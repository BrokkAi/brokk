use anyhow::{Context, Result};
use futures::Stream;
use futures::StreamExt;
use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::Duration;
use tokio_util::sync::CancellationToken;

/// Default value for the `--llm-idle-timeout-secs` CLI flag (and the
/// env var `BROKK_ACP_LLM_IDLE_TIMEOUT_SECS`). The actual value used
/// per request is a required parameter on `LlmBackend::stream_chat` --
/// callers cannot fall back to this implicitly.
///
/// Idle timeout semantics: maximum gap between two pieces of
/// *meaningful* SSE progress before we abort the request. "Meaningful
/// progress" is a parsed `data:` event that contributed content,
/// tool-call deltas, or `[DONE]`. Comments (`:keepalive\n`), blank
/// lines, and partial bytes that don't advance the parser do NOT reset
/// this timer -- otherwise a server or proxy could keep us alive
/// forever by drip-feeding pings.
///
/// Distinct from the reqwest client's overall `.timeout()` (wall-clock).
pub const DEFAULT_IDLE_CHUNK_TIMEOUT_SECS: u64 = 300;

/// Lower bound for both the `--llm-idle-timeout-secs` CLI flag and the
/// `/idle-timeout` slash command. 0 would mean "abort instantly", which
/// is never useful.
pub const MIN_IDLE_CHUNK_TIMEOUT_SECS: u64 = 1;

/// Upper bound for both the `--llm-idle-timeout-secs` CLI flag and the
/// `/idle-timeout` slash command. 24h is well above any realistic local
/// LLM prompt processing on consumer hardware and stops a typo'd huge
/// number from effectively disabling the stall detector.
pub const MAX_IDLE_CHUNK_TIMEOUT_SECS: u64 = 86_400;

/// Owning callback handed token deltas as the LLM streams them.
type TokenSink = Box<dyn FnMut(&str) + Send>;

