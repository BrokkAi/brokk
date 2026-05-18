//! Routing `LlmBackend` that fans `list_models` out to all configured
//! sources and dispatches `stream_chat` to the right one based on the
//! `<source>::<id>` wire prefix produced by `discovery.rs`.
//!
//! Why a separate type? `OpenAiClient` and `CodexClient` already implement
//! `LlmBackend` for one transport each. Wrapping the three (Codex,
//! OpenRouter, Ollama) in a single routing backend lets `agent.rs` stay
//! oblivious to which model it's talking to -- it just hands the wire id
//! back to the backend the same way it always has, and the backend strips
//! the prefix and routes.
//!
//! Bare ids (no `<source>::` prefix) fall back to a configurable preferred
//! source so manually-typed model ids still route somewhere reasonable.
//! Without that fallback, a user typing `llama3:latest` directly into the
//! `/config` model picker would get a "no backend for model" error even
//! though the picker also offers `ollama::llama3:latest`.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use anyhow::Result;
use futures::future::BoxFuture;
use std::time::Duration;
use tokio_util::sync::CancellationToken;

use crate::discovery::{
    DiscoveredModel, ModelSource, OLLAMA_DEFAULT_URL, discover_all, discovery_http_client,
    split_wire_id,
};
use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ModelMetadata, ToolDefinition};

/// Dynamic Ollama endpoint stored on `MultiBackend`. Defaults to
/// `discovery::OLLAMA_DEFAULT_URL`; `/configure ollama.endpoint <url>`
/// swaps it at runtime, after which the next discovery refresh hits
/// the new URL and the rebuilt chat backend talks to it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OllamaConfig {
    pub url: String,
}

impl Default for OllamaConfig {
    fn default() -> Self {
        Self {
            url: OLLAMA_DEFAULT_URL.to_string(),
        }
    }
}

/// Per-process OpenRouter attribution headers. These pin the
/// openrouter.ai leaderboard identity; values are baked into the
/// `reqwest::Client` defaults at `OpenAiClient` construction, so any
/// change requires rebuilding the backend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenRouterAttribution {
    pub http_referer: String,
    pub x_title: String,
}

impl OpenRouterAttribution {
    /// Brokk's well-known attribution defaults. Kept here so the
    /// startup path and `/configure` clears converge on one source.
    pub const DEFAULT_HTTP_REFERER: &'static str = "https://github.com/BrokkAi/brokk";
    pub const DEFAULT_X_TITLE: &'static str = "brokk-acp-rust";
}

impl Default for OpenRouterAttribution {
    fn default() -> Self {
        Self {
            http_referer: Self::DEFAULT_HTTP_REFERER.to_string(),
            x_title: Self::DEFAULT_X_TITLE.to_string(),
        }
    }
}

/// LLM backend that routes by `<source>::<id>` prefix. Any inner backend
/// may be absent (e.g. no `auth.json`, no `OPENROUTER_API_KEY`, or no
/// Ollama on the default port); calls for a source whose backend isn't
/// configured return a clear error rather than silently falling through.
///
/// All three slots (Codex, OpenRouter, Ollama) are held behind `RwLock`s
/// so a successful `/codex-login`, `/openrouter-login`, or `/configure
/// ollama.endpoint` mid-session can install a backend without a server
/// restart. The lock is only ever held for the duration of a synchronous
/// `Option<Arc<...>>` clone -- we never hold it across an `.await`.
///
/// The `ollama_config` and `openrouter_attribution` cells hold the
/// dynamic values (Ollama URL, OpenRouter leaderboard attribution)
/// `/configure` mutates. They are read on every discovery pass / backend
/// rebuild, so a change takes effect on the next request.
pub struct MultiBackend {
    codex: RwLock<Option<Arc<dyn LlmBackend>>>,
    openrouter: RwLock<Option<Arc<dyn LlmBackend>>>,
    ollama: RwLock<Option<Arc<dyn LlmBackend>>>,
    ollama_config: RwLock<OllamaConfig>,
    openrouter_attribution: RwLock<OpenRouterAttribution>,
}

