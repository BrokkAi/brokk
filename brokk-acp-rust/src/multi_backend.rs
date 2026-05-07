//! Routing `LlmBackend` that fans `list_models` out to all configured
//! sources and dispatches `stream_chat` to the right one based on the
//! `<source>::<id>` wire prefix produced by `discovery.rs`.
//!
//! Why a separate type? `OpenAiClient` and `CodexClient` already implement
//! `LlmBackend` for one transport each. Wrapping both in a third backend
//! lets `agent.rs` stay oblivious to which model it's talking to -- it
//! just hands the wire id back to the backend the same way it always has,
//! and the backend strips the prefix and routes.
//!
//! Bare ids (no `<source>::` prefix) fall back to a configurable preferred
//! source so manually-typed model ids still route somewhere reasonable.
//! Without that fallback, a user typing `llama3:latest` directly into the
//! `/config` model picker would get a "no backend for model" error even
//! though the picker also offers `ollama::llama3:latest`.

use std::sync::{Arc, RwLock};

use anyhow::Result;
use futures::future::BoxFuture;
use tokio_util::sync::CancellationToken;

use crate::discovery::{
    DiscoveredModel, ModelSource, OLLAMA_DEFAULT_URL, discover_all, discovery_http_client,
    split_wire_id,
};
use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};

/// LLM backend that routes by `<source>::<id>` prefix. Either inner
/// backend may be absent (e.g. no `auth.json`, or no Ollama on the
/// default port); calls for a source whose backend isn't configured
/// return a clear error rather than silently falling through.
///
/// The Codex slot is held behind a `RwLock` so a successful
/// `/codex-login` mid-session can install a backend without a server
/// restart. The lock is only ever held for the duration of a synchronous
/// `Option<Arc<...>>` clone -- we never hold it across an `.await`.
pub struct MultiBackend {
    codex: RwLock<Option<Arc<dyn LlmBackend>>>,
    ollama: Option<Arc<dyn LlmBackend>>,
}

impl MultiBackend {
    pub fn new(codex: Option<Arc<dyn LlmBackend>>, ollama: Option<Arc<dyn LlmBackend>>) -> Self {
        Self {
            codex: RwLock::new(codex),
            ollama,
        }
    }

    /// Install (or replace) the Codex backend at runtime. Called from
    /// the `/codex-login` handler so the next discovery refresh and any
    /// subsequent `codex::*` route picks it up without a server restart.
    pub fn install_codex(&self, backend: Arc<dyn LlmBackend>) {
        // unwrap: the only way the lock gets poisoned is a panic while
        // holding it, and the only sites that hold it are tiny clones of
        // an Option<Arc> -- not panickable in practice.
        *self.codex.write().unwrap() = Some(backend);
    }

    /// Snapshot the current Codex backend, if any. Cloning the inner Arc
    /// lets callers release the read lock immediately; they can then
    /// `.await` the backend without holding a guard.
    fn codex_snapshot(&self) -> Option<Arc<dyn LlmBackend>> {
        self.codex.read().unwrap().clone()
    }

    fn pick(&self, source: ModelSource) -> Option<Arc<dyn LlmBackend>> {
        match source {
            ModelSource::Codex => self.codex_snapshot(),
            ModelSource::Ollama => self.ollama.clone(),
        }
    }

