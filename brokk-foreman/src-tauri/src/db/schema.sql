-- Baseline schema for brokk-foreman. Applied on first launch.
-- ON DELETE CASCADE below is silently ignored unless every connection runs
-- `PRAGMA foreign_keys = ON;` first; SQLite's default is OFF. The migration
-- runner that consumes this file must enable it on each connection it opens.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS agents (
  id              TEXT PRIMARY KEY,
  source          TEXT NOT NULL,        -- 'registry' | 'custom'
  name            TEXT NOT NULL,
  version         TEXT,
  distribution    TEXT NOT NULL,        -- JSON serialized Distribution
  installed_path  TEXT,
  enabled         INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS issues (
  id              TEXT PRIMARY KEY,
  provider        TEXT NOT NULL,        -- 'github' | 'jira'
  external_key    TEXT NOT NULL,
  title           TEXT NOT NULL,
  body            TEXT,
  url             TEXT,
  state           TEXT,
  kanban_column   TEXT NOT NULL DEFAULT 'backlog',
  enrichment      TEXT,                 -- JSON
  enriched_at     INTEGER,
  imported_at     INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS blocking_edges (
  blocker_id      TEXT NOT NULL,
  blocked_id      TEXT NOT NULL,
  confidence      REAL,
  rationale       TEXT,
  computed_at     INTEGER NOT NULL,
  PRIMARY KEY (blocker_id, blocked_id),
  FOREIGN KEY (blocker_id) REFERENCES issues(id) ON DELETE CASCADE,
  FOREIGN KEY (blocked_id) REFERENCES issues(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sessions (
  id              TEXT PRIMARY KEY,
  issue_id        TEXT NOT NULL UNIQUE,
  agent_id        TEXT NOT NULL,
  worktree_path   TEXT NOT NULL,
  branch          TEXT NOT NULL,
  acp_session_id  TEXT,
  status          TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  closed_at       INTEGER,
  FOREIGN KEY (issue_id) REFERENCES issues(id) ON DELETE CASCADE,
  FOREIGN KEY (agent_id) REFERENCES agents(id)
);

CREATE TABLE IF NOT EXISTS config_kv (
  key             TEXT PRIMARY KEY,
  value           TEXT NOT NULL
);
