//! Foreman configuration: data-dir resolution plus a thin `config_kv`-backed
//! `Config` (repo path + default agent id). Values are persisted in the
//! `config_kv` SQLite table so the same connection state used by the rest of
//! the app owns them — no separate TOML on disk in v1.

use std::path::PathBuf;

use directories::ProjectDirs;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

/// Resolve the per-user data directory for foreman, e.g.
/// `~/Library/Application Support/ai.brokk.foreman/` on macOS.
///
/// Returns `None` on bare CI containers without `$HOME`.
pub fn data_dir() -> Option<PathBuf> {
    ProjectDirs::from("ai", "brokk", "foreman").map(|p| p.data_dir().to_path_buf())
}

/// Directory under `data_dir()` where installed agent binaries are extracted.
/// Returns `None` for the same reason `data_dir` does.
pub fn agents_install_dir() -> Option<PathBuf> {
    data_dir().map(|d| d.join("agents"))
}

const KEY_REPO_PATH: &str = "repo_path";
const KEY_DEFAULT_AGENT_ID: &str = "default_agent_id";

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Config {
    pub repo_path: Option<String>,
    pub default_agent_id: Option<String>,
}

impl Config {
    pub fn load(conn: &Connection) -> rusqlite::Result<Self> {
        Ok(Self {
            repo_path: get_kv(conn, KEY_REPO_PATH)?,
            default_agent_id: get_kv(conn, KEY_DEFAULT_AGENT_ID)?,
        })
    }

    pub fn save(&self, conn: &Connection) -> rusqlite::Result<()> {
        set_kv(conn, KEY_REPO_PATH, self.repo_path.as_deref())?;
        set_kv(conn, KEY_DEFAULT_AGENT_ID, self.default_agent_id.as_deref())?;
        Ok(())
    }
}

fn get_kv(conn: &Connection, key: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row(
        "SELECT value FROM config_kv WHERE key = ?1",
        params![key],
        |row| row.get::<_, String>(0),
    )
    .optional()
}

fn set_kv(conn: &Connection, key: &str, value: Option<&str>) -> rusqlite::Result<()> {
    match value {
        Some(v) => {
            conn.execute(
                "INSERT INTO config_kv (key, value) VALUES (?1, ?2) \
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                params![key, v],
            )?;
        }
        None => {
            conn.execute("DELETE FROM config_kv WHERE key = ?1", params![key])?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db;

    #[test]
    fn round_trips_through_config_kv() {
        let conn = db::open_in_memory().unwrap();
        let cfg = Config {
            repo_path: Some("/tmp/repo".into()),
            default_agent_id: Some("registry:claude-acp".into()),
        };
        cfg.save(&conn).unwrap();
        let loaded = Config::load(&conn).unwrap();
        assert_eq!(loaded.repo_path.as_deref(), Some("/tmp/repo"));
        assert_eq!(
            loaded.default_agent_id.as_deref(),
            Some("registry:claude-acp")
        );
    }

    #[test]
    fn clearing_keys_removes_rows() {
        let conn = db::open_in_memory().unwrap();
        Config {
            repo_path: Some("/tmp/repo".into()),
            default_agent_id: None,
        }
        .save(&conn)
        .unwrap();
        Config::default().save(&conn).unwrap();
        let loaded = Config::load(&conn).unwrap();
        assert!(loaded.repo_path.is_none());
        assert!(loaded.default_agent_id.is_none());
    }
}
