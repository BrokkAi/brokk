//! ChatGPT-subscription-backed LLM backend.
//!
//! Talks to `https://chatgpt.com/backend-api/codex/responses` (the
//! Responses API, not Chat Completions) using the OAuth `access_token`
//! and `chatgpt_account_id` stored in `~/.codex/auth.json`. This is the
//! same endpoint Codex CLI hits when you `codex login` with a ChatGPT
//! Plus/Pro/Enterprise account, so usage counts against the ChatGPT
//! subscription rather than against an `OPENAI_API_KEY`.
//!
//! Why a separate client? The standard `/v1/chat/completions` path takes
//! Chat Completions JSON (messages, tool_calls). The ChatGPT backend
//! takes the Responses API shape (typed `input` items, `function_call` /
//! `function_call_output`) and streams a different SSE schema
//! (`response.output_text.delta`, `response.output_item.done`,
//! `response.completed`). Reusing `OpenAiClient` would mean entangling
//! two protocols in one type; a sibling client is cleaner.
//!
//! Trust posture: the `Authorization` and `ChatGPT-Account-ID` headers
//! mirror what `codex` CLI sends. We deliberately set `originator:
//! codex_cli_rs` and `User-Agent: codex_cli_rs ...` so brokk-acp shows
//! up identically to Codex CLI from the server's perspective -- this is
//! a pragmatic compatibility choice, not impersonation: the user *is*
//! authenticating with their own OAuth tokens.

use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, anyhow};
use futures::Stream;
use futures::StreamExt;
use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

use crate::codex_auth::{AuthDotJson, is_stale, read_auth_dot_json, refresh_if_stale, urlencode};
use crate::llm_client::{
    ChatMessage, FunctionCall, LlmBackend, LlmResponse, ToolCall, ToolDefinition,
};

// Codex CLI's `chatgpt_base_url` default is
// `https://chatgpt.com/backend-api/codex`; the Responses API and the
// model-discovery endpoint both live under it. We spell each full URL
// out below rather than concatenating from a shared base so the strings
// are greppable verbatim.

/// Streaming completions endpoint (Responses API).
const CHATGPT_RESPONSES_URL: &str = "https://chatgpt.com/backend-api/codex/responses";

/// Model discovery endpoint. Returns `{"models": [{"slug": ..., "display_name": ..., ...}]}`
/// for the slugs the user's ChatGPT plan can route to. Codex CLI fetches
/// this on startup and caches it -- we do the same so the picker stays
/// in step with whatever models OpenAI currently offers.
const CHATGPT_MODELS_URL: &str = "https://chatgpt.com/backend-api/codex/models";

/// Originator header value Codex CLI sends. The server gates ChatGPT-
/// subscription usage on this identity (alongside the OAuth token), so
/// matching it is the difference between the request being honored on
/// the ChatGPT plan and being rejected as an unrecognized client.
const ORIGINATOR: &str = "codex_cli_rs";

/// Mirror Codex CLI's idle-timeout posture: long reasoning pauses are
/// fine, but a server that drip-feeds keepalives forever should not
/// hold the connection open. Aligned with `OpenAiClient`'s constant.
const IDLE_CHUNK_TIMEOUT: Duration = Duration::from_secs(90);

/// Last-resort fallback if `/models` is unreachable at startup. Kept
/// to a single, well-known slug so we don't ship a stale, multi-entry
/// picker that misleads the user (the original bug). One slug is enough
/// to bootstrap a session; the user can always type another at the
/// `/config` prompt and the server forwards it verbatim.
const FALLBACK_CHATGPT_MODEL: &str = "gpt-5-codex";

/// `client_version` we report to the ChatGPT backend. The server uses
/// it to gate per-model rollout via each `ModelInfo.minimal_client_version`:
/// any model whose minimum exceeds the value we send is filtered out
/// of `/models` before it reaches us. Sending our own crate version
/// (e.g. `0.1.0`) signals we're a primitive client and the server
/// hands back only the lowest-common-denominator entry, which is what
/// produced the "single old model" picker.
///
/// We pin this to a recent Codex CLI release tag so we get the same
/// model list the official client gets. Bump it when the picker starts
/// hiding new models that codex itself shows. (Spec lives at
/// `codex-rs/Cargo.toml#workspace.package.version`; current GitHub
/// releases tag e.g. `rust-v0.129.0-alpha.10` resolve to a
/// `client_version_to_whole()` of `0.129.0`.) This is a shim, not
/// impersonation: the user *is* authenticating with their own OAuth
/// tokens, we're just declaring "I can handle any model Codex CLI
/// at this version can handle."
const CODEX_COMPAT_CLIENT_VERSION: &str = "0.129.0";

/// LLM backend that proxies to the ChatGPT subscription via the
/// Responses API. Reads `~/.codex/auth.json` on every request and
/// transparently refreshes the OAuth tokens when they go stale.
pub struct CodexClient {
    http: reqwest::Client,
    /// Serialize concurrent token-refresh attempts. Without this, two
    /// in-flight prompts hitting a 401 race each other into the refresh
    /// endpoint and one of the resulting `refresh_token` values gets
    /// invalidated by the server's rotation policy.
    refresh_lock: Arc<Mutex<()>>,
}

impl std::fmt::Debug for CodexClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CodexClient").finish()
    }
}

impl Default for CodexClient {
    fn default() -> Self {
        Self::new()
    }
}

