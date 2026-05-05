//! Parser tests for the public ACP agent registry. The pinned fixture is a
//! verbatim snapshot of `https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`
//! taken at the time this scaffolding landed; refreshing it is intentional
//! and signals we should re-verify the schema mirror in src/acp/registry.rs.

use std::collections::HashMap;

use brokk_foreman_lib::acp::registry::{
    BinaryTarget, Distribution, Platform, Registry, RegistryAgent, RegistryError,
    SUPPORTED_FORMAT_VERSION,
};

const FIXTURE: &str = include_str!("fixtures/registry.json");

#[test]
fn parses_pinned_snapshot() {
    let registry = Registry::parse(FIXTURE).expect("pinned registry must parse");
    assert_eq!(registry.version, SUPPORTED_FORMAT_VERSION);
    assert!(
        !registry.agents.is_empty(),
        "registry snapshot was empty, fixture is corrupted"
    );
}

#[test]
fn every_agent_has_at_least_one_distribution_channel() {
    let registry = Registry::parse(FIXTURE).unwrap();
    for agent in &registry.agents {
        assert!(
            !agent.distribution.is_empty(),
            "agent {:?} declared no distribution channel",
            agent.id
        );
    }
}

#[test]
fn fixture_exercises_all_three_distribution_kinds() {
    let registry = Registry::parse(FIXTURE).unwrap();
    let mut saw_binary = false;
    let mut saw_npx = false;
    let mut saw_uvx = false;
    for agent in &registry.agents {
        saw_binary |= agent.distribution.binary.is_some();
        saw_npx |= agent.distribution.npx.is_some();
        saw_uvx |= agent.distribution.uvx.is_some();
    }
    assert!(saw_binary, "fixture must include at least one binary dist");
    assert!(saw_npx, "fixture must include at least one npx dist");
    assert!(saw_uvx, "fixture must include at least one uvx dist");
}

#[test]
fn rejects_wrong_format_version() {
    let synthetic = r#"{"version":"2.0.0","agents":[]}"#;
    match Registry::parse(synthetic) {
        Err(RegistryError::UnsupportedVersion(v)) => assert_eq!(v, "2.0.0"),
        other => panic!("expected UnsupportedVersion, got {other:?}"),
    }
}

#[test]
fn rejects_agent_with_empty_distribution() {
    let synthetic = r#"{
        "version": "1.0.0",
        "agents": [{
            "id": "broken",
            "name": "Broken",
            "version": "1.0.0",
            "description": "no channels",
            "distribution": {}
        }]
    }"#;
    match Registry::parse(synthetic) {
        Err(RegistryError::EmptyDistribution(id)) => assert_eq!(id, "broken"),
        other => panic!("expected EmptyDistribution, got {other:?}"),
    }
}

#[test]
fn npx_only_agent_is_supported_on_any_host() {
    let registry = Registry::parse(FIXTURE).unwrap();
    let claude = registry
        .agents
        .iter()
        .find(|a| a.id == "claude-acp")
        .expect("claude-acp should be in the registry");
    assert!(claude.distribution.npx.is_some());
    assert!(
        claude.supported_on_host(),
        "claude-acp is npx-only, should be installable on every host"
    );
}

/// Build a binary-only agent that publishes exactly `published`. The args/env
/// payloads are intentionally trivial; the test only cares about platform keys.
fn binary_only_agent(published: &[Platform]) -> RegistryAgent {
    let mut targets = HashMap::new();
    for p in published {
        targets.insert(
            *p,
            BinaryTarget {
                archive: "https://example.invalid/agent.tar.gz".to_string(),
                cmd: "agent".to_string(),
                args: vec![],
                env: HashMap::new(),
            },
        );
    }
    RegistryAgent {
        id: "synthetic-binary-only".to_string(),
        name: "Synthetic".to_string(),
        version: "0.0.0".to_string(),
        description: "binary-only test fixture".to_string(),
        repository: None,
        website: None,
        authors: vec![],
        license: None,
        icon: None,
        distribution: Distribution {
            binary: Some(targets),
            npx: None,
            uvx: None,
        },
    }
}

#[test]
fn binary_only_agent_supported_when_host_matches_published_target() {
    let agent = binary_only_agent(&[Platform::LinuxX86_64, Platform::DarwinAarch64]);
    assert!(agent.supported_on(Some(Platform::LinuxX86_64)));
    assert!(agent.supported_on(Some(Platform::DarwinAarch64)));
}

#[test]
fn binary_only_agent_unsupported_when_host_does_not_match() {
    let agent = binary_only_agent(&[Platform::LinuxX86_64]);
    assert!(!agent.supported_on(Some(Platform::WindowsX86_64)));
    assert!(!agent.supported_on(Some(Platform::DarwinAarch64)));
}

#[test]
fn binary_only_agent_unsupported_when_host_unknown() {
    let agent = binary_only_agent(&[Platform::LinuxX86_64, Platform::DarwinAarch64]);
    assert!(!agent.supported_on(None));
}

#[test]
fn supported_on_host_matches_supported_on_current_platform() {
    let registry = Registry::parse(FIXTURE).unwrap();
    let binary_only = registry
        .agents
        .iter()
        .find(|a| {
            a.distribution.binary.is_some()
                && a.distribution.npx.is_none()
                && a.distribution.uvx.is_none()
        })
        .expect("fixture must include at least one binary-only agent");
    assert_eq!(
        binary_only.supported_on_host(),
        binary_only.supported_on(Platform::current()),
        "the convenience wrapper must agree with the parametrized form"
    );
}

#[test]
fn round_trips_through_serde() {
    let registry = Registry::parse(FIXTURE).unwrap();
    let json = serde_json::to_string(&registry).unwrap();
    let again = Registry::parse(&json).expect("re-parse after round-trip must succeed");
    assert_eq!(registry.agents.len(), again.agents.len());
    for (a, b) in registry.agents.iter().zip(again.agents.iter()) {
        assert_eq!(a.id, b.id);
        assert_eq!(
            a.distribution.is_empty(),
            b.distribution.is_empty(),
            "distribution shape must round-trip for {}",
            a.id
        );
    }
}

#[test]
fn helper_distribution_is_empty_matches_serde_emptiness() {
    let synthetic_empty: Distribution = serde_json::from_str("{}").unwrap();
    assert!(synthetic_empty.is_empty());

    let synthetic_npx: Distribution = serde_json::from_str(r#"{"npx":{"package":"foo"}}"#).unwrap();
    assert!(!synthetic_npx.is_empty());

    // Regression: `"binary": {}` parses as `Some(empty HashMap)`, which used to
    // slip past `is_empty` and let agents with no usable channel survive parse.
    let synthetic_empty_binary: Distribution = serde_json::from_str(r#"{"binary":{}}"#).unwrap();
    assert!(synthetic_empty_binary.is_empty());
}

#[test]
fn rejects_agent_with_empty_binary_map() {
    let synthetic = r#"{
        "version": "1.0.0",
        "agents": [{
            "id": "empty-binary",
            "name": "EmptyBinary",
            "version": "1.0.0",
            "description": "binary map present but empty",
            "distribution": { "binary": {} }
        }]
    }"#;
    match Registry::parse(synthetic) {
        Err(RegistryError::EmptyDistribution(id)) => assert_eq!(id, "empty-binary"),
        other => panic!("expected EmptyDistribution, got {other:?}"),
    }
}