impl MultiBackend {
    /// Construct with default Ollama URL and OpenRouter attribution.
    /// `/configure ollama.endpoint` and `/configure openrouter.*` mutate
    /// the cells in place at runtime via the `set_*` helpers.
    pub fn new(
        codex: Option<Arc<dyn LlmBackend>>,
        openrouter: Option<Arc<dyn LlmBackend>>,
        ollama: Option<Arc<dyn LlmBackend>>,
    ) -> Self {
        Self {
            codex: RwLock::new(codex),
            openrouter: RwLock::new(openrouter),
            ollama: RwLock::new(ollama),
            ollama_config: RwLock::new(OllamaConfig::default()),
            openrouter_attribution: RwLock::new(OpenRouterAttribution::default()),
        }
    }

    /// Install (or replace) the Codex backend at runtime. Called from
    /// the `/codex-login` handler so the next discovery refresh and any
    /// subsequent `codex::*` route picks it up without a server restart.
    ///
    /// Replacing an existing backend is safe: any in-flight request
    /// holding a clone of the old `Arc<CodexClient>` finishes against
    /// that instance and then drops it. Note that the new backend
    /// starts with an empty `reqwest` cookie jar and `refresh_lock`, so
    /// the first request after replacement may have to re-acquire any
    /// Cloudflare cookies (`__cf_bm`, `cf_clearance`) the previous
    /// instance had already accumulated; this is a one-request cost.
    pub fn install_codex(&self, backend: Arc<dyn LlmBackend>) {
        // unwrap: the only way the lock gets poisoned is a panic while
        // holding it, and the only sites that hold it are tiny clones of
        // an Option<Arc> -- not panickable in practice.
        *self.codex.write().unwrap() = Some(backend);
    }

    /// Drop the currently-installed Codex backend, if any. Called from
    /// `/codex-login disconnect` after the on-disk credentials are
    /// wiped so a subsequent `codex::*` request fails with the same
    /// "backend not configured" error a fresh-no-auth.json startup
    /// would give, instead of firing requests with credentials that
    /// will now 401. In-flight requests holding an `Arc` to the old
    /// backend complete against that captured instance.
    pub fn uninstall_codex(&self) {
        *self.codex.write().unwrap() = None;
    }

    /// Install (or replace) the OpenRouter backend at runtime. Called
    /// from `/openrouter-login <key>` so a session that started without
    /// `OPENROUTER_API_KEY` or an on-disk credential file picks up the
    /// new key on the next discovery refresh.
    pub fn install_openrouter(&self, backend: Arc<dyn LlmBackend>) {
        *self.openrouter.write().unwrap() = Some(backend);
    }

    /// Drop the currently-installed OpenRouter backend, if any. Called
    /// from `/openrouter-login disconnect` after the on-disk credential
    /// file is wiped so a subsequent `openrouter::*` request fails with
    /// "backend not configured" instead of firing 401-bound requests.
    pub fn uninstall_openrouter(&self) {
        *self.openrouter.write().unwrap() = None;
    }

    /// Install (or replace) the Ollama backend at runtime. Called from
    /// `/configure ollama.endpoint <url>` after the new URL probe builds
    /// a fresh `OpenAiClient`. Mirrors the Codex/OpenRouter shape so an
    /// endpoint swap doesn't take the daemon away from in-flight chats.
    pub fn install_ollama(&self, backend: Arc<dyn LlmBackend>) {
        *self.ollama.write().unwrap() = Some(backend);
    }

    /// Snapshot the current Codex backend, if any. Cloning the inner Arc
    /// lets callers release the read lock immediately; they can then
    /// `.await` the backend without holding a guard.
    fn codex_snapshot(&self) -> Option<Arc<dyn LlmBackend>> {
        self.codex.read().unwrap().clone()
    }

    /// Snapshot the current OpenRouter backend, if any. Same shape as
    /// `codex_snapshot` -- callers release the read lock before awaiting.
    fn openrouter_snapshot(&self) -> Option<Arc<dyn LlmBackend>> {
        self.openrouter.read().unwrap().clone()
    }

    /// Snapshot the current Ollama backend, if any. Same lock-then-clone
    /// shape as the other two slots; the guard is dropped before any
    /// awaits.
    fn ollama_snapshot(&self) -> Option<Arc<dyn LlmBackend>> {
        self.ollama.read().unwrap().clone()
    }