// ---------------------------------------------------------------------------
// Tool calling types (OpenAI-compatible)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolDefinition {
    pub r#type: String,
    pub function: FunctionDef,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionDef {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub r#type: String,
    pub function: FunctionCall,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionCall {
    pub name: String,
    pub arguments: String,
}

/// What the LLM returned: either final text or tool calls to execute.
#[derive(Debug)]
pub enum LlmResponse {
    Text(String),
    ToolCalls { text: String, calls: Vec<ToolCall> },
}

// ---------------------------------------------------------------------------
// Chat message (extended for tool calling)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self {
            role: "system".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: "user".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn assistant(content: impl Into<String>) -> Self {
        Self {
            role: "assistant".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn assistant_tool_calls(calls: Vec<ToolCall>) -> Self {
        Self {
            role: "assistant".to_string(),
            content: None,
            tool_calls: Some(calls),
            tool_call_id: None,
            name: None,
        }
    }

    pub fn tool_result(
        tool_call_id: impl Into<String>,
        name: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        Self {
            role: "tool".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: Some(tool_call_id.into()),
            name: Some(name.into()),
        }
    }
}

// ---------------------------------------------------------------------------
// Model metadata
// ---------------------------------------------------------------------------

/// One reasoning-effort preset advertised by the server for a given model.
/// Mirrors the `supported_reasoning_levels[]` entries returned by the
/// ChatGPT `/models` endpoint. Backends without per-effort presets (Ollama,
/// OpenAI `/v1/models`) simply leave the per-model vec empty.
#[derive(Debug, Clone)]
pub struct ReasoningLevelPreset {
    pub effort: String,
    pub description: String,
}

/// Richer model descriptor surfaced through `LlmBackend::list_model_metadata`.
/// The `id` is the wire identifier the backend expects in `stream_chat`;
/// the optional reasoning fields are populated only for backends whose
/// catalog publishes them (today: `CodexClient`).
#[derive(Debug, Clone)]
pub struct ModelMetadata {
    pub id: String,
    pub default_reasoning_level: Option<String>,
    pub supported_reasoning_levels: Vec<ReasoningLevelPreset>,
}

impl ModelMetadata {
    /// Lift a bare model id to a metadata record with no reasoning data.
    /// Used by backends that don't publish reasoning presets, and by the
    /// default impl of `list_model_metadata` so existing `list_models`
    /// impls don't have to be rewritten.
    pub fn id_only(id: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            default_reasoning_level: None,
            supported_reasoning_levels: Vec::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// LLM backend trait
// ---------------------------------------------------------------------------

pub trait LlmBackend: Send + Sync {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>>;

    /// Same catalog as `list_models`, but carrying any per-model
    /// reasoning-effort presets the backend's discovery endpoint
    /// publishes. The default impl lifts each id to `ModelMetadata::id_only`,
    /// so backends that don't expose reasoning data don't need to override.
    fn list_model_metadata(&self) -> BoxFuture<'_, Result<Vec<ModelMetadata>>> {
        let fut = self.list_models();
        Box::pin(async move { Ok(fut.await?.into_iter().map(ModelMetadata::id_only).collect()) })
    }

    /// Stream a chat completion. `reasoning_effort` is honored only by
    /// backends that route to a reasoning-capable endpoint (today,
    /// `CodexClient` via the ChatGPT Responses API); other backends
    /// ignore it. `on_thought` receives chain-of-thought / reasoning
    /// text deltas separate from the assistant text on `on_token`;
    /// backends that don't surface reasoning never invoke it.
    ///
    /// `idle_timeout` is the maximum gap between two pieces of
    /// meaningful SSE progress before the backend aborts the stream.
    /// Threaded from the CLI flag `--llm-idle-timeout-secs` and the
    /// per-session `/idle-timeout` override.
    #[allow(clippy::too_many_arguments)]
    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        reasoning_effort: Option<&str>,
        on_token: Box<dyn FnMut(&str) + Send>,
        on_thought: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
        idle_timeout: Duration,
    ) -> BoxFuture<'_, Result<LlmResponse>>;
}

// ---------------------------------------------------------------------------
// Request/response types for the OpenAI-compatible API
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<ToolDefinition>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ModelEntry {
    id: String,
}

#[derive(Debug, Deserialize)]
struct ModelsResponse {
    data: Vec<ModelEntry>,
}

// SSE chunk types for streaming (extended for tool calls)

#[derive(Debug, Deserialize)]
struct ChatCompletionChunk {
    choices: Vec<ChunkChoice>,
}

#[derive(Debug, Deserialize)]
struct ChunkChoice {
    delta: ChunkDelta,
}

#[derive(Debug, Deserialize)]
struct ChunkDelta {
    #[serde(default)]
    content: Option<String>,
    #[serde(default)]
    tool_calls: Option<Vec<ChunkToolCall>>,
}

#[derive(Debug, Deserialize)]
struct ChunkToolCall {
    index: usize,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    function: Option<ChunkFunctionCall>,
}

#[derive(Debug, Deserialize)]
struct ChunkFunctionCall {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    arguments: Option<String>,
}

/// Accumulator for tool calls arriving as SSE fragments.
#[derive(Default)]
struct ToolCallAccumulator {
    calls: HashMap<usize, (String, String, String)>, // index -> (id, name, arguments)
}

impl ToolCallAccumulator {
    fn push(&mut self, chunk: &ChunkToolCall) {
        let entry = self
            .calls
            .entry(chunk.index)
            .or_insert_with(|| (String::new(), String::new(), String::new()));
        if let Some(id) = &chunk.id {
            entry.0 = id.clone();
        }
        if let Some(func) = &chunk.function {
            if let Some(name) = &func.name {
                entry.1 = name.clone();
            }
            if let Some(args) = &func.arguments {
                entry.2.push_str(args);
            }
        }
    }

    fn is_empty(&self) -> bool {
        self.calls.is_empty()
    }

    fn into_tool_calls(self) -> Vec<ToolCall> {
        let mut entries: Vec<_> = self.calls.into_iter().collect();
        entries.sort_by_key(|(idx, _)| *idx);
        entries
            .into_iter()
            .map(|(_, (id, name, arguments))| ToolCall {
                id,
                r#type: "function".to_string(),
                function: FunctionCall { name, arguments },
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// OpenAI-compatible client
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct OpenAiClient {
    base_url: String,
    api_key: Option<String>,
    http: reqwest::Client,
}

impl std::fmt::Debug for OpenAiClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OpenAiClient")
            .field("base_url", &self.base_url)
            .field("api_key", &self.api_key.as_ref().map(|_| "[REDACTED]"))
            .finish()
    }
}

impl OpenAiClient {
    pub fn new(base_url: String, api_key: Option<String>) -> Self {
        Self::with_default_headers(base_url, api_key, reqwest::header::HeaderMap::new())
    }

    /// Like `new`, but attaches `default_headers` to every request the
    /// resulting `reqwest::Client` makes. Used by providers that require
    /// out-of-band attribution headers on every call (today, OpenRouter's
    /// optional `HTTP-Referer` / `X-Title` leaderboard headers). Plain
    /// OpenAI / Ollama callers should keep using `new`.
    pub fn with_default_headers(
        base_url: String,
        api_key: Option<String>,
        default_headers: reqwest::header::HeaderMap,
    ) -> Self {
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(600))
            .default_headers(default_headers)
            .build()
            .expect("failed to build HTTP client");
        let base_url = base_url.trim_end_matches('/').to_string();
        Self {
            base_url,
            api_key,
            http,
        }
    }

    fn api_url(&self, path: &str) -> String {
        if self.base_url.ends_with("/v1") {
            format!("{}{}", self.base_url, path)
        } else {
            format!("{}/v1{}", self.base_url, path)
        }
    }
}

impl LlmBackend for OpenAiClient {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>> {
        Box::pin(self.list_models_impl())
    }

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        // Chat Completions doesn't take a reasoning_effort dimension on
        // non-reasoning models, and the reasoning-capable o1/o3 family
        // routes its own dedicated parameter -- not in scope here.
        _reasoning_effort: Option<&str>,
        on_token: Box<dyn FnMut(&str) + Send>,
        // No chain-of-thought stream on the Chat Completions SSE schema.
        _on_thought: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
        idle_timeout: Duration,
    ) -> BoxFuture<'_, Result<LlmResponse>> {
        let model = model.to_string();
        Box::pin(self.stream_chat_impl(model, messages, tools, on_token, cancel, idle_timeout))
    }
}

impl OpenAiClient {
    async fn list_models_impl(&self) -> Result<Vec<String>> {
        let url = self.api_url("/models");

        let mut req = self.http.get(&url);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req.send().await.context("failed to fetch models")?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            tracing::warn!("model discovery failed (HTTP {status}): {body}");
            anyhow::bail!("model discovery failed (HTTP {status})");
        }

        let models: ModelsResponse = resp
            .json()
            .await
            .context("failed to parse models response")?;
        Ok(models.data.into_iter().map(|m| m.id).collect())
    }

    async fn stream_chat_impl(
        &self,
        model: String,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        on_token: TokenSink,
        cancel: CancellationToken,
        idle_timeout: Duration,
    ) -> Result<LlmResponse> {
        let url = self.api_url("/chat/completions");

        let tool_choice = tools.as_ref().map(|_| "auto".to_string());

        let body = ChatCompletionRequest {
            model,
            messages,
            stream: true,
            temperature: None,
            max_tokens: None,
            tools,
            tool_choice,
        };

        let mut req = self.http.post(&url).json(&body);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req.send().await.context("failed to send chat request")?;
        let status = resp.status();
        if !status.is_success() {
            let body_text = resp.text().await.unwrap_or_default();
            anyhow::bail!("chat completion failed (HTTP {status}): {body_text}");
        }

        let stream = resp
            .bytes_stream()
            .map(|r| r.map(|b| b.to_vec()).map_err(anyhow::Error::from));

        drive_sse_stream(stream, on_token, cancel, idle_timeout).await
    }
}

