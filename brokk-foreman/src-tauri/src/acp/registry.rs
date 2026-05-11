//! Parser and types for the public ACP agent registry hosted at
//! `https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`.
//!
//! Schema mirror: `https://cdn.agentclientprotocol.com/registry/v1/latest/agent.schema.json`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::fs;

pub const REGISTRY_URL: &str =
    "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json";

pub const SUPPORTED_FORMAT_VERSION: &str = "1.0.0";

/// Top-level registry document shape.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Registry {
    pub version: String,
    pub agents: Vec<RegistryAgent>,
}

/// A single agent entry in the registry.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RegistryAgent {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repository: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub website: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub authors: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub license: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    pub distribution: Distribution,
}

/// All distribution channels declared for an agent. The schema requires
/// at least one of these to be present.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Distribution {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binary: Option<HashMap<Platform, BinaryTarget>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub npx: Option<PackageDistribution>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uvx: Option<PackageDistribution>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BinaryTarget {
    pub archive: String,
    pub cmd: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub args: Vec<String>,
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub env: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PackageDistribution {
    pub package: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub args: Vec<String>,
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub env: HashMap<String, String>,
}

/// Platform identifiers as defined by the registry schema.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Platform {
    #[serde(rename = "darwin-aarch64")]
    DarwinAarch64,
    #[serde(rename = "darwin-x86_64")]
    DarwinX86_64,
    #[serde(rename = "linux-aarch64")]
    LinuxAarch64,
    #[serde(rename = "linux-x86_64")]
    LinuxX86_64,
    #[serde(rename = "windows-aarch64")]
    WindowsAarch64,
    #[serde(rename = "windows-x86_64")]
    WindowsX86_64,
}

impl Platform {
    /// Resolve the platform of the running host. Returns `None` if the
    /// current OS/arch combination is not one of the supported registry targets.
    pub fn current() -> Option<Self> {
        match (std::env::consts::OS, std::env::consts::ARCH) {
            ("macos", "aarch64") => Some(Self::DarwinAarch64),
            ("macos", "x86_64") => Some(Self::DarwinX86_64),
            ("linux", "aarch64") => Some(Self::LinuxAarch64),
            ("linux", "x86_64") => Some(Self::LinuxX86_64),
            ("windows", "aarch64") => Some(Self::WindowsAarch64),
            ("windows", "x86_64") => Some(Self::WindowsX86_64),
            _ => None,
        }
    }
}

#[derive(Debug, Error)]
pub enum RegistryError {
    #[error("unsupported registry format version {0:?}, expected {SUPPORTED_FORMAT_VERSION}")]
    UnsupportedVersion(String),
    #[error(
        "agent {0:?} declared no distribution channel; the registry schema requires at least one"
    )]
    EmptyDistribution(String),
    #[error("failed to deserialize registry JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("registry HTTP error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("registry IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("registry CDN returned {0}")]
    Status(reqwest::StatusCode),
    #[error("registry cache is empty and the CDN is unreachable")]
    NoCacheAndOffline,
}

impl Registry {
    /// Parse a registry document from JSON, validating the wrapper version and
    /// the per-agent distribution invariant.
    pub fn parse(json: &str) -> Result<Self, RegistryError> {
        let registry: Registry = serde_json::from_str(json)?;
        if registry.version != SUPPORTED_FORMAT_VERSION {
            return Err(RegistryError::UnsupportedVersion(registry.version));
        }
        for agent in &registry.agents {
            if agent.distribution.is_empty() {
                return Err(RegistryError::EmptyDistribution(agent.id.clone()));
            }
        }
        Ok(registry)
    }
}

impl Distribution {
    pub fn is_empty(&self) -> bool {
        let binary_empty = self.binary.as_ref().is_none_or(HashMap::is_empty);
        binary_empty && self.npx.is_none() && self.uvx.is_none()
    }
}

/// Filesystem-backed registry cache. Stores the raw JSON body alongside the
/// last ETag the CDN gave us so subsequent fetches can revalidate cheaply
/// (HTTP 304 keeps `cdn.agentclientprotocol.com` traffic down for users
/// who open the agents page repeatedly).
pub struct RegistryCache {
    dir: PathBuf,
}

impl RegistryCache {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    fn json_path(&self) -> PathBuf {
        self.dir.join("registry-cache.json")
    }

    fn etag_path(&self) -> PathBuf {
        self.dir.join("registry-cache.etag")
    }

    async fn read_cached_etag(&self) -> Option<String> {
        fs::read_to_string(self.etag_path()).await.ok()
    }

    async fn read_cached_body(&self) -> Option<String> {
        fs::read_to_string(self.json_path()).await.ok()
    }

    async fn write_cache(&self, body: &str, etag: Option<&str>) -> std::io::Result<()> {
        fs::create_dir_all(&self.dir).await?;
        fs::write(self.json_path(), body).await?;
        if let Some(tag) = etag {
            fs::write(self.etag_path(), tag).await?;
        }
        Ok(())
    }
}

/// Fetch the registry from the CDN, honoring the cached `ETag` for 304s.
///
/// `force_refresh = true` bypasses the conditional `If-None-Match` header so
/// the UI's "Refresh" button always re-downloads. On any network error the
/// cached body is used as a fallback when present.
pub async fn fetch(cache: &RegistryCache, force_refresh: bool) -> Result<Registry, RegistryError> {
    let client = reqwest::Client::builder()
        .user_agent("brokk-foreman/0.1")
        .build()?;
    let mut req = client.get(REGISTRY_URL);
    let cached_etag = cache.read_cached_etag().await;
    if !force_refresh && let Some(tag) = cached_etag.as_deref() {
        req = req.header(reqwest::header::IF_NONE_MATCH, tag);
    }

    let resp = match req.send().await {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!("registry fetch failed: {e}; falling back to cache");
            return load_from_cache(cache).await;
        }
    };

    if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
        tracing::debug!("registry CDN returned 304; using cached body");
        return load_from_cache(cache).await;
    }
    if !resp.status().is_success() {
        return Err(RegistryError::Status(resp.status()));
    }

    let new_etag = resp
        .headers()
        .get(reqwest::header::ETAG)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    let body = resp.text().await?;
    let registry = Registry::parse(&body)?;
    cache.write_cache(&body, new_etag.as_deref()).await?;
    Ok(registry)
}

async fn load_from_cache(cache: &RegistryCache) -> Result<Registry, RegistryError> {
    match cache.read_cached_body().await {
        Some(body) => Registry::parse(&body),
        None => Err(RegistryError::NoCacheAndOffline),
    }
}

/// Path helpers exposed for callers that need the cache directory (e.g.
/// install flow places extracted archives under a sibling directory).
pub fn registry_cache_dir(data_dir: &Path) -> PathBuf {
    data_dir.to_path_buf()
}

impl RegistryAgent {
    /// Returns `true` if at least one distribution channel of this agent can
    /// be installed on the running host. Used to filter the registry browser
    /// to "things you could actually install today".
    pub fn supported_on_host(&self) -> bool {
        self.supported_on(Platform::current())
    }

    /// Returns `true` if at least one distribution channel of this agent can
    /// be installed on `host`. Tests inject the host so coverage doesn't
    /// silently collapse on runners whose target isn't in the registry.
    pub fn supported_on(&self, host: Option<Platform>) -> bool {
        if self.distribution.npx.is_some() || self.distribution.uvx.is_some() {
            return true;
        }
        match (host, &self.distribution.binary) {
            (Some(p), Some(targets)) => targets.contains_key(&p),
            _ => false,
        }
    }
}