impl CodexClient {
    pub fn new() -> Self {
        // The ChatGPT backend sits behind Cloudflare. Without a cookie
        // jar we drop Cloudflare's `__cf_bm` / `cf_clearance` / etc. set
        // on the first response, which makes the bot manager
        // increasingly suspicious of us across requests -- it can return
        // 403 or a challenge HTML page instead of JSON, and the
        // `/models` endpoint is more aggressive about that than
        // `/responses`. `cookie_store(true)` gives us a per-client jar
        // that quietly accumulates those cookies. We're not as strict
        // as codex-rs about allowlisting only Cloudflare names; the
        // jar is local to this client and the only host we talk to is
        // chatgpt.com.
        //
        // The User-Agent matches Codex CLI's `codex_cli_rs/<ver> (<os>)`
        // shape so we present as one of the first-party originators
        // the consent screen + Responses backend recognize. Cloudflare
        // is sensitive to user-agent strings that look like bots.
        let user_agent = format!(
            "{ORIGINATOR}/{ver} (brokk-acp; {os})",
            ver = env!("CARGO_PKG_VERSION"),
            os = std::env::consts::OS,
        );
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(600))
            .cookie_store(true)
            .user_agent(user_agent)
            .build()
            .expect("failed to build HTTP client");
        Self {
            http,
            refresh_lock: Arc::new(Mutex::new(())),
        }
    }

    /// Load fresh credentials from disk, refreshing the OAuth tokens if
    /// they're past Codex's 8-day staleness window. The fast path
    /// (credentials still fresh) bypasses `refresh_lock` so unrelated
    /// prompts don't queue up behind each other on a no-op disk read;
    /// the lock is only acquired when an actual refresh is warranted,
    /// at which point we re-read under the lock to avoid duplicate
    /// refreshes if another worker beat us to it.
    async fn load_credentials(&self) -> Result<ChatGptCredentials> {
        let auth = read_auth_dot_json()?.ok_or_else(|| {
            anyhow!("~/.codex/auth.json not found; run /codex-login to authenticate")
        })?;
        if !is_chatgpt_mode(&auth) {
            anyhow::bail!(
                "auth.json has auth_mode={:?} (need \"chatgpt\" for subscription routing); \
                 re-run /codex-login or drop --use-codex to fall back to OPENAI_API_KEY",
                auth.auth_mode
            );
        }
        if !is_stale(&auth) {
            return ChatGptCredentials::from_auth(&auth);
        }

        // Stale -- serialize the refresh so concurrent prompts don't
        // race each other into the refresh endpoint and invalidate one
        // of the resulting refresh_token rotations.
        let _guard = self.refresh_lock.lock().await;
        // Re-read under the lock: another worker might have refreshed
        // while we waited. Skip the refresh if so.
        let mut auth = read_auth_dot_json()?.ok_or_else(|| {
            anyhow!("~/.codex/auth.json disappeared while waiting for refresh lock")
        })?;
        if !is_chatgpt_mode(&auth) {
            anyhow::bail!("auth.json switched out of chatgpt mode while waiting for refresh lock");
        }
        if is_stale(&auth)
            && let Err(e) = refresh_if_stale(&mut auth).await
        {
            tracing::warn!("proactive token refresh failed (will retry on 401): {e:#}");
        }
        ChatGptCredentials::from_auth(&auth)
    }

    /// Force a fresh token regardless of `last_refresh`. Used after a
    /// 401 to retry once with rotated credentials before giving up.
    async fn force_refresh(&self) -> Result<ChatGptCredentials> {
        let _guard = self.refresh_lock.lock().await;
        let mut auth = read_auth_dot_json()?
            .ok_or_else(|| anyhow!("~/.codex/auth.json disappeared between requests"))?;
        if !is_chatgpt_mode(&auth) {
            anyhow::bail!("auth.json no longer in chatgpt mode");
        }
        // Pretend the credentials are old enough to need refresh by
        // backdating last_refresh past Codex's 8-day window. We mutate
        // this in memory only -- writing the backdated marker to disk
        // would let other workers observe stale credentials and pile
        // into the refresh endpoint themselves. refresh_if_stale will
        // persist the new credentials atomically on success.
        auth.last_refresh = Some(chrono::Utc::now() - chrono::Duration::days(30));
        if let Err(e) = refresh_if_stale(&mut auth).await {
            return Err(e.context("forced token refresh failed"));
        }
        ChatGptCredentials::from_auth(&auth)
    }

    async fn stream_chat_impl(
        &self,
        model: String,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> Result<LlmResponse> {
        let creds = self.load_credentials().await?;
        let body = build_responses_request(&model, &messages, tools.as_deref());
        match self
            .send_responses_request(&creds, &body, on_token, cancel.clone())
            .await
        {
            Ok(resp) => Ok(resp),
            Err(e) if is_unauthorized(&e) => {
                tracing::info!("ChatGPT backend returned 401; refreshing tokens and retrying once");
                let creds = self.force_refresh().await?;
                // Caller's on_token sink is consumed by the first attempt;
                // build a no-op sink for the retry so the trait stays
                // FnMut-only. Streaming text from the retry still flows
                // back via `LlmResponse::Text`.
                let noop: Box<dyn FnMut(&str) + Send> = Box::new(|_| {});
                self.send_responses_request(&creds, &body, noop, cancel)
                    .await
            }
            Err(e) => Err(e),
        }
    }

    async fn send_responses_request(
        &self,
        creds: &ChatGptCredentials,
        body: &ResponsesRequest,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> Result<LlmResponse> {
        let req = self
            .http
            .post(CHATGPT_RESPONSES_URL)
            .header("Authorization", format!("Bearer {}", creds.access_token))
            .header("ChatGPT-Account-ID", &creds.account_id)
            .header("originator", ORIGINATOR)
            .header("Accept", "text/event-stream")
            .json(body);

        let resp = req.send().await.context("posting Responses API request")?;
        let status = resp.status();
        if !status.is_success() {
            let body_text = resp.text().await.unwrap_or_default();
            return Err(anyhow::Error::new(ChatGptHttpError {
                status,
                body: body_text.trim().to_string(),
            }));
        }

        let stream = resp
            .bytes_stream()
            .map(|r| r.map(|b| b.to_vec()).map_err(anyhow::Error::from));

        drive_responses_sse_stream(stream, on_token, cancel, IDLE_CHUNK_TIMEOUT).await
    }

    /// Discover usable model slugs by hitting `chatgpt.com/backend-api/codex/models`.
    /// We deliberately don't ship a hardcoded picker any more: the
    /// model lineup moves faster than our release cadence, and shipping
    /// stale slugs (e.g. `gpt-5-pro` after that family is retired)
    /// gives users an autocomplete full of models that 401 on first
    /// use. On any error here we fall back to a single known slug
    /// (`FALLBACK_CHATGPT_MODEL`) so ACP `initialize` still advertises
    /// *something*; the user can override via `--default-model` or the
    /// `/config` model picker.
    async fn list_models_impl(&self) -> Result<Vec<String>> {
        let creds = match self.load_credentials().await {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("skipping ChatGPT model discovery (credentials not ready): {e:#}");
                return Ok(vec![FALLBACK_CHATGPT_MODEL.to_string()]);
            }
        };
        match fetch_chatgpt_models(&self.http, &creds).await {
            Ok(slugs) if !slugs.is_empty() => Ok(slugs),
            Ok(_) => {
                tracing::warn!(
                    "ChatGPT /models endpoint returned no slugs; falling back to {FALLBACK_CHATGPT_MODEL}"
                );
                Ok(vec![FALLBACK_CHATGPT_MODEL.to_string()])
            }
            Err(e) => {
                tracing::warn!(
                    "ChatGPT model discovery failed ({CHATGPT_MODELS_URL}): {e:#}; falling back to {FALLBACK_CHATGPT_MODEL}"
                );
                Ok(vec![FALLBACK_CHATGPT_MODEL.to_string()])
            }
        }
    }
}