    /// Snapshot the current Ollama config (URL). Used by `list_model_metadata`
    /// to point discovery at the configured endpoint, and by `/configure`
    /// dump paths.
    pub fn ollama_config_snapshot(&self) -> OllamaConfig {
        self.ollama_config.read().unwrap().clone()
    }

    /// Replace the Ollama config cell. Subsequent discovery and backend
    /// rebuilds read the new value.
    pub fn set_ollama_config(&self, config: OllamaConfig) {
        *self.ollama_config.write().unwrap() = config;
    }

    /// Snapshot the OpenRouter attribution. Used when rebuilding the
    /// OpenRouter backend so the new client carries the current headers.
    pub fn openrouter_attribution_snapshot(&self) -> OpenRouterAttribution {
        self.openrouter_attribution.read().unwrap().clone()
    }

    /// Replace the OpenRouter attribution cell. Note that an existing
    /// installed backend keeps the previous headers (they're baked into
    /// the client's defaults); callers must rebuild and reinstall to
    /// apply the change.
    pub fn set_openrouter_attribution(&self, attribution: OpenRouterAttribution) {
        *self.openrouter_attribution.write().unwrap() = attribution;
    }

    fn pick(&self, source: ModelSource) -> Option<Arc<dyn LlmBackend>> {
        match source {
            ModelSource::Codex => self.codex_snapshot(),
            ModelSource::OpenRouter => self.openrouter_snapshot(),
            ModelSource::Ollama => self.ollama_snapshot(),
        }
    }

    /// Source to use when a chat request arrives with no `<source>::` prefix.
    /// Computed on demand (rather than cached at construction) so a Codex
    /// login mid-session promotes Codex to the preferred fallback.
    ///
    /// Priority is Codex > OpenRouter > Ollama. Codex wins first because
    /// the more capable backend is more likely to be the user's intent
    /// for a bare model id like `gpt-5-codex`; OpenRouter sits ahead of
    /// Ollama because a configured cloud key is a stronger signal of
    /// intent than a daemon happening to be running locally.
    fn fallback_source(&self) -> Option<ModelSource> {
        let codex_present = self.codex.read().unwrap().is_some();
        if codex_present {
            return Some(ModelSource::Codex);
        }
        let openrouter_present = self.openrouter.read().unwrap().is_some();
        if openrouter_present {
            return Some(ModelSource::OpenRouter);
        }
        if self.ollama.read().unwrap().is_some() {
            return Some(ModelSource::Ollama);
        }
        None
    }

    /// Resolve a wire-form model id to (backend, bare id). Bare ids (no
    /// `<source>::` prefix) route to the fallback source.
    fn resolve(&self, wire_model: &str) -> Result<(Arc<dyn LlmBackend>, String)> {
        if let Some((source, bare)) = split_wire_id(wire_model) {
            let backend = self.pick(source).ok_or_else(|| {
                anyhow::anyhow!(
                    "model {wire_model} requires the {} backend, which is not configured",
                    source.as_str()
                )
            })?;
            return Ok((backend, bare.to_string()));
        }
        let source = self.fallback_source().ok_or_else(|| {
            anyhow::anyhow!(
                "no LLM backend is configured (none of Codex, OpenRouter, or Ollama \
                 discovered any models, and no `<source>::<id>` wire prefix was provided)"
            )
        })?;
        let backend = self
            .pick(source)
            .expect("fallback_source returns Some only when its backend exists");
        Ok((backend, wire_model.to_string()))
    }
}

impl LlmBackend for MultiBackend {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>> {
        // Thin adapter over `list_model_metadata` so the bare-id and
        // metadata paths can't drift -- e.g. forgetting to pick up a
        // freshly-installed Codex backend on only one of the two.
        Box::pin(async move {
            Ok(self
                .list_model_metadata()
                .await?
                .into_iter()
                .map(|m| m.id)
                .collect())
        })
    }

