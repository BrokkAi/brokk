//! SQLite state for foreman.
//!
//! Schema lives next to this file as `schema.sql`. The migration runner
//! tracks `PRAGMA user_version` and only applies migrations the database
//! hasn't seen yet. `PRAGMA foreign_keys = ON;` is applied on every
//! connection — SQLite's default is OFF and the `ON DELETE CASCADE`
//! clauses in the schema are silently inactive otherwise.

use std::path::Path;

use rusqlite::Connection;
use thiserror::Error;

pub const BASELINE_SCHEMA: &str = include_str!("schema.sql");

/// Latest schema version this binary knows how to produce.
pub const CURRENT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Error)]
pub enum DbError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("io error preparing database directory: {0}")]
    Io(#[from] std::io::Error),
    #[error(
        "database is at schema v{found}, newer than this binary's v{expected}; refusing to downgrade"
    )]
    NewerSchema { found: u32, expected: u32 },
}

pub type Result<T> = std::result::Result<T, DbError>;

/// Open a SQLite database at `path`, apply per-connection pragmas, and
/// run any pending migrations. Creates parent directories if needed.
pub fn open(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    let mut conn = Connection::open(path)?;
    apply_pragmas(&conn)?;
    migrate(&mut conn)?;
    Ok(conn)
}

/// In-memory database with the same pragmas + migrations as [`open`].
/// Intended for tests and short-lived ephemeral state.
pub fn open_in_memory() -> Result<Connection> {
    let mut conn = Connection::open_in_memory()?;
    apply_pragmas(&conn)?;
    migrate(&mut conn)?;
    Ok(conn)
}

fn apply_pragmas(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "PRAGMA foreign_keys = ON;\n\
         PRAGMA journal_mode = WAL;\n\
         PRAGMA busy_timeout = 5000;",
    )?;
    Ok(())
}

fn migrate(conn: &mut Connection) -> Result<()> {
    let current: u32 = conn.query_row("PRAGMA user_version", [], |row| row.get(0))?;

    if current > CURRENT_SCHEMA_VERSION {
        return Err(DbError::NewerSchema {
            found: current,
            expected: CURRENT_SCHEMA_VERSION,
        });
    }
    if current == CURRENT_SCHEMA_VERSION {
        return Ok(());
    }

    tracing::info!(
        from = current,
        to = CURRENT_SCHEMA_VERSION,
        "applying foreman db migrations"
    );

    if current < 1 {
        let tx = conn.transaction()?;
        tx.execute_batch(BASELINE_SCHEMA)?;
        tx.pragma_update(None, "user_version", 1u32)?;
        tx.commit()?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrate_creates_all_tables() {
        let conn = open_in_memory().unwrap();
        let names: Vec<String> = conn
            .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .unwrap()
            .query_map([], |row| row.get::<_, String>(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        for expected in [
            "agents",
            "blocking_edges",
            "config_kv",
            "issues",
            "sessions",
        ] {
            assert!(
                names.iter().any(|n| n == expected),
                "missing table {expected}; got {names:?}"
            );
        }
    }

    #[test]
    fn schema_version_set_to_current() {
        let conn = open_in_memory().unwrap();
        let v: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, CURRENT_SCHEMA_VERSION);
    }

    #[test]
    fn migrate_is_idempotent() {
        let mut conn = open_in_memory().unwrap();
        migrate(&mut conn).unwrap();
        migrate(&mut conn).unwrap();
        let v: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, CURRENT_SCHEMA_VERSION);
    }

    #[test]
    fn fk_cascade_actually_fires() {
        let conn = open_in_memory().unwrap();
        conn.execute(
            "INSERT INTO issues(id, provider, external_key, title, imported_at, updated_at) \
             VALUES ('github:42', 'github', '42', 'a', 0, 0)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO issues(id, provider, external_key, title, imported_at, updated_at) \
             VALUES ('github:43', 'github', '43', 'b', 0, 0)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO blocking_edges(blocker_id, blocked_id, computed_at) \
             VALUES ('github:42', 'github:43', 0)",
            [],
        )
        .unwrap();
        conn.execute("DELETE FROM issues WHERE id = 'github:42'", [])
            .unwrap();
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM blocking_edges", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn rejects_newer_schema() {
        let mut conn = rusqlite::Connection::open_in_memory().unwrap();
        apply_pragmas(&conn).unwrap();
        conn.pragma_update(None, "user_version", 999u32).unwrap();
        let err = migrate(&mut conn).unwrap_err();
        match err {
            DbError::NewerSchema { found, expected }
                if found == 999 && expected == CURRENT_SCHEMA_VERSION => {}
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[test]
    fn open_creates_parent_dirs() {
        let tmp = tempfile_dir();
        let nested = tmp.join("a/b/c/state.db");
        let conn = open(&nested).unwrap();
        let v: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, CURRENT_SCHEMA_VERSION);
        assert!(nested.exists());
    }

    fn tempfile_dir() -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("brokk-foreman-db-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&p).unwrap();
        p
    }
}