/// GET `chatgpt.com/backend-api/codex/models?client_version=...` and
/// return the slugs. Sorted by the server-supplied `priority`
/// (descending) so the most recommended model surfaces first in the
/// picker -- matches Codex CLI's ordering.
///
/// We attach `client_version` because the ChatGPT backend uses it for
/// version-gated rollout (older clients see a different list). Reading
/// the body as bytes first lets us surface an excerpt in the error
/// message when the server returns a Cloudflare HTML challenge or any
/// other non-JSON payload, which used to fail silently with `parsing
/// /models JSON: expected value at line 1 column 1`.
async fn fetch_chatgpt_models(
    http: &reqwest::Client,
    creds: &ChatGptCredentials,
) -> Result<Vec<String>> {
    let url = format!(
        "{CHATGPT_MODELS_URL}?client_version={}",
        urlencode(CODEX_COMPAT_CLIENT_VERSION)
    );
    let resp = http
        .get(&url)
        .header("Authorization", format!("Bearer {}", creds.access_token))
        .header("ChatGPT-Account-ID", &creds.account_id)
        .header("originator", ORIGINATOR)
        .header("Accept", "application/json")
        .send()
        .await
        .context("GET /models")?;
    let status = resp.status();
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(ToString::to_string);
    let body_bytes = resp
        .bytes()
        .await
        .context("reading /models response body")?;

    if !status.is_success() {
        let excerpt = body_excerpt(&body_bytes, 256);
        anyhow::bail!(
            "ChatGPT /models returned HTTP {status} (content-type: {ct}): {excerpt}",
            ct = content_type.as_deref().unwrap_or("(none)")
        );
    }
    // Cloudflare challenges come back 200 OK with text/html. Fail loud
    // rather than try to parse them as JSON.
    if let Some(ct) = &content_type
        && !ct.contains("json")
    {
        let excerpt = body_excerpt(&body_bytes, 256);
        anyhow::bail!("ChatGPT /models returned non-JSON response (content-type: {ct}): {excerpt}");
    }
    let parsed: ChatGptModelsResponse = serde_json::from_slice(&body_bytes).with_context(|| {
        format!(
            "parsing /models JSON (excerpt: {})",
            body_excerpt(&body_bytes, 256)
        )
    })?;
    let mut models = parsed.models;
    // Codex's `ModelVisibility` enum has three values: `list` (show in
    // picker), `hide` (don't show but still callable), and `none`
    // (internal-only model used by Codex's review/automation hooks --
    // e.g. `codex-auto-review`). Codex CLI itself only puts `list`
    // models in its picker (`show_in_picker = info.visibility ==
    // ModelVisibility::List`). Match that exactly so we don't surface
    // automation-only slugs that the user can't sensibly chat with.
    //
    // The previous filter looked for `"hidden"` (wrong serialized
    // name) and ended up keeping `hide` *and* `none` entries, which is
    // how `codex-auto-review` leaked into the picker.
    models.retain(|m| m.visibility.as_deref() == Some("list"));
    // Higher priority first -- Codex's UI does the same. Stable sort so
    // ties keep server order (which is already curated).
    models.sort_by_key(|m| std::cmp::Reverse(m.priority));
    tracing::info!(
        "ChatGPT /models returned {} slugs after filtering: {:?}",
        models.len(),
        models.iter().map(|m| m.slug.as_str()).collect::<Vec<_>>()
    );
    Ok(models.into_iter().map(|m| m.slug).collect())
}

/// Render up to `limit` bytes of `body` as a debug-safe string. Used
/// only in error paths -- we don't trust the body to be UTF-8 (a
/// Cloudflare challenge page might be) but `from_utf8_lossy` always
/// gives us *something* readable in logs.
fn body_excerpt(body: &[u8], limit: usize) -> String {
    let slice = if body.len() > limit {
        &body[..limit]
    } else {
        body
    };
    let s = String::from_utf8_lossy(slice).replace('\n', " ");
    if body.len() > limit {
        format!("{s}... (truncated, {} total bytes)", body.len())
    } else {
        s
    }
}