    fn list_model_metadata(&self) -> BoxFuture<'_, Result<Vec<ModelMetadata>>> {
        Box::pin(async move {
            // Snapshot the Codex backend once and pull its enriched
            // catalog (slugs + per-model reasoning presets). Holding
            // only an `Arc` clone here means a concurrent
            // `install_codex` doesn't perturb this discovery pass.
            let codex = self.codex_snapshot();
            let codex_metadata: Vec<ModelMetadata> = match &codex {
                Some(c) => c.list_model_metadata().await.unwrap_or_default(),
                None => Vec::new(),
            };
            // Index by bare slug so we can re-attach reasoning data
            // to the wire-prefixed records returned by discover_all.
            let codex_by_id: HashMap<String, ModelMetadata> = codex_metadata
                .iter()
                .map(|m| (m.id.clone(), m.clone()))
                .collect();
            // Hand discover_all the bare slugs we already have so we
            // don't hit `/models` twice during a single list call.
            let codex_ids: Vec<String> = codex_metadata.iter().map(|m| m.id.clone()).collect();
            let codex_lookup = || async move { Ok::<_, anyhow::Error>(codex_ids) };

            // OpenRouter exposes a flat `/v1/models` list with no
            // reasoning-effort metadata, so we just need bare ids. When
            // the backend isn't configured (no `OPENROUTER_API_KEY`),
            // the closure returns an empty list and `discover_all`
            // logs/skips it like any other absent source.
            let openrouter_backend = self.openrouter_snapshot();
            let openrouter_lookup = move || async move {
                match openrouter_backend {
                    Some(or) => or.list_models().await,
                    None => Ok(Vec::new()),
                }
            };

            let http = discovery_http_client();
            // Read the configured URL once per pass; in-flight requests
            // already hold their own backend snapshots so a concurrent
            // `set_ollama_config` doesn't perturb them.
            let ollama_url = self.ollama_config_snapshot().url;
            let discovered: Vec<DiscoveredModel> =
                discover_all(&http, &ollama_url, codex_lookup, openrouter_lookup).await;
            Ok(discovered
                .into_iter()
                .map(|m| {
                    let wire = m.wire_id();
                    match m.source {
                        ModelSource::Codex => codex_by_id
                            .get(&m.id)
                            .map(|meta| ModelMetadata {
                                id: wire.clone(),
                                default_reasoning_level: meta.default_reasoning_level.clone(),
                                supported_reasoning_levels: meta.supported_reasoning_levels.clone(),
                            })
                            .unwrap_or_else(|| ModelMetadata::id_only(wire)),
                        // Neither Ollama nor OpenRouter publishes
                        // reasoning presets through its catalog -- ids
                        // surface with empty metadata so the picker
                        // simply omits the effort selector for them.
                        ModelSource::Ollama | ModelSource::OpenRouter => {
                            ModelMetadata::id_only(wire)
                        }
                    }
                })
                .collect())
        })
    }

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
    ) -> BoxFuture<'_, Result<LlmResponse>> {
        let resolution = self.resolve(model);
        let reasoning_effort = reasoning_effort.map(str::to_string);
        Box::pin(async move {
            let (backend, bare) = resolution?;
            backend
                .stream_chat(
                    &bare,
                    messages,
                    tools,
                    reasoning_effort.as_deref(),
                    on_token,
                    on_thought,
                    cancel,
                    idle_timeout,
                )
                .await
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::future::FutureExt;
    use std::sync::Mutex;

    /// Test double that records the model id and reasoning effort it was
    /// called with. Lets us assert that `MultiBackend` strips the
    /// `<source>::` prefix before delegating, so the inner client
    /// receives the bare id Ollama or the Responses API actually expects,
    /// and that the per-session reasoning_effort threads all the way
    /// through the dispatcher unchanged.
    struct RecordingBackend {
        name: &'static str,
        last_model: Arc<Mutex<Option<String>>>,
        last_reasoning_effort: Arc<Mutex<Option<String>>>,
    }

    impl LlmBackend for RecordingBackend {
        fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>> {
            let name = self.name;
            async move { Ok(vec![format!("{name}-stub")]) }.boxed()
        }

        fn stream_chat(
            &self,
            model: &str,
            _messages: Vec<ChatMessage>,
            _tools: Option<Vec<ToolDefinition>>,
            reasoning_effort: Option<&str>,
            _on_token: Box<dyn FnMut(&str) + Send>,
            _on_thought: Box<dyn FnMut(&str) + Send>,
            _cancel: CancellationToken,
            _idle_timeout: Duration,
        ) -> BoxFuture<'_, Result<LlmResponse>> {
            *self.last_model.lock().unwrap() = Some(model.to_string());
            *self.last_reasoning_effort.lock().unwrap() = reasoning_effort.map(str::to_string);
            let response = LlmResponse::Text(format!("hello from {}", self.name));
            async move { Ok(response) }.boxed()
        }
    }

    /// Captured per-call state for assertions.
    struct RecordingHandles {
        last_model: Arc<Mutex<Option<String>>>,
        last_reasoning_effort: Arc<Mutex<Option<String>>>,
    }

    fn recording(name: &'static str) -> (Arc<dyn LlmBackend>, RecordingHandles) {
        let last_model = Arc::new(Mutex::new(None));
        let last_reasoning_effort = Arc::new(Mutex::new(None));
        let backend = Arc::new(RecordingBackend {
            name,
            last_model: last_model.clone(),
            last_reasoning_effort: last_reasoning_effort.clone(),
        });
        (
            backend,
            RecordingHandles {
                last_model,
                last_reasoning_effort,
            },
        )
    }

    /// Wire ids tagged `codex::` route to the Codex backend with the bare
    /// id, while `ollama::` ids route to Ollama. Each backend records the
    /// model string it received so we can assert the prefix was stripped.
    #[tokio::test]
    async fn stream_chat_routes_by_wire_prefix() {
        let (codex_backend, codex_handles) = recording("codex");
        let (ollama_backend, ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(Some(codex_backend), None, Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("codex route");
        assert_eq!(
            codex_handles.last_model.lock().unwrap().as_deref(),
            Some("gpt-5-codex")
        );
        assert!(ollama_handles.last_model.lock().unwrap().is_none());

        let _ = multi
            .stream_chat(
                "ollama::llama3:latest",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("ollama route");
        // The Ollama tag suffix must survive prefix stripping.
        assert_eq!(
            ollama_handles.last_model.lock().unwrap().as_deref(),
            Some("llama3:latest")
        );
    }

    /// A bare model id (no `<source>::` prefix) routes to the fallback
    /// source. With both backends configured, Codex wins -- it's the more
    /// capable choice and the more likely user intent for a bare id.
    #[tokio::test]
    async fn bare_id_routes_to_codex_fallback_when_both_configured() {
        let (codex_backend, codex_handles) = recording("codex");
        let (ollama_backend, ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(Some(codex_backend), None, Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("bare id falls back to codex");
        assert_eq!(
            codex_handles.last_model.lock().unwrap().as_deref(),
            Some("gpt-5-codex")
        );
        assert!(ollama_handles.last_model.lock().unwrap().is_none());
    }

    /// Bare id with only Ollama configured: falls through to Ollama
    /// rather than erroring. Lets users with no Codex login still type
    /// raw model ids into `/config`.
    #[tokio::test]
    async fn bare_id_routes_to_ollama_when_codex_absent() {
        let (ollama_backend, ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(None, None, Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "llama3",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("bare id falls back to ollama");
        assert_eq!(
            ollama_handles.last_model.lock().unwrap().as_deref(),
            Some("llama3")
        );
    }

    /// Wire id requesting an absent backend errors loudly instead of
    /// silently falling through to the other source -- if the user picked
    /// `codex::gpt-5` from the catalog and the Codex login expired, we
    /// must NOT route the request to Ollama under a different model name.
    #[tokio::test]
    async fn wire_id_for_absent_backend_returns_error() {
        // Only Ollama is configured; a `codex::` wire id must error.
        let (ollama_backend, _ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(None, None, Some(ollama_backend));

        let err = multi
            .stream_chat(
                "codex::gpt-5",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect_err("codex route must fail when codex backend is absent");
        let msg = format!("{err:#}");
        assert!(msg.contains("codex"), "error must mention codex: {msg}");
    }

    /// When neither backend is configured, every chat request errors
    /// rather than panics. `MultiBackend` is constructible empty (the
    /// server still starts -- the user can run `/codex-login` mid-session
    /// or start Ollama and re-discover) but no model can be routed.
    #[tokio::test]
    async fn empty_multi_backend_errors_on_chat() {
        let multi = MultiBackend::new(None, None, None);
        let err = multi
            .stream_chat(
                "anything",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect_err("no backend means no route");
        let msg = format!("{err:#}");
        assert!(
            msg.contains("no LLM backend is configured"),
            "error must explain the empty-backend case: {msg}"
        );
    }

    /// Regression for the `/codex-login` lifecycle (issue #3555): the
    /// server starts with no Codex backend (auth.json absent), the user
    /// runs `/codex-login`, and the new backend is installed at
    /// runtime. Subsequent `codex::*` routing must succeed -- previously
    /// it kept returning the "backend not configured" error because the
    /// `None` was captured permanently at construction.
    #[tokio::test]
    async fn codex_installed_after_login_is_routable() {
        // Start with no Codex (mirrors the empty-auth.json startup path).
        let multi = MultiBackend::new(None, None, None);

        // Pre-install: a `codex::*` request must fail loudly.
        let err = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect_err("codex route must fail before install");
        assert!(format!("{err:#}").contains("codex"));

        // User runs `/codex-login` successfully -- the handler installs
        // a freshly-built Codex backend.
        let (codex_backend, codex_handles) = recording("codex");
        multi.install_codex(codex_backend);

        // Now the same request routes through Codex with the prefix
        // stripped, exactly as if the credentials had been there at
        // startup.
        let _ = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("codex route must succeed after install");
        assert_eq!(
            codex_handles.last_model.lock().unwrap().as_deref(),
            Some("gpt-5-codex")
        );
    }

    /// Bare ids must also start routing to Codex once it's installed.
    /// Before the fix, `fallback_source` was frozen at construction --
    /// so even after `install_codex` a bare `gpt-5-codex` would error
    /// out with "no LLM backend is configured" because the cached
    /// fallback was still `None`.
    #[tokio::test]
    async fn bare_id_falls_back_to_codex_after_install() {
        let multi = MultiBackend::new(None, None, None);

        let (codex_backend, codex_handles) = recording("codex");
        multi.install_codex(codex_backend);

        let _ = multi
            .stream_chat(
                "gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("bare id must route to newly-installed codex");
        assert_eq!(
            codex_handles.last_model.lock().unwrap().as_deref(),
            Some("gpt-5-codex")
        );
    }

    /// `list_models` must consult the currently-installed Codex backend,
    /// not the one captured at construction. Without this, a successful
    /// `/codex-login` followed by a discovery refresh (e.g. on
    /// `session/new`) would keep returning an empty Codex list and the
    /// model picker would never show Codex models.
    #[tokio::test]
    async fn list_models_reflects_installed_codex() {
        let multi = MultiBackend::new(None, None, None);
        let (codex_backend, _codex_handles) = recording("codex");
        multi.install_codex(codex_backend);

        // RecordingBackend::list_models returns ["codex-stub"].
        // `discover_all` delegates Codex discovery entirely to the
        // closure (the only Codex source it has -- there is no separate
        // native probe), so the "codex-stub" id surfacing through it
        // proves the freshly-installed backend was consulted by the
        // refresh path rather than the empty `None` captured at
        // construction.
        let models = multi.list_models().await.expect("discovery must succeed");
        assert!(
            models.iter().any(|m| m.contains("codex-stub")),
            "installed codex backend must contribute to discovery: got {models:?}"
        );
    }

    /// `/codex-login disconnect` calls `uninstall_codex` after wiping
    /// auth.json. Subsequent `codex::*` routing must fail with the same
    /// "backend not configured" error a fresh-no-auth.json startup
    /// gives -- otherwise a wire id picked from a stale `availableModels`
    /// list would fire a request against credentials that no longer
    /// exist on disk.
    #[tokio::test]
    async fn codex_uninstall_unroutes_codex_requests() {
        let multi = MultiBackend::new(None, None, None);
        let (codex_backend, _codex_handles) = recording("codex");
        multi.install_codex(codex_backend);

        // Sanity check: routable while installed.
        let _ = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("codex route must succeed while installed");

        // Disconnect path drops the backend.
        multi.uninstall_codex();

        let err = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect_err("codex route must fail after uninstall");
        assert!(
            format!("{err:#}").contains("codex"),
            "error must mention codex backend"
        );
    }

    /// Wire ids tagged `openrouter::` route to the OpenRouter backend
    /// with the bare id (slash-separated `vendor/model`), and do NOT
    /// leak to Codex or Ollama when those are also configured.
    #[tokio::test]
    async fn openrouter_wire_id_routes_to_openrouter() {
        let (codex_backend, codex_handles) = recording("codex");
        let (openrouter_backend, openrouter_handles) = recording("openrouter");
        let (ollama_backend, ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(
            Some(codex_backend),
            Some(openrouter_backend),
            Some(ollama_backend),
        );

        let _ = multi
            .stream_chat(
                "openrouter::anthropic/claude-3.5-sonnet",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("openrouter route");
        // The inner slash in `vendor/model` must survive prefix
        // stripping; OpenRouter expects the slashed id verbatim.
        assert_eq!(
            openrouter_handles.last_model.lock().unwrap().as_deref(),
            Some("anthropic/claude-3.5-sonnet")
        );
        assert!(codex_handles.last_model.lock().unwrap().is_none());
        assert!(ollama_handles.last_model.lock().unwrap().is_none());
    }

    /// A bare id with only OpenRouter configured falls back to
    /// OpenRouter rather than erroring -- the same fallback contract
    /// Ollama gets when it's the only backend.
    #[tokio::test]
    async fn bare_id_routes_to_openrouter_when_only_openrouter_configured() {
        let (openrouter_backend, openrouter_handles) = recording("openrouter");
        let multi = MultiBackend::new(None, Some(openrouter_backend), None);

        let _ = multi
            .stream_chat(
                "anthropic/claude-3.5-sonnet",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("bare id falls back to openrouter");
        assert_eq!(
            openrouter_handles.last_model.lock().unwrap().as_deref(),
            Some("anthropic/claude-3.5-sonnet")
        );
    }

    /// Fallback priority: Codex > OpenRouter > Ollama. With all three
    /// configured, a bare id routes to Codex (most capable, most likely
    /// intent). With Codex absent, OpenRouter wins over Ollama because a
    /// configured cloud key is a stronger signal of intent than a
    /// happens-to-be-running local daemon.
    #[tokio::test]
    async fn bare_id_prefers_openrouter_over_ollama_when_codex_absent() {
        let (openrouter_backend, openrouter_handles) = recording("openrouter");
        let (ollama_backend, ollama_handles) = recording("ollama");
        let multi = MultiBackend::new(None, Some(openrouter_backend), Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "some-bare-id",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("bare id falls back to openrouter");
        assert_eq!(
            openrouter_handles.last_model.lock().unwrap().as_deref(),
            Some("some-bare-id")
        );
        assert!(ollama_handles.last_model.lock().unwrap().is_none());
    }

    /// Wire id requesting an absent OpenRouter backend errors loudly
    /// rather than silently routing to a different source. Same contract
    /// as `wire_id_for_absent_backend_returns_error` for Codex -- if the
    /// user picks `openrouter::vendor/model` from a catalog snapshot and
    /// the key has since been unexported, we must NOT route the request
    /// to Codex or Ollama under a different (and probably nonexistent)
    /// model id.
    #[tokio::test]
    async fn openrouter_wire_id_for_absent_backend_returns_error() {
        let (codex_backend, _codex_handles) = recording("codex");
        let multi = MultiBackend::new(Some(codex_backend), None, None);

        let err = multi
            .stream_chat(
                "openrouter::anthropic/claude-3.5-sonnet",
                vec![],
                None,
                None,
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect_err("openrouter route must fail when openrouter backend is absent");
        let msg = format!("{err:#}");
        assert!(
            msg.contains("openrouter"),
            "error must mention openrouter: {msg}"
        );
    }

    /// `reasoning_effort` threads through the dispatcher unchanged --
    /// it must arrive at the resolved inner backend, not get swallowed
    /// or coerced. (This protects the Codex picker's per-session
    /// selection from being silently lost on its way through
    /// `MultiBackend`.)
    #[tokio::test]
    async fn stream_chat_forwards_reasoning_effort() {
        let (codex_backend, codex_handles) = recording("codex");
        let multi = MultiBackend::new(Some(codex_backend), None, None);

        let _ = multi
            .stream_chat(
                "codex::gpt-5.2",
                vec![],
                None,
                Some("xhigh"),
                Box::new(|_| {}),
                Box::new(|_| {}),
                CancellationToken::new(),
                Duration::from_secs(60),
            )
            .await
            .expect("codex route");
        assert_eq!(
            codex_handles
                .last_reasoning_effort
                .lock()
                .unwrap()
                .as_deref(),
            Some("xhigh"),
            "reasoning_effort must arrive at the inner backend unchanged"
        );
    }
}
