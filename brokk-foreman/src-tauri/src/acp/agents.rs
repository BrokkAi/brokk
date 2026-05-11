//! Persistence layer for the `agents` table — registry-imported and
//! user-defined custom agents. `AgentRecord` is the typed row; `launch_spec`
//! resolves the stored `Distribution` plus optional install path into the
//! concrete `cmd + args + env` triple the launcher hands to
//! `tokio::process::Command`.

use std::collections::HashMap;
use std::path::PathBuf;

use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::acp::registry::{Distribution, Platform, RegistryAgent};

/// One row of the `agents` table.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentRecord {
    pub id: String,
    pub source: AgentSource,
    pub name: String,
    pub version: Option<String>,
    pub distribution: Distribution,
    pub installed_path: Option<String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentSource {
    Registry,
    Custom,
}

impl AgentSource {
    fn parse(s: &str) -> Result<Self, AgentsError> {
        match s {
            "registry" => Ok(Self::Registry),
            "custom" => Ok(Self::Custom),
            other => Err(AgentsError::UnknownSource(other.into())),
        }
    }
}

/// User-supplied custom agent spec — the v1 "add agent" form payload.
/// Stored as a synthetic single-target binary `Distribution` keyed to the
/// current host platform so the launcher path is identical for registry
/// and custom agents.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomAgentSpec {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub version: Option<String>,
    pub cmd: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
}

#[derive(Debug, Error)]
pub enum AgentsError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("agent json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("agent {0:?} not found")]
    NotFound(String),
    #[error("unknown agent source {0:?} in db")]
    UnknownSource(String),
    #[error("custom agent {0:?} cannot resolve a launch spec on this host")]
    NoLaunchTarget(String),
    #[error("agent {0:?} has no installed_path; install it via install_agent before launching")]
    NotInstalled(String),
}

/// List every row in `agents`, ordered by name.
pub fn list(conn: &Connection) -> Result<Vec<AgentRecord>, AgentsError> {
    let mut stmt = conn.prepare(
        "SELECT id, source, name, version, distribution, installed_path, enabled \
         FROM agents ORDER BY name ASC",
    )?;
    let rows = stmt.query_map([], row_to_record)?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(AgentsError::Sqlite)
        .and_then(|rs| rs.into_iter().collect())
}

/// Fetch a single agent row.
pub fn get(conn: &Connection, id: &str) -> Result<AgentRecord, AgentsError> {
    conn.query_row(
        "SELECT id, source, name, version, distribution, installed_path, enabled \
         FROM agents WHERE id = ?1",
        params![id],
        row_to_record,
    )
    .map_err(|e| match e {
        rusqlite::Error::QueryReturnedNoRows => AgentsError::NotFound(id.into()),
        other => AgentsError::Sqlite(other),
    })
    .and_then(|r| r)
}

/// Insert a registry agent (no install yet — `installed_path` left `NULL`).
pub fn upsert_registry(
    conn: &Connection,
    agent: &RegistryAgent,
) -> Result<AgentRecord, AgentsError> {
    let dist_json = serde_json::to_string(&agent.distribution)?;
    conn.execute(
        "INSERT INTO agents (id, source, name, version, distribution, installed_path, enabled) \
         VALUES (?1, 'registry', ?2, ?3, ?4, NULL, 1) \
         ON CONFLICT(id) DO UPDATE SET \
            name = excluded.name, version = excluded.version, distribution = excluded.distribution",
        params![agent.id, agent.name, agent.version, dist_json],
    )?;
    get(conn, &agent.id)
}

/// Insert a user-defined custom agent built from a `CustomAgentSpec`.
pub fn upsert_custom(
    conn: &Connection,
    spec: &CustomAgentSpec,
) -> Result<AgentRecord, AgentsError> {
    let distribution = custom_spec_to_distribution(spec);
    let dist_json = serde_json::to_string(&distribution)?;
    conn.execute(
        "INSERT INTO agents (id, source, name, version, distribution, installed_path, enabled) \
         VALUES (?1, 'custom', ?2, ?3, ?4, NULL, 1) \
         ON CONFLICT(id) DO UPDATE SET \
            name = excluded.name, version = excluded.version, distribution = excluded.distribution",
        params![spec.id, spec.name, spec.version, dist_json],
    )?;
    get(conn, &spec.id)
}

/// Record an extracted binary install path for an agent.
pub fn set_installed_path(
    conn: &Connection,
    id: &str,
    installed_path: &str,
) -> Result<(), AgentsError> {
    let n = conn.execute(
        "UPDATE agents SET installed_path = ?1 WHERE id = ?2",
        params![installed_path, id],
    )?;
    if n == 0 {
        return Err(AgentsError::NotFound(id.into()));
    }
    Ok(())
}

/// Remove an agent row and clear any stored install path.
pub fn delete(conn: &Connection, id: &str) -> Result<(), AgentsError> {
    let n = conn.execute("DELETE FROM agents WHERE id = ?1", params![id])?;
    if n == 0 {
        return Err(AgentsError::NotFound(id.into()));
    }
    Ok(())
}