#[derive(Debug, Deserialize)]
struct ChatGptModelsResponse {
    #[serde(default)]
    models: Vec<ChatGptModelEntry>,
}

#[derive(Debug, Deserialize)]
struct ChatGptModelEntry {
    slug: String,
    #[serde(default)]
    visibility: Option<String>,
    /// Server-supplied ordering hint. Higher = more prominent in the
    /// picker. Default to 0 if absent so missing-field models sort last.
    #[serde(default)]
    priority: i32,
}

impl LlmBackend for CodexClient {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>> {
        Box::pin(self.list_models_impl())
    }

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<LlmResponse>> {
        let model = model.to_string();
        Box::pin(self.stream_chat_impl(model, messages, tools, on_token, cancel))
    }
}

// ---------------------------------------------------------------------------
// Credentials extracted from auth.json
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct ChatGptCredentials {
    access_token: String,
    account_id: String,
}

impl ChatGptCredentials {
    fn from_auth(auth: &AuthDotJson) -> Result<Self> {
        let tokens = auth
            .tokens
            .as_ref()
            .ok_or_else(|| anyhow!("auth.json has no `tokens` block; run /codex-login again"))?;
        if tokens.access_token.is_empty() {
            anyhow::bail!("auth.json `tokens.access_token` is empty");
        }
        if tokens.account_id.is_empty() {
            anyhow::bail!("auth.json `tokens.account_id` is empty");
        }
        Ok(Self {
            access_token: tokens.access_token.clone(),
            account_id: tokens.account_id.clone(),
        })
    }
}

fn is_chatgpt_mode(auth: &AuthDotJson) -> bool {
    matches!(auth.auth_mode.as_deref(), Some("chatgpt"))
}

/// Typed HTTP error so `is_unauthorized` can match on the status code
/// rather than scanning the formatted error message. The previous
/// string-match approach drifted out of sync whenever the upstream
/// wording changed and conflated unrelated bodies that happened to
/// mention "invalid_token". Putting the `StatusCode` in a downcastable
/// struct makes the 401-retry path robust without leaking reqwest
/// types to public APIs.
#[derive(Debug)]
struct ChatGptHttpError {
    status: reqwest::StatusCode,
    body: String,
}

impl std::fmt::Display for ChatGptHttpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "ChatGPT Responses API returned HTTP {}: {}",
            self.status, self.body
        )
    }
}

impl std::error::Error for ChatGptHttpError {}