/// Drive an SSE byte stream until the LLM emits `[DONE]`, the stream
/// ends, or the cancellation token fires. Aborts with a clear error if
/// no *meaningful progress* (parsed `data:` event contributing content
/// or tool-call deltas, or `[DONE]`) is observed within `idle`. SSE
/// keepalive comments (`:\n`), blank lines, and partial bytes that
/// don't advance the parser do NOT reset the deadline, so a server or
/// proxy that drip-feeds pings every <90s can no longer hold the
/// request open indefinitely.
async fn drive_sse_stream<S>(
    mut stream: S,
    mut on_token: TokenSink,
    cancel: CancellationToken,
    idle: Duration,
) -> Result<LlmResponse>
where
    S: Stream<Item = Result<Vec<u8>>> + Unpin,
{
    let mut full_text = String::new();
    let mut tool_acc = ToolCallAccumulator::default();
    let mut raw_buf: Vec<u8> = Vec::new();
    let mut deadline = tokio::time::Instant::now() + idle;

    loop {
        tokio::select! {
            _ = cancel.cancelled() => {
                tracing::info!("streaming cancelled by client");
                break;
            }
            chunk_or_timeout = tokio::time::timeout_at(deadline, stream.next()) => {
                let chunk_opt = match chunk_or_timeout {
                    Ok(opt) => opt,
                    Err(_elapsed) => anyhow::bail!(
                        "LLM stream made no meaningful progress for {}s; aborting (server-side hang or keepalive-only flood)",
                        idle.as_secs()
                    ),
                };
                let Some(chunk) = chunk_opt else { break; };
                let chunk = chunk.context("stream read error")?;
                raw_buf.extend_from_slice(&chunk);

                let mut made_progress = false;

                while let Some(pos) = raw_buf.iter().position(|&b| b == b'\n') {
                    let line_bytes = raw_buf.drain(..=pos).collect::<Vec<_>>();
                    let line = String::from_utf8_lossy(&line_bytes).trim().to_string();

                    if line.is_empty() || line.starts_with(':') {
                        continue;
                    }

                    let data = if let Some(stripped) = line.strip_prefix("data: ") {
                        stripped.trim()
                    } else {
                        continue;
                    };

                    if data == "[DONE]" {
                        if tool_acc.is_empty() {
                            return Ok(LlmResponse::Text(full_text));
                        }
                        return Ok(LlmResponse::ToolCalls {
                            text: full_text,
                            calls: tool_acc.into_tool_calls(),
                        });
                    }

                    match serde_json::from_str::<ChatCompletionChunk>(data) {
                        Ok(chunk) => {
                            for choice in &chunk.choices {
                                // Accumulate text content
                                if let Some(content) = &choice.delta.content {
                                    made_progress = true;
                                    on_token(content);
                                    full_text.push_str(content);
                                }
                                // Accumulate tool call fragments
                                if let Some(tc_chunks) = &choice.delta.tool_calls {
                                    if !tc_chunks.is_empty() {
                                        made_progress = true;
                                    }
                                    for tc in tc_chunks {
                                        tool_acc.push(tc);
                                    }
                                }
                            }
                        }
                        Err(e) => {
                            tracing::debug!("skipping unparseable SSE chunk: {e}");
                        }
                    }
                }

                if made_progress {
                    deadline = tokio::time::Instant::now() + idle;
                }
            }
        }
    }

    // If we exited the loop via cancellation, tool call fragments may be incomplete
    // (arguments JSON truncated mid-stream). Return only the text we've already
    // streamed to the caller to avoid dispatching malformed tool calls.
    if cancel.is_cancelled() {
        return Ok(LlmResponse::Text(full_text));
    }

    if tool_acc.is_empty() {
        Ok(LlmResponse::Text(full_text))
    } else {
        Ok(LlmResponse::ToolCalls {
            text: full_text,
            calls: tool_acc.into_tool_calls(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::stream;
    use std::sync::{Arc, Mutex};

    fn collect_tokens() -> (TokenSink, Arc<Mutex<Vec<String>>>) {
        let collected = Arc::new(Mutex::new(Vec::<String>::new()));
        let inner = collected.clone();
        let cb: TokenSink = Box::new(move |t| {
            inner.lock().unwrap().push(t.to_string());
        });
        (cb, collected)
    }

    /// A stream that emits only SSE keepalive comments (`:\n`) must trip
    /// the deadline -- otherwise a server can hold the request open
    /// forever by drip-feeding pings. Regression for the failure mode
    /// flagged in PR #3510 review.
    #[tokio::test(start_paused = true)]
    async fn drive_sse_stream_bails_on_keepalive_only_chunks() {
        // One keepalive, then permanently pending. Auto-advance under
        // `start_paused` will roll forward to the deadline since no task
        // is ready -- the keepalive line does NOT count as progress.
        let chunks: Vec<Result<Vec<u8>>> = vec![Ok(b":keepalive\n".to_vec())];
        let s = stream::iter(chunks).chain(stream::pending());

        let (on_token, _) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        let err = result.expect_err("keepalive-only stream should bail");
        let msg = err.to_string();
        assert!(
            msg.contains("no meaningful progress") && msg.contains("90"),
            "error should mention 'no meaningful progress' and the timeout, got: {msg}"
        );
    }

    /// A stream that emits content data resets the deadline. Mixed with
    /// many keepalives, the helper must still complete normally.
    #[tokio::test(start_paused = true)]
    async fn drive_sse_stream_resets_deadline_on_content_chunks() {
        let chunks: Vec<Result<Vec<u8>>> = vec![
            Ok(b":keepalive\n".to_vec()),
            Ok(b"data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n".to_vec()),
            Ok(b":keepalive\n".to_vec()),
            Ok(b"data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n".to_vec()),
            Ok(b"data: [DONE]\n".to_vec()),
        ];
        let s = stream::iter(chunks);

        let (on_token, collected) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete") {
            LlmResponse::Text(t) => assert_eq!(t, "hello"),
            other => panic!("expected text response, got {other:?}"),
        }
        assert_eq!(*collected.lock().unwrap(), vec!["hel", "lo"]);
    }

    /// `[DONE]` ends the stream cleanly with whatever has been accumulated.
    #[tokio::test]
    async fn drive_sse_stream_returns_text_on_done() {
        let chunks: Vec<Result<Vec<u8>>> = vec![
            Ok(b"data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n".to_vec()),
            Ok(b"data: [DONE]\n".to_vec()),
        ];
        let s = stream::iter(chunks);

        let (on_token, collected) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete") {
            LlmResponse::Text(t) => assert_eq!(t, "hi"),
            other => panic!("expected text response, got {other:?}"),
        }
        assert_eq!(*collected.lock().unwrap(), vec!["hi"]);
    }

    /// A pre-cancelled token routes through the cancel arm of `select!`
    /// and returns an empty `Text`, never `ToolCalls` (which would be
    /// malformed if the stream cut mid-arguments). Sanity check that the
    /// cancel arm exists and produces text.
    #[tokio::test]
    async fn drive_sse_stream_cancellation_returns_text() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        let s = stream::pending::<Result<Vec<u8>>>();

        let (on_token, _) = collect_tokens();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete via cancel") {
            LlmResponse::Text(t) => assert_eq!(t, ""),
            other => panic!("expected text response, got {other:?}"),
        }
    }

    /// `[DONE]` after tool-call deltas (no text) returns `ToolCalls`,
    /// preserving id/name and the concatenated arguments JSON. The SSE
    /// stream only delivers complete tool calls when `[DONE]` arrives;
    /// truncating mid-arguments would leave malformed JSON for the LLM.
    #[tokio::test]
    async fn drive_sse_stream_returns_tool_calls_on_done() {
        let chunks: Vec<Result<Vec<u8>>> = vec![
            Ok(
                b"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{\\\"pa\"}}]}}]}\n"
                    .to_vec(),
            ),
            Ok(
                b"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a.txt\\\"}\"}}]}}]}\n"
                    .to_vec(),
            ),
            Ok(b"data: [DONE]\n".to_vec()),
        ];
        let s = stream::iter(chunks);

        let (on_token, _) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete") {
            LlmResponse::ToolCalls { text, calls } => {
                assert!(text.is_empty());
                assert_eq!(calls.len(), 1);
                assert_eq!(calls[0].id, "call_1");
                assert_eq!(calls[0].function.name, "readFile");
                assert_eq!(calls[0].function.arguments, r#"{"path":"a.txt"}"#);
            }
            other => panic!("expected tool calls, got {other:?}"),
        }
    }

    /// Unparseable SSE chunks are skipped (logged at debug). They must
    /// not abort the stream or count as progress -- the deadline keeps
    /// ticking, but valid downstream chunks still parse normally.
    #[tokio::test]
    async fn drive_sse_stream_skips_unparseable_data_chunks() {
        let chunks: Vec<Result<Vec<u8>>> = vec![
            Ok(b"data: {not-json}\n".to_vec()),
            Ok(b"data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n".to_vec()),
            Ok(b"data: [DONE]\n".to_vec()),
        ];
        let s = stream::iter(chunks);

        let (on_token, collected) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete") {
            LlmResponse::Text(t) => assert_eq!(t, "ok"),
            other => panic!("expected text response, got {other:?}"),
        }
        assert_eq!(*collected.lock().unwrap(), vec!["ok"]);
    }

    /// Stream that ends without `[DONE]` returns whatever has been
    /// accumulated -- some upstream proxies don't forward the terminator.
    /// Tool-call accumulation flushes on stream end if no text arrived.
    #[tokio::test]
    async fn drive_sse_stream_returns_on_eof_without_done() {
        let chunks: Vec<Result<Vec<u8>>> = vec![Ok(
            b"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n".to_vec(),
        )];
        let s = stream::iter(chunks);

        let (on_token, _) = collect_tokens();
        let cancel = CancellationToken::new();
        let result = drive_sse_stream(s, on_token, cancel, Duration::from_secs(90)).await;

        match result.expect("should complete") {
            LlmResponse::Text(t) => assert_eq!(t, "partial"),
            other => panic!("expected text response, got {other:?}"),
        }
    }

    /// The base URL is normalized to drop a trailing slash, and `/v1` is
    /// appended only when not already present. This is what lets the
    /// CLI default `http://localhost:11434/v1` and a bare endpoint URL
    /// like `https://api.example.com` both work.
    #[test]
    fn api_url_appends_v1_when_missing() {
        let client = OpenAiClient::new("http://localhost:11434".into(), None);
        assert_eq!(
            client.api_url("/chat/completions"),
            "http://localhost:11434/v1/chat/completions"
        );
        assert_eq!(
            client.api_url("/models"),
            "http://localhost:11434/v1/models"
        );
    }

    #[test]
    fn api_url_does_not_double_v1() {
        let client = OpenAiClient::new("http://localhost:11434/v1".into(), None);
        assert_eq!(
            client.api_url("/chat/completions"),
            "http://localhost:11434/v1/chat/completions"
        );
    }

    /// Trailing slash is trimmed at construction, so the final URL never
    /// has a `//` between the base and the path.
    #[test]
    fn api_url_strips_trailing_slash() {
        let client = OpenAiClient::new("http://localhost:11434/".into(), None);
        assert_eq!(
            client.api_url("/models"),
            "http://localhost:11434/v1/models"
        );

        let client = OpenAiClient::new("http://localhost:11434/v1/".into(), None);
        assert_eq!(
            client.api_url("/models"),
            "http://localhost:11434/v1/models"
        );
    }

    /// HTTPS endpoints (typical for hosted OpenAI-compatible providers)
    /// receive the same normalization as plain HTTP.
    #[test]
    fn api_url_handles_https_endpoint() {
        let client = OpenAiClient::new("https://api.example.com".into(), None);
        assert_eq!(
            client.api_url("/chat/completions"),
            "https://api.example.com/v1/chat/completions"
        );
    }

    /// `Debug` for `OpenAiClient` must redact the api key so it does not
    /// leak into log output via `{:?}`.
    #[test]
    fn debug_redacts_api_key() {
        let client = OpenAiClient::new("http://x".into(), Some("sk-secret-123".into()));
        let dbg = format!("{client:?}");
        assert!(!dbg.contains("sk-secret-123"));
        assert!(dbg.contains("REDACTED"));
    }

    /// `ChatMessage` constructors set the role correctly and serialize
    /// only the fields they own. The `#[serde(skip_serializing_if = "Option::is_none")]`
    /// attributes are load-bearing for endpoints that reject extra keys.
    #[test]
    fn chat_message_constructors_round_trip_through_json() {
        let m = ChatMessage::system("you are helpful");
        let v: serde_json::Value = serde_json::to_value(&m).unwrap();
        assert_eq!(v["role"], "system");
        assert_eq!(v["content"], "you are helpful");
        assert!(v.get("tool_calls").is_none(), "system has no tool_calls");
        assert!(v.get("tool_call_id").is_none());

        let m = ChatMessage::user("hi");
        assert_eq!(serde_json::to_value(&m).unwrap()["role"], "user");

        let m = ChatMessage::assistant("ok");
        assert_eq!(serde_json::to_value(&m).unwrap()["role"], "assistant");

        let m = ChatMessage::tool_result("call_1", "readFile", "contents");
        let v: serde_json::Value = serde_json::to_value(&m).unwrap();
        assert_eq!(v["role"], "tool");
        assert_eq!(v["tool_call_id"], "call_1");
        assert_eq!(v["name"], "readFile");
        assert_eq!(v["content"], "contents");
    }

    /// `assistant_tool_calls` omits `content` entirely (not `null`),
    /// because some providers reject `content: null` alongside
    /// `tool_calls`.
    #[test]
    fn assistant_tool_calls_omits_content_field() {
        let calls = vec![ToolCall {
            id: "id_0".into(),
            r#type: "function".into(),
            function: FunctionCall {
                name: "readFile".into(),
                arguments: r#"{"path":"x"}"#.into(),
            },
        }];
        let m = ChatMessage::assistant_tool_calls(calls);
        let v: serde_json::Value = serde_json::to_value(&m).unwrap();
        assert_eq!(v["role"], "assistant");
        assert!(
            v.get("content").is_none(),
            "content key must be skipped when None, got: {v}"
        );
        assert!(v.get("tool_calls").is_some());
    }

    /// `ToolCallAccumulator` merges fragments by index. The OpenAI API
    /// streams tool-call arguments across many SSE chunks; we must
    /// concatenate them in order without duplicating the id or name.
    #[test]
    fn tool_call_accumulator_concatenates_fragments_per_index() {
        let mut acc = ToolCallAccumulator::default();
        // Index 0: id arrives first, then name, then arguments split into two.
        acc.push(&ChunkToolCall {
            index: 0,
            id: Some("call_0".into()),
            function: None,
        });
        acc.push(&ChunkToolCall {
            index: 0,
            id: None,
            function: Some(ChunkFunctionCall {
                name: Some("readFile".into()),
                arguments: Some(r#"{"pa"#.into()),
            }),
        });
        acc.push(&ChunkToolCall {
            index: 0,
            id: None,
            function: Some(ChunkFunctionCall {
                name: None,
                arguments: Some(r#"th":"x.txt"}"#.into()),
            }),
        });
        // Index 1 in parallel; sort_by_key in into_tool_calls puts it second.
        acc.push(&ChunkToolCall {
            index: 1,
            id: Some("call_1".into()),
            function: Some(ChunkFunctionCall {
                name: Some("writeFile".into()),
                arguments: Some(r#"{"path":"y.txt","content":""}"#.into()),
            }),
        });

        let calls = acc.into_tool_calls();
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].id, "call_0");
        assert_eq!(calls[0].function.name, "readFile");
        assert_eq!(calls[0].function.arguments, r#"{"path":"x.txt"}"#);
        assert_eq!(calls[1].id, "call_1");
        assert_eq!(calls[1].function.name, "writeFile");
    }

    /// `OpenAiClient::with_default_headers` produces an instance that
    /// `LlmBackend` can be cast over (no panic on construction, headers
    /// accepted as-is). Wire path is exercised by the integration with
    /// `MultiBackend` -- this test pins the constructor contract that
    /// OpenRouter's `HTTP-Referer` / `X-Title` attribution headers rely
    /// on (`main.rs::build_openrouter_backend`).
    #[test]
    fn with_default_headers_constructs_with_attribution_headers() {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::HeaderName::from_static("http-referer"),
            reqwest::header::HeaderValue::from_static("https://example.test"),
        );
        headers.insert(
            reqwest::header::HeaderName::from_static("x-title"),
            reqwest::header::HeaderValue::from_static("brokk-acp-rust"),
        );
        let client = OpenAiClient::with_default_headers(
            "https://openrouter.ai/api/v1".to_string(),
            Some("sk-test-key".to_string()),
            headers,
        );
        // Trailing slashes are stripped by both constructors so callers
        // can interchange `.../v1` and `.../v1/` without double-slashes
        // showing up in the request URL.
        let debug = format!("{client:?}");
        assert!(debug.contains("openrouter.ai"), "got {debug}");
        assert!(
            debug.contains("[REDACTED]"),
            "api_key must be redacted from Debug output: {debug}"
        );
    }

    /// OpenRouter's `/v1/models` response carries strictly more fields
    /// than OpenAI's (`name`, `canonical_slug`, `pricing`, `architecture`,
    /// etc.). The shared `ModelsResponse` deserializer must round-trip
    /// the catalog without choking on the extra fields, leaving the
    /// caller with just the bare `id` strings the routing layer expects.
    /// Sample shape distilled from a live `GET https://openrouter.ai/api/v1/models`
    /// response (vendor/model ids, with nested `pricing` and
    /// `architecture` objects) so a future serde-rename regression is
    /// caught here rather than at runtime on the user's first session.
    #[test]
    fn models_response_parses_openrouter_shape_ignoring_extra_fields() {
        let raw = r#"{
            "data": [
                {
                    "id": "anthropic/claude-3.5-sonnet",
                    "name": "Anthropic: Claude 3.5 Sonnet",
                    "canonical_slug": "anthropic/claude-3.5-sonnet",
                    "context_length": 200000,
                    "pricing": {"prompt": "0.000003", "completion": "0.000015"},
                    "architecture": {"input_modalities": ["text", "image"]},
                    "top_provider": {"context_length": 200000}
                },
                {
                    "id": "openai/gpt-4o",
                    "name": "OpenAI: GPT-4o",
                    "context_length": 128000,
                    "pricing": {"prompt": "0.0000025", "completion": "0.00001"}
                }
            ]
        }"#;
        let parsed: ModelsResponse = serde_json::from_str(raw).expect("OpenRouter /models parses");
        let ids: Vec<String> = parsed.data.into_iter().map(|m| m.id).collect();
        assert_eq!(
            ids,
            vec![
                "anthropic/claude-3.5-sonnet".to_string(),
                "openai/gpt-4o".to_string(),
            ]
        );
    }
}