/// Concrete launch spec resolved from an `AgentRecord` for the running host.
#[derive(Debug, Clone)]
pub struct LaunchSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub env: HashMap<String, String>,
    pub kind: LaunchKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchKind {
    Binary,
    Npx,
    Uvx,
}

/// Resolve the agent's distribution into the concrete launch spec. For
/// `binary` distributions we require `installed_path` to be set; the
/// caller (install flow) is responsible for downloading and extracting
/// the archive first.
pub fn launch_spec(record: &AgentRecord) -> Result<LaunchSpec, AgentsError> {
    let host = Platform::current();
    let dist = &record.distribution;

    if let (Some(p), Some(targets)) = (host, dist.binary.as_ref())
        && let Some(target) = targets.get(&p)
    {
        // Custom agents ship a synthetic binary distribution whose `cmd` is
        // the absolute path the user typed into the form — no install dir.
        // Registry binary agents have `cmd` relative to the extracted
        // archive root recorded in `installed_path`.
        let program = match record.source {
            AgentSource::Custom => PathBuf::from(&target.cmd),
            AgentSource::Registry => {
                let installed_path = record
                    .installed_path
                    .as_deref()
                    .ok_or_else(|| AgentsError::NotInstalled(record.id.clone()))?;
                PathBuf::from(installed_path).join(&target.cmd)
            }
        };
        return Ok(LaunchSpec {
            program,
            args: target.args.clone(),
            env: target.env.clone(),
            kind: LaunchKind::Binary,
        });
    }

    if let Some(npx) = dist.npx.as_ref() {
        let mut args = vec!["--yes".to_string(), npx.package.clone()];
        args.extend(npx.args.iter().cloned());
        return Ok(LaunchSpec {
            program: PathBuf::from("npx"),
            args,
            env: npx.env.clone(),
            kind: LaunchKind::Npx,
        });
    }

    if let Some(uvx) = dist.uvx.as_ref() {
        let mut args = vec![uvx.package.clone()];
        args.extend(uvx.args.iter().cloned());
        return Ok(LaunchSpec {
            program: PathBuf::from("uvx"),
            args,
            env: uvx.env.clone(),
            kind: LaunchKind::Uvx,
        });
    }

    Err(AgentsError::NoLaunchTarget(record.id.clone()))
}

fn custom_spec_to_distribution(spec: &CustomAgentSpec) -> Distribution {
    use crate::acp::registry::BinaryTarget;
    let host = Platform::current();
    let mut binary = HashMap::new();
    if let Some(host) = host {
        binary.insert(
            host,
            BinaryTarget {
                archive: String::new(),
                cmd: spec.cmd.clone(),
                args: spec.args.clone(),
                env: spec.env.clone(),
            },
        );
    }
    Distribution {
        binary: Some(binary),
        npx: None,
        uvx: None,
    }
}

fn row_to_record(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<AgentRecord, AgentsError>> {
    let id: String = row.get(0)?;
    let source: String = row.get(1)?;
    let name: String = row.get(2)?;
    let version: Option<String> = row.get(3)?;
    let distribution_json: String = row.get(4)?;
    let installed_path: Option<String> = row.get(5)?;
    let enabled: bool = row.get::<_, i64>(6)? != 0;

    Ok((|| -> Result<AgentRecord, AgentsError> {
        let source = AgentSource::parse(&source)?;
        let distribution: Distribution = serde_json::from_str(&distribution_json)?;
        Ok(AgentRecord {
            id,
            source,
            name,
            version,
            distribution,
            installed_path,
            enabled,
        })
    })())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db;

    fn sample_custom(id: &str) -> CustomAgentSpec {
        CustomAgentSpec {
            id: id.into(),
            name: format!("custom {id}"),
            version: Some("0.1".into()),
            cmd: "/usr/local/bin/my-agent".into(),
            args: vec!["--stdio".into()],
            env: HashMap::from([("FOO".into(), "bar".into())]),
        }
    }

    #[test]
    fn custom_agent_round_trip() {
        let conn = db::open_in_memory().unwrap();
        let rec = upsert_custom(&conn, &sample_custom("local-agent")).unwrap();
        assert_eq!(rec.source, AgentSource::Custom);
        assert_eq!(rec.name, "custom local-agent");

        let listed = list(&conn).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, "local-agent");

        delete(&conn, "local-agent").unwrap();
        assert!(list(&conn).unwrap().is_empty());
    }

    #[test]
    fn delete_unknown_errors() {
        let conn = db::open_in_memory().unwrap();
        let err = delete(&conn, "nope").unwrap_err();
        assert!(matches!(err, AgentsError::NotFound(_)));
    }

    #[test]
    fn custom_launch_spec_resolves_for_host() {
        let conn = db::open_in_memory().unwrap();
        let rec = upsert_custom(&conn, &sample_custom("c")).unwrap();
        if Platform::current().is_none() {
            // Skip on registry-unsupported targets (CI runners on exotic hosts).
            return;
        }
        let spec = launch_spec(&rec).unwrap();
        assert_eq!(spec.kind, LaunchKind::Binary);
        assert_eq!(spec.args, vec!["--stdio"]);
    }
}
