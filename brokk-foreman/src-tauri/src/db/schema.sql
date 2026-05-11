-- Baseline schema for brokk-foreman v1. Applied on first launch.
-- The migration runner enables `PRAGMA foreign_keys = ON;` on every
-- connection (SQLite's default is OFF). v1 has no FK relationships among
-- the two tables below, but the pragma is kept on for forward compat
-- when worktree/session/issue tables land in later milestones.
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

CREATE TABLE IF NOT EXISTS config_kv (
  key             TEXT PRIMARY KEY,
  value           TEXT NOT NULL
);