/// Detect 401 by walking the anyhow chain for our typed
/// `ChatGptHttpError`. Returns false for transport errors or other
/// non-HTTP failures, which is the intended behavior -- we only retry
/// once on an actual unauthorized response from the gateway.
fn is_unauthorized(err: &anyhow::Error) -> bool {
    err.chain()
        .find_map(|cause| cause.downcast_ref::<ChatGptHttpError>())
        .map(|e| e.status == reqwest::StatusCode::UNAUTHORIZED)
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Responses API request shape
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub(crate) struct ResponsesRequest {
    pub(crate) model: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) instructions: Option<String>,
    pub(crate) input: Vec<ResponsesInputItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tools: Option<Vec<ResponsesToolDef>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tool_choice: Option<String>,
    pub(crate) parallel_tool_calls: bool,
    pub(crate) stream: bool,
    /// Don't ask the server to retain this request; brokk owns its own
    /// session/turn persistence and we don't want a side-channel copy
    /// living on OpenAI's storage tied to the user's subscription.
    pub(crate) store: bool,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(crate) enum ResponsesInputItem {
    Message {
        role: String,
        content: Vec<ResponsesContent>,
    },
    FunctionCall {
        name: String,
        arguments: String,
        call_id: String,
    },
    FunctionCallOutput {
        call_id: String,
        output: String,
    },
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(crate) enum ResponsesContent {
    InputText { text: String },
    OutputText { text: String },
}

/// Responses API tool descriptor. Differs from Chat Completions: name
/// and parameters are at the top level (not nested under `function`),
/// and there's no separate `type: "function"` wrapping object.
#[derive(Debug, Serialize)]
pub(crate) struct ResponsesToolDef {
    pub(crate) r#type: String,
    pub(crate) name: String,
    pub(crate) description: String,
    pub(crate) parameters: serde_json::Value,
}

/// Convert brokk's Chat-Completions-shaped messages into the typed
/// Responses-API `input` items. System messages collapse into the
/// top-level `instructions` field (concatenated in arrival order so
/// later system prompts can extend earlier ones); the rest map 1:1 with
/// a small splay because each assistant tool-call expands to one
/// `function_call` item per call.
pub(crate) fn build_responses_request(
    model: &str,
    messages: &[ChatMessage],
    tools: Option<&[ToolDefinition]>,
) -> ResponsesRequest {
    let mut instructions_parts: Vec<String> = Vec::new();
    let mut input: Vec<ResponsesInputItem> = Vec::new();

    for msg in messages {
        match msg.role.as_str() {
            "system" => {
                if let Some(text) = &msg.content {
                    instructions_parts.push(text.clone());
                } else {
                    tracing::debug!(
                        "dropping system message with no content when building Responses input"
                    );
                }
            }
            "user" => {
                if let Some(text) = &msg.content {
                    input.push(ResponsesInputItem::Message {
                        role: "user".to_string(),
                        content: vec![ResponsesContent::InputText { text: text.clone() }],
                    });
                } else {
                    tracing::debug!(
                        "dropping user message with no content when building Responses input"
                    );
                }
            }
            "assistant" => {
                if let Some(calls) = &msg.tool_calls {
                    for call in calls {
                        input.push(ResponsesInputItem::FunctionCall {
                            name: call.function.name.clone(),
                            arguments: call.function.arguments.clone(),
                            call_id: call.id.clone(),
                        });
                    }
                } else if let Some(text) = &msg.content {
                    input.push(ResponsesInputItem::Message {
                        role: "assistant".to_string(),
                        content: vec![ResponsesContent::OutputText { text: text.clone() }],
                    });
                } else {
                    tracing::debug!(
                        "dropping assistant message with neither tool_calls nor content when \
                         building Responses input"
                    );
                }
            }
            "tool" => {
                if let (Some(call_id), Some(output)) = (&msg.tool_call_id, &msg.content) {
                    input.push(ResponsesInputItem::FunctionCallOutput {
                        call_id: call_id.clone(),
                        output: output.clone(),
                    });
                } else {
                    tracing::warn!(
                        "dropping malformed tool message when building Responses input: \
                         tool_call_id_present={} content_present={}",
                        msg.tool_call_id.is_some(),
                        msg.content.is_some()
                    );
                }
            }
            other => {
                tracing::debug!("dropping unknown role {other:?} when building Responses input");
            }
        }
    }

    let tools = tools.map(|defs| {
        defs.iter()
            .map(|d| ResponsesToolDef {
                r#type: "function".to_string(),
                name: d.function.name.clone(),
                description: d.function.description.clone(),
                parameters: d.function.parameters.clone(),
            })
            .collect()
    });
    let tool_choice = tools.as_ref().map(|_| "auto".to_string());

    let instructions = if instructions_parts.is_empty() {
        None
    } else {
        Some(instructions_parts.join("\n\n"))
    };

    ResponsesRequest {
        model: model.to_string(),
        instructions,
        input,
        tools,
        tool_choice,
        parallel_tool_calls: true,
        stream: true,
        store: false,
    }
}

// ---------------------------------------------------------------------------
// Responses API SSE parsing
// ---------------------------------------------------------------------------

/// Subset of `ResponsesStreamEvent` from codex-rs we actually consume.
/// Unknown event types (`response.reasoning_summary_text.delta`,
/// `response.metadata`, etc.) deserialize successfully but contribute
/// nothing -- we surface only text deltas and final-shape items.
#[derive(Debug, Deserialize)]
struct StreamEvent {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    delta: Option<String>,
    #[serde(default)]
    item: Option<serde_json::Value>,
    #[serde(default)]
    response: Option<ResponseFinal>,
}

/// Body of a `response.completed` / `response.failed` event. Most
/// fields are unused; we keep the struct minimal so server-side
/// schema additions don't break parsing.
#[derive(Debug, Deserialize)]
struct ResponseFinal {
    #[serde(default)]
    error: Option<ResponseError>,
}

#[derive(Debug, Deserialize)]
struct ResponseError {
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    message: Option<String>,
}

/// Output item parsed from `response.output_item.done`. Mirrors
/// `codex_protocol::models::ResponseItem` but only the variants we
/// surface (assistant message text and function calls).
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum OutputItem {
    Message {
        #[serde(default)]
        role: Option<String>,
        #[serde(default)]
        content: Vec<OutputItemContent>,
    },
    FunctionCall {
        #[serde(default)]
        id: Option<String>,
        name: String,
        arguments: String,
        #[serde(default)]
        call_id: Option<String>,
    },
    /// Fallback for variants we don't model (Reasoning, LocalShellCall,
    /// ToolSearchCall, ...). Keeps the deserializer permissive so a
    /// future server adding new item types doesn't poison the stream.
    #[serde(other)]
    Other,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum OutputItemContent {
    OutputText {
        #[serde(default)]
        text: String,
    },
    /// Same fallback strategy as `OutputItem::Other` -- ignore content
    /// shapes (input_image, refusal, ...) we don't render.
    #[serde(other)]
    Other,
}

/// Drive a Responses-API SSE byte stream until `response.completed`,
/// the stream ends, or the cancellation token fires. Emits text deltas
/// to `on_token` as they arrive; collects function calls from
/// `response.output_item.done` events. Idle-timeout posture matches
/// `OpenAiClient::drive_sse_stream`.
async fn drive_responses_sse_stream<S>(
    mut stream: S,
    mut on_token: Box<dyn FnMut(&str) + Send>,
    cancel: CancellationToken,
    idle: Duration,
) -> Result<LlmResponse>
where
    S: Stream<Item = Result<Vec<u8>>> + Unpin,
{
    let mut full_text = String::new();
    let mut tool_calls: Vec<ToolCall> = Vec::new();
    let mut raw_buf: Vec<u8> = Vec::new();
    let mut deadline = tokio::time::Instant::now() + idle;
    let mut completed = false;
    let mut failure: Option<anyhow::Error> = None;
    // Track whether any text deltas were actually delivered so the
    // output_item.done backfill below can distinguish "no deltas yet"
    // from "deltas arrived but happened to be empty strings". Using
    // `full_text.is_empty()` for that decision conflated the two and
    // could double-emit the assistant text when a server sent both.
    let mut deltas_received = false;

    loop {
        tokio::select! {
            _ = cancel.cancelled() => {
                tracing::info!("Codex streaming cancelled by client");
                break;
            }
            chunk_or_timeout = tokio::time::timeout_at(deadline, stream.next()) => {
                let chunk_opt = match chunk_or_timeout {
                    Ok(opt) => opt,
                    Err(_elapsed) => anyhow::bail!(
                        "Codex Responses stream made no meaningful progress for {}s; aborting",
                        idle.as_secs()
                    ),
                };
                let Some(chunk) = chunk_opt else { break; };
                let chunk = chunk.context("Codex stream read error")?;
                raw_buf.extend_from_slice(&chunk);

                let mut made_progress = false;

                while let Some(pos) = raw_buf.iter().position(|&b| b == b'\n') {
                    let line_bytes = raw_buf.drain(..=pos).collect::<Vec<_>>();
                    let line = String::from_utf8_lossy(&line_bytes).trim().to_string();

                    if line.is_empty() || line.starts_with(':') || line.starts_with("event:") {
                        // Blank lines, SSE comments, and `event:` markers
                        // are all non-data: the JSON we need rides on
                        // `data: ...` lines and carries its own `type`
                        // discriminator, so we don't track `event:`.
                        continue;
                    }

                    let data = if let Some(stripped) = line.strip_prefix("data: ") {
                        stripped.trim()
                    } else if let Some(stripped) = line.strip_prefix("data:") {
                        stripped.trim()
                    } else {
                        continue;
                    };

                    if data == "[DONE]" {
                        completed = true;
                        break;
                    }

                    let Ok(event) = serde_json::from_str::<StreamEvent>(data) else {
                        tracing::debug!("skipping unparseable Responses SSE chunk: {data}");
                        continue;
                    };

                    match event.kind.as_str() {
                        "response.output_text.delta" => {
                            if let Some(delta) = event.delta {
                                made_progress = true;
                                deltas_received = true;
                                on_token(&delta);
                                full_text.push_str(&delta);
                            }
                        }
                        "response.output_item.done" => {
                            if let Some(item_val) = event.item
                                && let Ok(item) = serde_json::from_value::<OutputItem>(item_val)
                            {
                                match item {
                                    OutputItem::Message { role, content } => {
                                        // Some servers stream the entire
                                        // assistant message via output_item.done
                                        // without ever emitting deltas (e.g. a
                                        // very short reply or a cached completion).
                                        // Backfill `full_text` from the item only
                                        // when no deltas were seen -- otherwise
                                        // the deltas already carry the assistant
                                        // text and re-emitting it duplicates the
                                        // content for the caller.
                                        if role.as_deref() == Some("assistant") && !deltas_received {
                                            for c in content {
                                                if let OutputItemContent::OutputText { text } = c {
                                                    on_token(&text);
                                                    full_text.push_str(&text);
                                                }
                                            }
                                        }
                                        made_progress = true;
                                    }
                                    OutputItem::FunctionCall {
                                        id,
                                        name,
                                        arguments,
                                        call_id,
                                    } => {
                                        // The Responses API uses `call_id` as
                                        // the persistent identifier; brokk's
                                        // `ToolCall.id` doubles as the
                                        // function_call_output `call_id`, so
                                        // we copy that across. `id` is the
                                        // server-side item id and not useful
                                        // for tool-result correlation.
                                        let resolved_id = call_id
                                            .or(id)
                                            .unwrap_or_else(|| format!("call_{}", tool_calls.len()));
                                        tool_calls.push(ToolCall {
                                            id: resolved_id,
                                            r#type: "function".to_string(),
                                            function: FunctionCall { name, arguments },
                                        });
                                        made_progress = true;
                                    }
                                    OutputItem::Other => {}
                                }
                            }
                        }
                        "response.completed" => {
                            completed = true;
                            break;
                        }
                        "response.failed" => {
                            let msg = event
                                .response
                                .and_then(|r| r.error)
                                .map(|e| {
                                    let code = e.code.unwrap_or_else(|| "unknown".to_string());
                                    let body = e.message.unwrap_or_default();
                                    format!("{code}: {body}")
                                })
                                .unwrap_or_else(|| "unknown error".to_string());
                            failure = Some(anyhow!("Codex Responses stream failed: {msg}"));
                            completed = true;
                            break;
                        }
                        // Reasoning summaries, metadata, rate-limit
                        // snapshots, etc. -- we don't surface them but
                        // count them as activity so the idle timer
                        // doesn't fire mid-think.
                        _ => {
                            made_progress = true;
                        }
                    }
                }

                if completed {
                    break;
                }
                if made_progress {
                    deadline = tokio::time::Instant::now() + idle;
                }
            }
        }
    }

    if let Some(err) = failure {
        return Err(err);
    }

    if cancel.is_cancelled() {
        return Ok(LlmResponse::Text(full_text));
    }

    if tool_calls.is_empty() {
        Ok(LlmResponse::Text(full_text))
    } else {
        Ok(LlmResponse::ToolCalls {
            text: full_text,
            calls: tool_calls,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llm_client::{ChatMessage, FunctionCall, FunctionDef, ToolCall, ToolDefinition};
    use serde_json::json;

    fn sink_collecting(
        buf: std::sync::Arc<std::sync::Mutex<String>>,
    ) -> Box<dyn FnMut(&str) + Send> {
        Box::new(move |t: &str| buf.lock().unwrap().push_str(t))
    }

    #[test]
    fn build_request_collapses_system_messages_into_instructions() {
        let messages = vec![
            ChatMessage::system("be helpful"),
            ChatMessage::system("also be brief"),
            ChatMessage::user("hi"),
        ];
        let req = build_responses_request("gpt-5-codex", &messages, None);
        assert_eq!(
            req.instructions.as_deref(),
            Some("be helpful\n\nalso be brief")
        );
        assert_eq!(req.input.len(), 1);
        match &req.input[0] {
            ResponsesInputItem::Message { role, content } => {
                assert_eq!(role, "user");
                assert_eq!(content.len(), 1);
                match &content[0] {
                    ResponsesContent::InputText { text } => assert_eq!(text, "hi"),
                    _ => panic!("expected input_text"),
                }
            }
            _ => panic!("expected user message"),
        }
        assert!(req.tools.is_none());
        assert!(req.tool_choice.is_none());
        assert!(!req.store);
        assert!(req.stream);
    }

    #[test]
    fn build_request_emits_function_call_per_assistant_tool_call() {
        let messages = vec![
            ChatMessage::user("search for X"),
            ChatMessage::assistant_tool_calls(vec![ToolCall {
                id: "fc_abc".to_string(),
                r#type: "function".to_string(),
                function: FunctionCall {
                    name: "search".to_string(),
                    arguments: r#"{"q":"X"}"#.to_string(),
                },
            }]),
            ChatMessage::tool_result("fc_abc", "search", "no results"),
        ];
        let req = build_responses_request("gpt-5-codex", &messages, None);
        assert_eq!(req.input.len(), 3);
        match &req.input[1] {
            ResponsesInputItem::FunctionCall {
                name,
                arguments,
                call_id,
            } => {
                assert_eq!(name, "search");
                assert_eq!(arguments, r#"{"q":"X"}"#);
                assert_eq!(call_id, "fc_abc");
            }
            _ => panic!("expected function_call"),
        }
        match &req.input[2] {
            ResponsesInputItem::FunctionCallOutput { call_id, output } => {
                assert_eq!(call_id, "fc_abc");
                assert_eq!(output, "no results");
            }
            _ => panic!("expected function_call_output"),
        }
    }

    #[test]
    fn build_request_serializes_tools_at_top_level() {
        let tools = vec![ToolDefinition {
            r#type: "function".to_string(),
            function: FunctionDef {
                name: "ping".to_string(),
                description: "check liveness".to_string(),
                parameters: json!({"type": "object", "properties": {}}),
            },
        }];
        let req = build_responses_request("gpt-5", &[ChatMessage::user("hi")], Some(&tools));
        let serialized = serde_json::to_value(&req).unwrap();
        let tools = serialized.get("tools").unwrap().as_array().unwrap();
        assert_eq!(tools.len(), 1);
        // Responses API: name and parameters are top-level on the tool
        // object, not nested under `function` like Chat Completions.
        assert_eq!(tools[0]["type"], "function");
        assert_eq!(tools[0]["name"], "ping");
        assert_eq!(tools[0]["description"], "check liveness");
        assert!(tools[0].get("function").is_none());
        assert_eq!(serialized.get("tool_choice").unwrap(), "auto");
    }

    #[test]
    fn build_request_omits_assistant_text_when_tool_calls_present() {
        // The Chat Completions input lets assistant messages carry both
        // `content` and `tool_calls`; the Responses API splits those
        // into separate items, and brokk's tool-loop never round-trips
        // the assistant's pre-tool text. Match that here.
        let messages = vec![ChatMessage {
            role: "assistant".to_string(),
            content: Some("ignored preamble".to_string()),
            tool_calls: Some(vec![ToolCall {
                id: "fc_1".to_string(),
                r#type: "function".to_string(),
                function: FunctionCall {
                    name: "noop".to_string(),
                    arguments: "{}".to_string(),
                },
            }]),
            tool_call_id: None,
            name: None,
        }];
        let req = build_responses_request("gpt-5", &messages, None);
        assert_eq!(req.input.len(), 1);
        assert!(matches!(
            req.input[0],
            ResponsesInputItem::FunctionCall { .. }
        ));
    }

    #[tokio::test]
    async fn sse_parser_streams_text_deltas_and_tool_calls() {
        let raw = concat!(
            "data: {\"type\":\"response.created\",\"response\":{}}\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hel\"}\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}\n\n",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\",\"call_id\":\"fc_1\"}}\n\n",
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
        );
        let stream = futures::stream::iter(vec![Ok(raw.as_bytes().to_vec())]);
        let collected = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        let cb = sink_collecting(collected.clone());
        let cancel = CancellationToken::new();
        let resp = drive_responses_sse_stream(stream, cb, cancel, Duration::from_secs(5))
            .await
            .expect("stream completes");
        match resp {
            LlmResponse::ToolCalls { text, calls } => {
                assert_eq!(text, "hello");
                assert_eq!(calls.len(), 1);
                assert_eq!(calls[0].function.name, "search");
                assert_eq!(calls[0].id, "fc_1");
            }
            other => panic!("expected ToolCalls, got {other:?}"),
        }
        assert_eq!(collected.lock().unwrap().as_str(), "hello");
    }

    #[tokio::test]
    async fn sse_parser_backfills_text_from_output_item_done_when_no_deltas() {
        // Some short replies ship the whole message in a single
        // output_item.done event with no preceding deltas. The parser
        // must still surface the assistant text in that case.
        let raw = concat!(
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}}\n\n",
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
        );
        let stream = futures::stream::iter(vec![Ok(raw.as_bytes().to_vec())]);
        let collected = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        let cb = sink_collecting(collected.clone());
        let cancel = CancellationToken::new();
        let resp = drive_responses_sse_stream(stream, cb, cancel, Duration::from_secs(5))
            .await
            .expect("stream completes");
        match resp {
            LlmResponse::Text(text) => assert_eq!(text, "ok"),
            other => panic!("expected Text, got {other:?}"),
        }
        assert_eq!(collected.lock().unwrap().as_str(), "ok");
    }

    #[tokio::test]
    async fn sse_parser_does_not_duplicate_text_when_deltas_and_output_item_done_overlap() {
        // Some servers send both a delta stream AND a final
        // output_item.done carrying the same assistant text. The
        // parser must surface the deltas (which already drove
        // on_token) and ignore the final item's content -- echoing
        // it would double the visible reply.
        let raw = concat!(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hel\"}\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}\n\n",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}}\n\n",
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
        );
        let stream = futures::stream::iter(vec![Ok(raw.as_bytes().to_vec())]);
        let collected = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        let cb = sink_collecting(collected.clone());
        let cancel = CancellationToken::new();
        let resp = drive_responses_sse_stream(stream, cb, cancel, Duration::from_secs(5))
            .await
            .expect("stream completes");
        match resp {
            LlmResponse::Text(text) => assert_eq!(text, "hello"),
            other => panic!("expected Text, got {other:?}"),
        }
        assert_eq!(collected.lock().unwrap().as_str(), "hello");
    }

    #[tokio::test]
    async fn sse_parser_surfaces_response_failed_as_error() {
        let raw = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}}\n\n";
        let stream = futures::stream::iter(vec![Ok(raw.as_bytes().to_vec())]);
        let collected = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        let cb = sink_collecting(collected.clone());
        let cancel = CancellationToken::new();
        let err = drive_responses_sse_stream(stream, cb, cancel, Duration::from_secs(5))
            .await
            .expect_err("response.failed must surface as Err");
        let msg = format!("{err:#}");
        assert!(msg.contains("rate_limit_exceeded"), "got: {msg}");
        assert!(msg.contains("slow down"), "got: {msg}");
    }

    #[tokio::test]
    async fn sse_parser_ignores_unknown_event_types() {
        // New event types (reasoning summaries, rate-limit metadata,
        // etc.) must not poison the stream -- they should keep the
        // idle timer alive but contribute nothing to the result.
        let raw = concat!(
            "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thinking\"}\n\n",
            "data: {\"type\":\"response.metadata\",\"metadata\":{}}\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n",
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
        );
        let stream = futures::stream::iter(vec![Ok(raw.as_bytes().to_vec())]);
        let collected = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        let cb = sink_collecting(collected.clone());
        let cancel = CancellationToken::new();
        let resp = drive_responses_sse_stream(stream, cb, cancel, Duration::from_secs(5))
            .await
            .expect("stream completes");
        match resp {
            LlmResponse::Text(text) => assert_eq!(text, "hi"),
            other => panic!("expected Text, got {other:?}"),
        }
    }

    #[test]
    fn unauthorized_detector_matches_codex_responses_401_shape() {
        let err = anyhow::Error::new(ChatGptHttpError {
            status: reqwest::StatusCode::UNAUTHORIZED,
            body: "{...}".to_string(),
        });
        assert!(is_unauthorized(&err));
        let other = anyhow::Error::new(ChatGptHttpError {
            status: reqwest::StatusCode::INTERNAL_SERVER_ERROR,
            body: "server error".to_string(),
        });
        assert!(!is_unauthorized(&other));
        // Non-HTTP errors (e.g. transport failures) must not be
        // misclassified as 401 -- the retry path would loop forever.
        let transport = anyhow!("connection reset");
        assert!(!is_unauthorized(&transport));
    }

    #[test]
    fn unauthorized_detector_walks_anyhow_chain() {
        // `is_unauthorized` runs after callers may have added their
        // own `.context(...)` -- the typed cause must still be
        // recoverable through the chain.
        let err = anyhow::Error::new(ChatGptHttpError {
            status: reqwest::StatusCode::UNAUTHORIZED,
            body: "expired".to_string(),
        })
        .context("posting Responses API request");
        assert!(is_unauthorized(&err));
    }

    #[test]
    fn parses_models_response_and_sorts_by_priority_descending() {
        // Mirror a real ChatGPT /models payload (fields trimmed to the
        // ones we deserialize). Visibility filtering follows Codex's
        // own `ModelVisibility` enum: only `list` is shown in pickers.
        // `hide` (callable but not in picker) and `none` (internal /
        // automation-only, e.g. `codex-auto-review`) are dropped so the
        // user doesn't see slugs that aren't meant for chat. Priority-
        // descending sort matches Codex CLI's UI ordering.
        let raw = r#"{
            "models": [
                {"slug": "gpt-low",     "priority": 1,   "visibility": "list"},
                {"slug": "gpt-high",    "priority": 100, "visibility": "list"},
                {"slug": "gpt-mid",     "priority": 50,  "visibility": "list"},
                {"slug": "gpt-hidden",  "priority": 999, "visibility": "hide"},
                {"slug": "auto-review", "priority": 999, "visibility": "none"},
                {"slug": "gpt-no-vis",  "priority": 999}
            ]
        }"#;
        let parsed: ChatGptModelsResponse = serde_json::from_str(raw).unwrap();
        let mut models = parsed.models;
        models.retain(|m| m.visibility.as_deref() == Some("list"));
        models.sort_by_key(|m| std::cmp::Reverse(m.priority));
        let slugs: Vec<&str> = models.iter().map(|m| m.slug.as_str()).collect();
        assert_eq!(slugs, vec!["gpt-high", "gpt-mid", "gpt-low"]);
    }

    #[test]
    fn parses_models_response_with_unknown_fields() {
        // The real payload carries dozens of fields we don't model
        // (reasoning levels, instructions templates, etc.). Make sure
        // they don't break our deserializer.
        let raw = r#"{
            "models": [
                {
                    "slug": "gpt-future",
                    "display_name": "GPT Future",
                    "priority": 10,
                    "supported_reasoning_levels": [],
                    "shell_type": "default_shell",
                    "visibility": "public",
                    "supported_in_api": true,
                    "base_instructions": "be helpful",
                    "supports_reasoning_summaries": false,
                    "support_verbosity": false,
                    "default_verbosity": null,
                    "apply_patch_tool_type": null,
                    "truncation_policy": {"type": "auto"},
                    "supports_parallel_tool_calls": true,
                    "experimental_supported_tools": [],
                    "availability_nux": null,
                    "upgrade": null
                }
            ]
        }"#;
        let parsed: ChatGptModelsResponse = serde_json::from_str(raw).unwrap();
        assert_eq!(parsed.models.len(), 1);
        assert_eq!(parsed.models[0].slug, "gpt-future");
    }
}
