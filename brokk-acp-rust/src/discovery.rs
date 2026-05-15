//! Auto-discover available LLM models from Codex (`~/.codex/auth.json`),
//! a local Ollama daemon (`http://localhost:11434/v1/models`), and
//! OpenRouter (`https://openrouter.ai/api/v1/models`, gated on the
//! `OPENROUTER_API_KEY` env var).
//!
//! Zero-config by design: the Ollama URL is fixed at the daemon's default
//! port. If your daemon listens elsewhere, the catalog will simply not
//! include Ollama models -- run `ollama serve` on `:11434` to make them
//! discoverable. OpenRouter is enabled only when `OPENROUTER_API_KEY` is
//! set in the environment; absent the key, the catalog simply omits its
//! models with no warning.
//!
//! Each discovered model carries a `ModelSource` tag so the routing backend
//! (`MultiBackend`) can pick the right HTTP client at request time. The
//! catalog is presented to ACP clients as `<source>::<id>` wire ids, e.g.
//! `codex::gpt-5-codex`, `ollama::llama3:latest`, and
//! `openrouter::anthropic/claude-3.5-sonnet`. The double-colon separator
//! avoids collision with Ollama tags (`model:tag`) and with OpenRouter
//! ids (`vendor/model`).
//!
//! Failure posture: missing or unreachable sources are logged and skipped,
//! never propagated. A user with none of the three configured still gets a
//! working server -- they just see an empty model picker until one of the
//! sources comes online (and can re-run discovery via `session/new`, which
//! refreshes the cache).

use std::time::Duration;

use anyhow::{Context, Result};
use serde::Deserialize;

/// Where a discovered model came from. The variant is encoded into the
/// wire id so the routing backend doesn't need a per-request map lookup.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ModelSource {
    Codex,
    Ollama,
    OpenRouter,
}

impl ModelSource {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Codex => "codex",
            Self::Ollama => "ollama",
            Self::OpenRouter => "openrouter",
        }
    }
}

#[derive(Debug, Clone)]
pub struct DiscoveredModel {
    /// Bare model id as the upstream returns it (e.g. `gpt-5-codex`,
    /// `llama3:latest`). Pass this verbatim to the source's chat endpoint.
    pub id: String,
    pub source: ModelSource,
}

impl DiscoveredModel {
    /// Wire form: `<source>::<id>`. Round-trips through the ACP model picker.
    pub fn wire_id(&self) -> String {
        format!("{}::{}", self.source.as_str(), self.id)
    }
}

/// Parse a wire-form model id back into `(source, bare_id)`. Returns `None`
/// if the input is missing the `<source>::` prefix or the prefix is unknown,
/// in which case callers should treat the input as already-bare and pick a
/// default backend.
pub fn split_wire_id(wire: &str) -> Option<(ModelSource, &str)> {
    let (prefix, rest) = wire.split_once("::")?;
    let source = match prefix {
        "codex" => ModelSource::Codex,
        "ollama" => ModelSource::Ollama,
        "openrouter" => ModelSource::OpenRouter,
        _ => return None,
    };
    Some((source, rest))
}

/// Default Ollama base URL. Hardcoded by design: the whole point of the
/// zero-config posture is that the user doesn't pick a port -- if the
/// daemon isn't here, it's not discoverable.
pub const OLLAMA_DEFAULT_URL: &str = "http://localhost:11434";

/// OpenRouter cloud base URL. Hardcoded; OpenRouter is a single SaaS
/// endpoint with no self-hosted variant to point at. `OpenAiClient` will
/// append `/v1/...` when it sees this base URL (already covered by the
/// existing `api_url` logic).
pub const OPENROUTER_BASE_URL: &str = "https://openrouter.ai/api/v1";

/// Environment variable name carrying the OpenRouter API key. Mirrors the
/// `OPENROUTER_API_KEY` convention used by OpenRouter's own SDK docs --
/// brokk-acp-rust deliberately does NOT introduce a `BROKK_OPENROUTER_*`
/// alias so the same shell that already works with `openrouter` / OpenAI
/// SDK / litellm works here too.
pub const OPENROUTER_API_KEY_ENV: &str = "OPENROUTER_API_KEY";

// ---------------------------------------------------------------------------
// Ollama discovery (OpenAI-compatible /v1/models)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct OpenAiModelsResponse {
    #[serde(default)]
    data: Vec<OpenAiModelEntry>,
}

#[derive(Debug, Deserialize)]
struct OpenAiModelEntry {
    id: String,
}

/// Build a short-timeout HTTP client tuned for discovery. Ollama is local;
/// if it's not running we want to fail fast, not block startup for 30s.
pub fn discovery_http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(2))
        .timeout(Duration::from_secs(5))
        .build()
        .expect("failed to build discovery HTTP client")
}

