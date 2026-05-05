//! Parser and types for the public ACP agent registry hosted at
//! `https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`.
//!
//! Schema mirror: `https://cdn.agentclientprotocol.com/registry/v1/latest/agent.schema.json`.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

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
