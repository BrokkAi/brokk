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

use std::sync::Arc;

use anyhow::Result;
use futures::future::BoxFuture;
use tokio_util::sync::CancellationToken;

use crate::discovery::{
    DiscoveredModel, DiscoveryConfig, ModelSource, discover_all, discovery_http_client,
    split_wire_id,
};
use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};

/// LLM backend that routes by `<source>::<id>` prefix. Either inner
/// backend may be absent (e.g. no `auth.json`, or `--no-ollama`); calls
/// for a source whose backend isn't configured return a clear error
/// rather than silently falling through.
pub struct MultiBackend {
    codex: Option<Arc<dyn LlmBackend>>,
    ollama: Option<Arc<dyn LlmBackend>>,
    /// Discovery config used by `list_models`. Held by value because each
    /// `list_models` call rebuilds the catalog (the ACP agent caches the
    /// result for re-use across `session/new` until the next discovery).
    discovery_config: DiscoveryConfig,
    /// Source to use when a chat request arrives with no `<source>::` prefix.
    /// Picked at construction so `stream_chat` is purely synchronous w.r.t.
    /// configuration.
    fallback_source: Option<ModelSource>,
}

impl MultiBackend {
    pub fn new(
        codex: Option<Arc<dyn LlmBackend>>,
        ollama: Option<Arc<dyn LlmBackend>>,
        discovery_config: DiscoveryConfig,
    ) -> Self {
        // Prefer Codex when both are configured -- it's the more capable
        // backend and more likely to be the user's intent for a bare model
        // id like "gpt-5-codex". Falls back to Ollama if Codex is absent.
        let fallback_source = match (codex.is_some(), ollama.is_some()) {
            (true, _) => Some(ModelSource::Codex),
            (false, true) => Some(ModelSource::Ollama),
            (false, false) => None,
        };
        Self {
            codex,
            ollama,
            discovery_config,
            fallback_source,
        }
    }

    fn pick(&self, source: ModelSource) -> Option<&Arc<dyn LlmBackend>> {
        match source {
            ModelSource::Codex => self.codex.as_ref(),
            ModelSource::Ollama => self.ollama.as_ref(),
        }
    }

    /// Resolve a wire-form model id to (backend, bare id). Bare ids (no
    /// `<source>::` prefix) route to the fallback source.
    fn resolve(&self, wire_model: &str) -> Result<(Arc<dyn LlmBackend>, String)> {
        if let Some((source, bare)) = split_wire_id(wire_model) {
            let backend = self
                .pick(source)
                .ok_or_else(|| {
                    anyhow::anyhow!(
                        "model {wire_model} requires the {} backend, which is not configured",
                        source.as_str()
                    )
                })?
                .clone();
            return Ok((backend, bare.to_string()));
        }
        let source = self.fallback_source.ok_or_else(|| {
            anyhow::anyhow!(
                "no LLM backend is configured (neither Codex nor Ollama discovered \
                 any models, and no `<source>::<id>` wire prefix was provided)"
            )
        })?;
        let backend = self
            .pick(source)
            .expect("fallback_source is set only when its backend exists")
            .clone();
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
            let codex = self.codex.clone();
            let codex_lookup = || async move {
                match codex {
                    Some(c) => c.list_models().await,
                    None => Ok(Vec::new()),
                }
            };
            let discovered: Vec<DiscoveredModel> =
                discover_all(&self.discovery_config, &http, codex_lookup).await;
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
        let multi = MultiBackend::new(
            Some(codex_backend),
            Some(ollama_backend),
            DiscoveryConfig::default(),
        );

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
        let multi = MultiBackend::new(
            Some(codex_backend),
            Some(ollama_backend),
            DiscoveryConfig::default(),
        );

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
        let multi = MultiBackend::new(None, Some(ollama_backend), DiscoveryConfig::default());

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
        let multi = MultiBackend::new(None, Some(ollama_backend), DiscoveryConfig::default());

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
        let multi = MultiBackend::new(None, None, DiscoveryConfig::default());
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
}
