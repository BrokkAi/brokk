//! Auto-discover available LLM models from Codex (`~/.codex/auth.json`) and
//! a local Ollama daemon (`http://localhost:11434/api/tags` by default).
//!
//! Each discovered model carries a `ModelSource` tag so the routing backend
//! (`MultiBackend`) can pick the right HTTP client at request time. The
//! catalog is presented to ACP clients as `<source>::<id>` wire ids, e.g.
//! `codex::gpt-5-codex` and `ollama::llama3:latest`. The double-colon
//! separator avoids collision with Ollama tags (which themselves contain a
//! single colon, `model:tag`).
//!
//! Failure posture: missing or unreachable sources are logged and skipped,
//! never propagated. A user with neither Codex nor Ollama still gets a
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
}

impl ModelSource {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Codex => "codex",
            Self::Ollama => "ollama",
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
        _ => return None,
    };
    Some((source, rest))
}

/// Configuration for one discovery pass.
#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    /// Ollama base URL (without `/api` or `/v1` suffix). `None` disables
    /// Ollama discovery entirely.
    pub ollama_url: Option<String>,
    /// Discover Codex models if `~/.codex/auth.json` exists. Set to `false`
    /// to skip the auth.json read regardless of file presence.
    pub codex_enabled: bool,
}

impl Default for DiscoveryConfig {
    fn default() -> Self {
        Self {
            ollama_url: Some("http://localhost:11434".to_string()),
            codex_enabled: true,
        }
    }
}

// ---------------------------------------------------------------------------
// Ollama discovery (native /api/tags)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct OllamaTagsResponse {
    #[serde(default)]
    models: Vec<OllamaTagEntry>,
}

#[derive(Debug, Deserialize)]
struct OllamaTagEntry {
    name: String,
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

/// Query Ollama's native `/api/tags` endpoint. Native API, not the
/// OpenAI-compatible `/v1/models` -- `/api/tags` is the canonical list of
/// downloaded models and includes tag suffixes (`llama3:latest`) that
/// `/v1/models` strips.
pub async fn discover_ollama(
    http: &reqwest::Client,
    base_url: &str,
) -> Result<Vec<DiscoveredModel>> {
    let base = base_url.trim_end_matches('/');
    let url = format!("{base}/api/tags");
    let resp = http
        .get(&url)
        .send()
        .await
        .with_context(|| format!("GET {url}"))?;
    let status = resp.status();
    if !status.is_success() {
        anyhow::bail!("ollama /api/tags returned HTTP {status}");
    }
    let parsed: OllamaTagsResponse = resp.json().await.context("parsing /api/tags JSON")?;
    Ok(parsed
        .models
        .into_iter()
        .map(|m| DiscoveredModel {
            id: m.name,
            source: ModelSource::Ollama,
        })
        .collect())
}

// ---------------------------------------------------------------------------
// Top-level orchestrator
// ---------------------------------------------------------------------------

/// Run all enabled discovery sources concurrently and merge results.
/// Failures are logged and treated as "no models from this source" so a
/// dead Ollama daemon doesn't shadow a working Codex login (or vice versa).
///
/// Codex discovery is delegated to a caller-provided closure because the
/// `CodexClient` it relies on lives in a sibling module that already
/// handles auth.json parsing, fallback slugs, and `chatgpt`/`apikey` mode
/// branching. Keeping the closure here lets `discovery.rs` stay agnostic
/// of those concerns while still running both sources in parallel.
pub async fn discover_all<F, Fut>(
    config: &DiscoveryConfig,
    http: &reqwest::Client,
    codex_lookup: F,
) -> Vec<DiscoveredModel>
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<Vec<String>>>,
{
    let codex_fut = async {
        if !config.codex_enabled {
            return Vec::new();
        }
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

    let ollama_fut = async {
        let Some(base) = config.ollama_url.as_deref() else {
            return Vec::new();
        };
        match discover_ollama(http, base).await {
            Ok(models) => models,
            Err(e) => {
                tracing::info!("ollama model discovery skipped at {base}: {e:#}");
                Vec::new()
            }
        }
    };

    let (codex, ollama) = tokio::join!(codex_fut, ollama_fut);
    let mut all = Vec::with_capacity(codex.len() + ollama.len());
    all.extend(codex);
    all.extend(ollama);
    all
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `wire_id` round-trips through `split_wire_id` for both sources, and
    /// preserves Ollama tag suffixes (the inner colon in `llama3:latest`).
    #[test]
    fn wire_id_round_trips_for_both_sources() {
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
    }

    /// Wire ids without the `::` separator or with an unknown source are
    /// rejected so callers can treat them as already-bare and route to a
    /// default backend rather than silently mis-tagging them.
    #[test]
    fn split_wire_id_rejects_bare_or_unknown_prefix() {
        assert!(split_wire_id("gpt-5-codex").is_none());
        assert!(split_wire_id("llama3:latest").is_none());
        assert!(split_wire_id("unknown::foo").is_none());
        // Single colon is not enough -- `llama3:latest` is a bare Ollama id,
        // not a wire id, so we must NOT treat it as `source=llama3`.
        assert!(split_wire_id("llama3:latest").is_none());
    }

    /// `discover_all` returns Codex first, then Ollama, regardless of which
    /// future resolves first. Stable ordering matters because the first
    /// model in the catalog is auto-selected as the session default; we
    /// don't want users to see a different default just because their
    /// Ollama daemon was slow to respond on a particular boot.
    #[tokio::test]
    async fn discover_all_orders_codex_before_ollama() {
        let config = DiscoveryConfig {
            // Pointing at a closed port so ollama discovery returns Err
            // quickly via `connect_timeout`. We only care about ordering
            // of the merged vec, not the success path.
            ollama_url: Some("http://127.0.0.1:1".into()),
            codex_enabled: true,
        };
        let http = discovery_http_client();
        let models = discover_all(&config, &http, || async {
            Ok(vec!["gpt-5-codex".to_string(), "gpt-4o".to_string()])
        })
        .await;
        // Codex returned 2; Ollama failed -> 0.
        assert_eq!(models.len(), 2);
        assert!(models.iter().all(|m| m.source == ModelSource::Codex));
        assert_eq!(models[0].id, "gpt-5-codex");
    }

    /// When both sources fail, the merged vec is empty rather than an
    /// error -- the server still starts and the user can re-run discovery
    /// later via session/new.
    #[tokio::test]
    async fn discover_all_returns_empty_when_both_sources_fail() {
        let config = DiscoveryConfig {
            ollama_url: Some("http://127.0.0.1:1".into()),
            codex_enabled: true,
        };
        let http = discovery_http_client();
        let models = discover_all(&config, &http, || async { anyhow::bail!("no auth.json") }).await;
        assert!(models.is_empty());
    }

    /// Disabling a source via config short-circuits its lookup. Verified
    /// by passing a codex_lookup that would panic if called.
    #[tokio::test]
    async fn discover_all_skips_disabled_codex() {
        let config = DiscoveryConfig {
            ollama_url: None,
            codex_enabled: false,
        };
        let http = discovery_http_client();
        let models = discover_all(&config, &http, || async {
            panic!("codex_lookup must not be called when codex_enabled=false")
        })
        .await;
        assert!(models.is_empty());
    }
}