    /// Source to use when a chat request arrives with no `<source>::` prefix.
    /// Computed on demand (rather than cached at construction) so a Codex
    /// login mid-session promotes Codex to the preferred fallback.
    fn fallback_source(&self) -> Option<ModelSource> {
        let codex_present = self.codex.read().unwrap().is_some();
        match (codex_present, self.ollama.is_some()) {
            // Prefer Codex when both are configured -- it's the more
            // capable backend and more likely to be the user's intent
            // for a bare model id like "gpt-5-codex".
            (true, _) => Some(ModelSource::Codex),
            (false, true) => Some(ModelSource::Ollama),
            (false, false) => None,
        }
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
                "no LLM backend is configured (neither Codex nor Ollama discovered \
                 any models, and no `<source>::<id>` wire prefix was provided)"
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
        Box::pin(async move {
            let http = discovery_http_client();
            // Codex lookup is delegated to the configured backend's own
            // `list_models` so we keep auth-mode handling, fallback slugs,
            // and Cloudflare cookie state where they already live.
            // Snapshotting under the read lock and dropping the guard
            // before the discovery future means a concurrent
            // `install_codex` call still observes a consistent state.
            let codex = self.codex_snapshot();
            let codex_lookup = || async move {
                match codex {
                    Some(c) => c.list_models().await,
                    None => Ok(Vec::new()),
                }
            };
            let discovered: Vec<DiscoveredModel> =
                discover_all(&http, OLLAMA_DEFAULT_URL, codex_lookup).await;
            Ok(discovered.into_iter().map(|m| m.wire_id()).collect())
        })
    }

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<LlmResponse>> {
        let resolution = self.resolve(model);
        Box::pin(async move {
            let (backend, bare) = resolution?;
            backend
                .stream_chat(&bare, messages, tools, on_token, cancel)
                .await
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::future::FutureExt;
    use std::sync::Mutex;

    /// Test double that records the model id it was called with. Lets us
    /// assert that `MultiBackend` strips the `<source>::` prefix before
    /// delegating, so the inner client receives the bare id Ollama or
    /// the Responses API actually expects.
    struct RecordingBackend {
        name: &'static str,
        last_model: Arc<Mutex<Option<String>>>,
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
            _on_token: Box<dyn FnMut(&str) + Send>,
            _cancel: CancellationToken,
        ) -> BoxFuture<'_, Result<LlmResponse>> {
            *self.last_model.lock().unwrap() = Some(model.to_string());
            let response = LlmResponse::Text(format!("hello from {}", self.name));
            async move { Ok(response) }.boxed()
        }
    }

    fn recording(name: &'static str) -> (Arc<dyn LlmBackend>, Arc<Mutex<Option<String>>>) {
        let last = Arc::new(Mutex::new(None));
        let backend = Arc::new(RecordingBackend {
            name,
            last_model: last.clone(),
        });
        (backend, last)
    }

    /// Wire ids tagged `codex::` route to the Codex backend with the bare
    /// id, while `ollama::` ids route to Ollama. Each backend records the
    /// model string it received so we can assert the prefix was stripped.
    #[tokio::test]
    async fn stream_chat_routes_by_wire_prefix() {
        let (codex_backend, codex_last) = recording("codex");
        let (ollama_backend, ollama_last) = recording("ollama");
        let multi = MultiBackend::new(Some(codex_backend), Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("codex route");
        assert_eq!(codex_last.lock().unwrap().as_deref(), Some("gpt-5-codex"));
        assert!(ollama_last.lock().unwrap().is_none());

        let _ = multi
            .stream_chat(
                "ollama::llama3:latest",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("ollama route");
        // The Ollama tag suffix must survive prefix stripping.
        assert_eq!(
            ollama_last.lock().unwrap().as_deref(),
            Some("llama3:latest")
        );
    }

    /// A bare model id (no `<source>::` prefix) routes to the fallback
    /// source. With both backends configured, Codex wins -- it's the more
    /// capable choice and the more likely user intent for a bare id.
    #[tokio::test]
    async fn bare_id_routes_to_codex_fallback_when_both_configured() {
        let (codex_backend, codex_last) = recording("codex");
        let (ollama_backend, ollama_last) = recording("ollama");
        let multi = MultiBackend::new(Some(codex_backend), Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "gpt-5-codex",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("bare id falls back to codex");
        assert_eq!(codex_last.lock().unwrap().as_deref(), Some("gpt-5-codex"));
        assert!(ollama_last.lock().unwrap().is_none());
    }

    /// Bare id with only Ollama configured: falls through to Ollama
    /// rather than erroring. Lets users with no Codex login still type
    /// raw model ids into `/config`.
    #[tokio::test]
    async fn bare_id_routes_to_ollama_when_codex_absent() {
        let (ollama_backend, ollama_last) = recording("ollama");
        let multi = MultiBackend::new(None, Some(ollama_backend));

        let _ = multi
            .stream_chat(
                "llama3",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("bare id falls back to ollama");
        assert_eq!(ollama_last.lock().unwrap().as_deref(), Some("llama3"));
    }

    /// Wire id requesting an absent backend errors loudly instead of
    /// silently falling through to the other source -- if the user picked
    /// `codex::gpt-5` from the catalog and the Codex login expired, we
    /// must NOT route the request to Ollama under a different model name.
    #[tokio::test]
    async fn wire_id_for_absent_backend_returns_error() {
        // Only Ollama is configured; a `codex::` wire id must error.
        let (ollama_backend, _ollama_last) = recording("ollama");
        let multi = MultiBackend::new(None, Some(ollama_backend));

        let err = multi
            .stream_chat(
                "codex::gpt-5",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
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
        let multi = MultiBackend::new(None, None);
        let err = multi
            .stream_chat(
                "anything",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
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
        let multi = MultiBackend::new(None, None);

        // Pre-install: a `codex::*` request must fail loudly.
        let err = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect_err("codex route must fail before install");
        assert!(format!("{err:#}").contains("codex"));

        // User runs `/codex-login` successfully -- the handler installs
        // a freshly-built Codex backend.
        let (codex_backend, codex_last) = recording("codex");
        multi.install_codex(codex_backend);

        // Now the same request routes through Codex with the prefix
        // stripped, exactly as if the credentials had been there at
        // startup.
        let _ = multi
            .stream_chat(
                "codex::gpt-5-codex",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("codex route must succeed after install");
        assert_eq!(codex_last.lock().unwrap().as_deref(), Some("gpt-5-codex"));
    }

    /// Bare ids must also start routing to Codex once it's installed.
    /// Before the fix, `fallback_source` was frozen at construction --
    /// so even after `install_codex` a bare `gpt-5-codex` would error
    /// out with "no LLM backend is configured" because the cached
    /// fallback was still `None`.
    #[tokio::test]
    async fn bare_id_falls_back_to_codex_after_install() {
        let multi = MultiBackend::new(None, None);

        let (codex_backend, codex_last) = recording("codex");
        multi.install_codex(codex_backend);

        let _ = multi
            .stream_chat(
                "gpt-5-codex",
                vec![],
                None,
                Box::new(|_| {}),
                CancellationToken::new(),
            )
            .await
            .expect("bare id must route to newly-installed codex");
        assert_eq!(codex_last.lock().unwrap().as_deref(), Some("gpt-5-codex"));
    }

    /// `list_models` must consult the currently-installed Codex backend,
    /// not the one captured at construction. Without this, a successful
    /// `/codex-login` followed by a discovery refresh (e.g. on
    /// `session/new`) would keep returning an empty Codex list and the
    /// model picker would never show Codex models.
    #[tokio::test]
    async fn list_models_reflects_installed_codex() {
        let multi = MultiBackend::new(None, None);
        let (codex_backend, _codex_last) = recording("codex");
        multi.install_codex(codex_backend);

        // RecordingBackend::list_models returns ["codex-stub"]. We can't
        // exercise the live Cloudflare-fronted /codex/models path here
        // -- the discovery wrapper falls back to the closure's output
        // when its native probe fails or returns nothing, so the
        // "codex-stub" id surfacing through `discover_all` is enough to
        // prove the freshly-installed backend was consulted.
        let models = multi.list_models().await.expect("discovery must succeed");
        assert!(
            models.iter().any(|m| m.contains("codex-stub")),
            "installed codex backend must contribute to discovery: got {models:?}"
        );
    }
}