/// Query Ollama's OpenAI-compatible `/v1/models` endpoint. Recent Ollama
/// versions preserve tag suffixes (`llama3:latest`) in the OpenAI shim,
/// so we use it directly instead of the native `/api/tags`. This also
/// keeps the parsing identical to any other OpenAI-compatible local
/// server a user might run on the same port.
pub async fn discover_ollama(
    http: &reqwest::Client,
    base_url: &str,
) -> Result<Vec<DiscoveredModel>> {
    let base = base_url.trim_end_matches('/');
    let url = format!("{base}/v1/models");
    let resp = http
        .get(&url)
        .send()
        .await
        .with_context(|| format!("GET {url}"))?;
    let status = resp.status();
    if !status.is_success() {
        anyhow::bail!("ollama /v1/models returned HTTP {status}");
    }
    let parsed: OpenAiModelsResponse = resp.json().await.context("parsing /v1/models JSON")?;
    Ok(parsed
        .data
        .into_iter()
        .map(|m| DiscoveredModel {
            id: m.id,
            source: ModelSource::Ollama,
        })
        .collect())
}

// ---------------------------------------------------------------------------
// Top-level orchestrator
// ---------------------------------------------------------------------------

/// Run all configured discovery sources concurrently and merge results.
/// Failures are logged and treated as "no models from this source" so a
/// dead Ollama daemon doesn't shadow a working Codex login (or vice versa).
///
/// `ollama_url` is a parameter purely for test injection -- production
/// always passes `OLLAMA_DEFAULT_URL`. There's no CLI flag for this; the
/// zero-config posture means the user doesn't pick a port.
///
/// Codex and OpenRouter discovery are delegated to caller-provided
/// closures. For Codex, the `CodexClient` lives in a sibling module that
/// already handles auth.json parsing, fallback slugs, and
/// `chatgpt`/`apikey` mode branching. For OpenRouter, the closure lets
/// the caller short-circuit cleanly when no `OPENROUTER_API_KEY` is set
/// (returning `Err`) without `discovery.rs` having to know about env
/// vars. Each closure keeps `discovery.rs` agnostic of those concerns
/// while still running all sources in parallel.
///
/// Output ordering is fixed: Codex first, then OpenRouter, then Ollama.
/// The first model in the merged list is auto-selected as the session
/// default elsewhere -- keeping ordering deterministic stops users from
/// seeing a different default just because one source happened to be
/// slow on a given boot.
pub async fn discover_all<FC, FutC, FOR, FutOR>(
    http: &reqwest::Client,
    ollama_url: &str,
    codex_lookup: FC,
    openrouter_lookup: FOR,
) -> Vec<DiscoveredModel>
where
    FC: FnOnce() -> FutC,
    FutC: std::future::Future<Output = Result<Vec<String>>>,
    FOR: FnOnce() -> FutOR,
    FutOR: std::future::Future<Output = Result<Vec<String>>>,
{
    let codex_fut = async {
        match codex_lookup().await {
            Ok(ids) => ids
                .into_iter()
                .map(|id| DiscoveredModel {
                    id,
                    source: ModelSource::Codex,
                })
                .collect(),
            Err(e) => {
                tracing::info!("codex model discovery skipped: {e:#}");
                Vec::new()
            }
        }
    };

    let openrouter_fut = async {
        match openrouter_lookup().await {
            Ok(ids) => ids
                .into_iter()
                .map(|id| DiscoveredModel {
                    id,
                    source: ModelSource::OpenRouter,
                })
                .collect(),
            Err(e) => {
                tracing::info!("openrouter model discovery skipped: {e:#}");
                Vec::new()
            }
        }
    };

    let ollama_fut = async {
        match discover_ollama(http, ollama_url).await {
            Ok(models) => models,
            Err(e) => {
                tracing::info!("ollama model discovery skipped at {ollama_url}: {e:#}");
                Vec::new()
            }
        }
    };

    let (codex, openrouter, ollama) = tokio::join!(codex_fut, openrouter_fut, ollama_fut);
    let mut all = Vec::with_capacity(codex.len() + openrouter.len() + ollama.len());
    all.extend(codex);
    all.extend(openrouter);
    all.extend(ollama);
    all
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `wire_id` round-trips through `split_wire_id` for every source,
    /// preserves Ollama tag suffixes (the inner colon in `llama3:latest`),
    /// and preserves OpenRouter vendor-prefixed ids (the inner slash in
    /// `anthropic/claude-3.5-sonnet`).
    #[test]
    fn wire_id_round_trips_for_every_source() {
        let codex = DiscoveredModel {
            id: "gpt-5-codex".into(),
            source: ModelSource::Codex,
        };
        let codex_wire = codex.wire_id();
        assert_eq!(codex_wire, "codex::gpt-5-codex");
        let (src, id) = split_wire_id(&codex_wire).expect("must parse");
        assert_eq!(src, ModelSource::Codex);
        assert_eq!(id, "gpt-5-codex");

        let ollama = DiscoveredModel {
            id: "llama3:latest".into(),
            source: ModelSource::Ollama,
        };
        let ollama_wire = ollama.wire_id();
        assert_eq!(ollama_wire, "ollama::llama3:latest");
        let (src, id) = split_wire_id(&ollama_wire).expect("must parse");
        assert_eq!(src, ModelSource::Ollama);
        // The inner `:` survives -- our separator is the double-colon, so
        // the bare id keeps its tag suffix and routes correctly to Ollama.
        assert_eq!(id, "llama3:latest");

        let openrouter = DiscoveredModel {
            id: "anthropic/claude-3.5-sonnet".into(),
            source: ModelSource::OpenRouter,
        };
        let openrouter_wire = openrouter.wire_id();
        assert_eq!(openrouter_wire, "openrouter::anthropic/claude-3.5-sonnet");
        let (src, id) = split_wire_id(&openrouter_wire).expect("must parse");
        assert_eq!(src, ModelSource::OpenRouter);
        // The inner `/` survives -- OpenRouter's `vendor/model` format
        // round-trips through the wire id without escaping.
        assert_eq!(id, "anthropic/claude-3.5-sonnet");
    }

    /// Wire ids without the `::` separator or with an unknown source are
    /// rejected so callers can treat them as already-bare and route to a
    /// default backend rather than silently mis-tagging them.
    #[test]
    fn split_wire_id_rejects_bare_or_unknown_prefix() {
        assert!(split_wire_id("gpt-5-codex").is_none());
        assert!(split_wire_id("llama3:latest").is_none());
        assert!(split_wire_id("anthropic/claude-3.5-sonnet").is_none());
        assert!(split_wire_id("unknown::foo").is_none());
        // Single colon is not enough -- `llama3:latest` is a bare Ollama id,
        // not a wire id, so we must NOT treat it as `source=llama3`.
        assert!(split_wire_id("llama3:latest").is_none());
    }

    /// Closed-port URL used by tests to force `discover_ollama` to fail
    /// fast via `connect_timeout`, regardless of whether the test host
    /// happens to have a real Ollama running on the default port.
    const TEST_DEAD_OLLAMA_URL: &str = "http://127.0.0.1:1";

    /// `discover_all` returns Codex first, then OpenRouter, then Ollama,
    /// regardless of which future resolves first. Stable ordering matters
    /// because the first model in the catalog is auto-selected as the
    /// session default; we don't want users to see a different default
    /// just because their Ollama daemon was slow to respond on a
    /// particular boot.
    #[tokio::test]
    async fn discover_all_orders_codex_openrouter_ollama() {
        let http = discovery_http_client();
        let models = discover_all(
            &http,
            TEST_DEAD_OLLAMA_URL,
            || async { Ok(vec!["gpt-5-codex".to_string(), "gpt-4o".to_string()]) },
            || async {
                Ok(vec![
                    "anthropic/claude-3.5-sonnet".to_string(),
                    "openai/gpt-4o".to_string(),
                ])
            },
        )
        .await;
        // Codex returned 2; OpenRouter returned 2; Ollama failed -> 0.
        assert_eq!(models.len(), 4);
        assert_eq!(models[0].source, ModelSource::Codex);
        assert_eq!(models[0].id, "gpt-5-codex");
        assert_eq!(models[1].source, ModelSource::Codex);
        assert_eq!(models[2].source, ModelSource::OpenRouter);
        assert_eq!(models[2].id, "anthropic/claude-3.5-sonnet");
        assert_eq!(models[3].source, ModelSource::OpenRouter);
    }

    /// When every source fails, the merged vec is empty rather than an
    /// error -- the server still starts and the user can run /codex-login,
    /// start Ollama, or export `OPENROUTER_API_KEY`, then re-run discovery
    /// via the next session/new.
    #[tokio::test]
    async fn discover_all_returns_empty_when_every_source_fails() {
        let http = discovery_http_client();
        let models = discover_all(
            &http,
            TEST_DEAD_OLLAMA_URL,
            || async { anyhow::bail!("no auth.json") },
            || async { anyhow::bail!("no OPENROUTER_API_KEY") },
        )
        .await;
        assert!(models.is_empty());
    }

    /// OpenRouter discovery is treated independently from Codex: a Codex
    /// failure (no auth.json) must not suppress OpenRouter results, and
    /// vice versa. Regression for the "missing source shadows working
    /// source" failure mode the orchestrator is specifically designed to
    /// prevent.
    #[tokio::test]
    async fn discover_all_keeps_openrouter_when_codex_fails() {
        let http = discovery_http_client();
        let models = discover_all(
            &http,
            TEST_DEAD_OLLAMA_URL,
            || async { anyhow::bail!("no auth.json") },
            || async { Ok(vec!["anthropic/claude-3.5-sonnet".to_string()]) },
        )
        .await;
        assert_eq!(models.len(), 1);
        assert_eq!(models[0].source, ModelSource::OpenRouter);
        assert_eq!(models[0].id, "anthropic/claude-3.5-sonnet");
    }
}
