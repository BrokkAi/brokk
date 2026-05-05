# brokk-foreman — design plan

Cross-platform desktop "issue bot" and **pure** ACP client: list GitHub/Jira
issues, enrich them with code context via a user-chosen ACP agent, detect
blocking dependencies, run one ACP session per issue in its own git
worktree, watch them move across a kanban board.

This document describes the intended end state. The scaffolding is already
in tree; the rest lands incrementally.

## Status

Landed in #3520 (initial scaffold):

- Cargo workspace + Tauri 2 `src-tauri` (Rust 2024, LGPL-3.0).
- Svelte 5 + Vite SPA frontend.
- Public ACP registry parser at `src-tauri/src/acp/registry.rs` matching
  upstream `agent.schema.json`, plus 9 tests against a pinned 35-agent
  snapshot of the live registry.
- Module stubs for `acp/`, `issues/`, `llm/`, `worktree`, `db/` with a
  baseline SQL schema (`src-tauri/src/db/schema.sql`).
- 3-OS CI (ubuntu/macos/windows) running `cargo fmt --check`, `cargo
  build`, `cargo test`, `cargo clippy -D warnings`, then `pnpm install
  --frozen-lockfile`, `pnpm check`, `pnpm build`.

Next, in roughly this order:

1. **ACP client wrapper** (`acp/launcher.rs`, `acp/mod.rs`): spawn
   binary / npx / uvx agents, perform `initialize` + `session/new`, stream
   `SessionUpdate` events to the frontend over Tauri events.
2. **SQLite migration runner** in `db/mod.rs` consuming `db/schema.sql`.
3. **GitHub provider** via `octocrab`; then **Jira provider** via
   `reqwest`.
4. **Worktree manager** (shell-out to `git worktree`).
5. **Tauri commands** wired to the frontend.
6. **Kanban UI** + **issue view**.
7. **Enrichment-on-import** via ACP.
8. **Blocking-graph analysis** via ACP.

## Context

A new Tauri desktop app, `brokk-foreman`, that acts as a **pure ACP
client** for managing GitHub/Jira issues against a local git repository.
Users list issues, let an LLM enrich them with code context and detect
blocking dependencies, drag them across a kanban board, and start ACP
sessions per issue (one worktree per session) using **any** ACP-compliant
agent — picked from the official ACP registry or supplied as a custom
install.

The app does **no** agentic logic of its own. All LLM work — enrichment,
blocking-graph extraction, the actual coding work on each issue — runs
through ACP sessions whose backend agent is user-chosen and swappable
(Brokk, Claude Agent, Gemini, Codex, Goose, etc.).

This decouples brokk-foreman from any one vendor's stack and aligns with
the ACP industry direction: Zed and JetBrains shipped registry support in
early 2026, and brokk-foreman becomes a third client of the same registry.

## Non-goals

- No coupling to Brokk's Java backend (`BrokkExternalMcpServer`, Java
  agents, etc.). brokk-foreman runs without brokk installed.
- No multi-repo support in v1 (one repo per app instance).
- No mirroring of GitHub Projects boards or Jira sprint state to/from the
  local kanban — kanban state is local-only.
- No multi-user or cloud sync.
- No agent-side auth credential management beyond triggering each agent's
  own auth flow (the agent stores its own keys).

## Architecture summary

```
+-----------------------------------------------------------+
|                     Tauri main (Rust)                     |
|  +------------+  +-----------+  +-----------+  +-------+  |
|  | ACP client |  | Registry  |  | Issue     |  | Git   |  |
|  | (per       |  | client    |  | providers |  | worktree |
|  |  session)  |  | + cache   |  | (GH/Jira) |  | runner|  |
|  +------+-----+  +-----+-----+  +-----+-----+  +---+---+  |
|         |              |              |            |      |
|  +------v-------- SQLite (state.db) --v---------+--v---+  |
|         |                                                  |
|  +------v---- IPC (tauri::Event/Command) ----------------+ |
|         |                                                  |
|  +------v--- Svelte frontend (Vite, svelte-dnd-action) --+ |
+-----------------------------------------------------------+
            |                          |
            v (spawns)                 v (HTTPS)
   +------------------+        +-------------------+
   | ACP agent procs  |        | api.github.com,   |
   | (1..N, stdio JSON|        | <jira host>/rest, |
   |  -RPC, agent-    |        | cdn.agentclient.. |
   |  client-protocol |        +-------------------+
   |  v0.11)          |
   +------------------+
```

Process boundaries:

- **Tauri main** is one Rust process; it's the only one talking to disk,
  the registry CDN, the issue REST APIs, and any ACP agent.
- **Svelte frontend** runs in the system webview. It only talks to the
  Rust backend via Tauri commands and events.
- **ACP agents** run as child processes of the Rust main. There's
  typically one per active issue (working session), plus short-lived
  ephemeral sessions for enrichment and blocking analysis.

## Cargo layout

`brokk-foreman/` lives at the brokk monorepo root, parallel to
`brokk-acp-rust/`. It is its own Cargo workspace; brokk-acp-rust and
brokk-foreman do not share runtime code (one is an ACP server, the other
a client). They could share a small types crate later if needed.

The intended module tree (some files are already stubs in tree, others
land later):

```
brokk-foreman/
  Cargo.toml                  # workspace root
  src-tauri/
    Cargo.toml
    tauri.conf.json
    build.rs
    src/
      main.rs                 # Tauri setup, command registration
      lib.rs                  # module declarations + run()
      commands.rs             # #[tauri::command] handlers (frontend bridge)
      events.rs               # event payload types
      acp/
        mod.rs                # AcpSession, AcpClient, lifecycle
        registry.rs           # fetch + cache + parse registry.json    [landed]
        launcher.rs           # binary/npx/uvx launching, auth flows
        protocol.rs           # thin wrappers over agent-client-protocol crate
      issues/
        mod.rs                # IssueProvider trait
        github.rs             # octocrab-backed
        jira.rs               # reqwest-backed REST
      worktree.rs             # git worktree add/remove/list (shell-out)
      llm/
        enrich.rs             # one-shot enrichment session via ACP
        blocking.rs           # cross-issue blocking analysis via ACP
      db/
        mod.rs                # rusqlite, migrations, queries
        schema.sql            # baseline schema                        [landed]
      config.rs               # foreman config in ~/.brokk/foreman/config.toml
  src/                        # Svelte frontend
    routes/
      +layout.svelte
      +page.svelte            # /kanban
      issues/[id]/+page.svelte
      agents/+page.svelte
      settings/+page.svelte
    lib/
      api.ts                  # typed wrappers for tauri invoke
      events.ts               # tauri event subscriptions
      stores/
        issues.ts
        agents.ts
        sessions.ts
    components/
      Kanban.svelte
      KanbanColumn.svelte
      IssueCard.svelte
      SessionPane.svelte
      AgentPicker.svelte
      RegistryBrowser.svelte
  package.json
  vite.config.ts
  svelte.config.js
  tsconfig.json
  index.html
```

## Key dependencies

Rust (`src-tauri/Cargo.toml`):

- `tauri = "2"`, `tauri-plugin-shell = "2"`
- `agent-client-protocol = "=0.11.1"` (exact pin)
- `tokio` (full)
- `serde`, `serde_json`
- `reqwest` (rustls) for the registry CDN and Jira
- `octocrab` (rustls) for GitHub
- `rusqlite` (bundled) for state
- `directories`, `which`
- `flate2`, `tar`, `zip` for binary distribution archive extraction
- `tracing`, `tracing-subscriber`
- `thiserror`, `anyhow`, `uuid`, `url`

Frontend (`package.json`):

- `svelte` 5, `vite`
- `svelte-dnd-action` for kanban drag-and-drop
- `@tauri-apps/api`, `@tauri-apps/plugin-shell`

## Data model (SQLite, `~/.brokk/foreman/state.db`)

Baseline schema is already in tree at `src-tauri/src/db/schema.sql`:

```sql
CREATE TABLE agents (
  id              TEXT PRIMARY KEY,    -- registry id or user-chosen for custom
  source          TEXT NOT NULL,       -- 'registry' | 'custom'
  name            TEXT NOT NULL,
  version         TEXT,
  distribution    TEXT NOT NULL,       -- JSON: serialized distribution block
  installed_path  TEXT,                -- for binary dists: extracted dir
  enabled         INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE issues (
  id              TEXT PRIMARY KEY,    -- "<provider>:<key>"
  provider        TEXT NOT NULL,       -- 'github' | 'jira'
  external_key    TEXT NOT NULL,
  title           TEXT NOT NULL,
  body            TEXT,
  url             TEXT,
  state           TEXT,                -- provider-side state
  kanban_column   TEXT NOT NULL DEFAULT 'backlog',
  enrichment      TEXT,                -- JSON blob (see llm/enrich.rs)
  enriched_at     INTEGER,
  imported_at     INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE blocking_edges (
  blocker_id      TEXT NOT NULL,
  blocked_id      TEXT NOT NULL,
  confidence      REAL,
  rationale       TEXT,
  computed_at     INTEGER NOT NULL,
  PRIMARY KEY (blocker_id, blocked_id),
  FOREIGN KEY (blocker_id) REFERENCES issues(id) ON DELETE CASCADE,
  FOREIGN KEY (blocked_id) REFERENCES issues(id) ON DELETE CASCADE
);

CREATE TABLE sessions (
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

CREATE TABLE config_kv (
  key             TEXT PRIMARY KEY,
  value           TEXT NOT NULL
);
```

The migration runner that opens this DB **must** issue `PRAGMA
foreign_keys = ON;` on every connection — SQLite's default is OFF and the
`ON DELETE CASCADE` clauses are silently inactive otherwise.

## ACP registry integration

- Registry URL: `https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`
- Schema URL: `https://cdn.agentclientprotocol.com/registry/v1/latest/agent.schema.json`
- Format: `{ "version": "1.0.0", "agents": [...] }`. CDN sets
  `Cache-Control: max-age=300`.
- `acp/registry.rs` parses with strict `deny_unknown_fields` on the
  per-agent types so any upstream schema drift breaks tests instead of
  silently dropping fields.
- `acp/registry.rs` will, in a follow-up commit, fetch with `reqwest`,
  validate `version == "1.0.0"`, persist the raw JSON at
  `~/.brokk/foreman/registry-cache.json`, and respect the CDN cache TTL
  plus an `If-None-Match`/`ETag` revalidation.
- `RegistryAgent` mirrors the upstream schema: `id`, `name`, `version`,
  `description`, `repository`, `website`, `authors`, `license`, `icon`,
  `distribution` with three variants (`binary` / `npx` / `uvx`).
- `Platform::current()` resolves the running host to one of the registry's
  six published targets (`darwin-aarch64`, `darwin-x86_64`,
  `linux-aarch64`, `linux-x86_64`, `windows-aarch64`, `windows-x86_64`).
  `RegistryAgent::supported_on_host()` filters the registry browser to
  "things you could install today".

### Custom agents

A `CustomAgent` is the same shape as a registry entry but tagged
`source: "custom"`, with a free-form `cmd` / `args` / `env` instead of a
registry distribution block. Stored in the `agents` table with
`source='custom'`. UI: an "Add agent" form with name + binary path + args
+ env editor; this gets stored as a synthetic single-target binary
distribution keyed to the current platform (no archive download).

### Install flow

- For `npx` and `uvx`: nothing to install ahead of time; we just verify
  `npx` (or `uvx`) is on PATH at activation time via the `which` crate.
- For `binary`: download `archive` to
  `~/.brokk/foreman/agents/<id>/<version>.<ext>`, extract to
  `~/.brokk/foreman/agents/<id>/<version>/`, store the resulting absolute
  path of `cmd` in `agents.installed_path`. Reject
  `.dmg/.pkg/.deb/.rpm/.msi/.appimage` per the registry spec.

### Auth handling

When `session/new` returns `AUTH_REQUIRED`, the response declares either:

- `type: "agent"` — Agent Auth: the agent owns the OAuth flow (browser +
  local callback). brokk-foreman just calls `authenticate` per the ACP
  spec and waits.
- `type: "terminal"` — Terminal Auth: relaunch the agent with the
  response's `args`/`env` *replacing* the defaults, attached to a TTY. v1
  spawns Terminal Auth in the user's default terminal app via
  `tauri-plugin-shell` and waits for the agent to exit before resuming;
  in-app pty is a v2 enhancement.

## ACP client surface (`acp/mod.rs`, `acp/launcher.rs`)

The `agent-client-protocol` crate handles the wire protocol. Our wrapper
provides:

- `AcpClient::launch(agent: &AgentRecord, cwd: &Path) -> AcpClient` —
  spawns the configured binary/npx/uvx with `cmd + args + env`, wires
  stdin/stdout to the protocol's `Connection`, performs `initialize`.
- `AcpClient::new_session(opts) -> SessionHandle` — calls `session/new`
  with `cwd` set to the worktree path; returns the ACP session id.
- `AcpClient::prompt(session, content)` — sends a `prompt` request and
  returns a stream of `SessionUpdate` events.
- `AcpClient::cancel(session)` — issues `cancel`.
- `AcpClient::shutdown()` — drops the connection and `kill_on_drop`
  cleans up.

Streaming: `SessionUpdate` events are forwarded to the frontend as Tauri
events on channel `acp:update:<session_id>`. The frontend renders them in
`SessionPane.svelte`. Permission requests (`requestPermission`) are
surfaced as a modal in the issue view; the user accepts/rejects and the
response is forwarded back through the ACP connection. **No auto-accept**
in v1 — even though brokk-foreman runs locally, silent auto-accept
defeats the kanban's "I can see what each session is doing" purpose.

Reference for stdio JSON-RPC subprocess management: see
`../brokk-acp-rust/src/bifrost_client.rs` — same line-framed JSON-RPC
over `tokio::process` with `kill_on_drop(true)` and `BufReader`-wrapped
`ChildStdout`.

## Issue providers (`issues/`)

Trait:

```rust
#[async_trait]
trait IssueProvider {
    async fn list_issues(&self, filter: ListFilter) -> Result<Vec<Issue>>;
    async fn fetch_issue(&self, key: &str) -> Result<Issue>;
    async fn update_state(&self, key: &str, new_state: ProviderState) -> Result<()>;
}
```

- **GitHub**: `octocrab` with PAT from `~/.brokk/foreman/config.toml` or
  `GH_TOKEN` env.
- **Jira**: `reqwest` against `<host>/rest/api/3/search` and
  `/issue/{key}`. Basic auth (email + API token) or PAT, configured per
  host. Reimplemented in Rust — no JNI, no Java dependency.

Both fetch on a manual "Refresh" button and on a configurable interval
(default 5 min) using a tokio task.

## Worktree manager (`worktree.rs`)

Shells out to `git worktree`, matching the brokk Java convention so the
same physical layout works whether the user opens the worktree from brokk
or from foreman:

- Storage root: `~/.brokk/worktrees/<repo-folder-name>/wt<N>` where `<N>`
  is the next free integer (matches `getNextWorktreePath()` in
  `../app/src/main/java/ai/brokk/git/GitRepoWorktrees.java`).
- Branch naming: `foreman/<issue-key>` (slugified). If the branch
  exists, reuse via `git worktree add <path> <branch>`; otherwise
  `git worktree add -b <branch> <path>` from the configured base branch
  (default `main`/`master`).
- Removal: explicit user action ("Close session" on the kanban card) →
  `git worktree remove --force <path>` then optional branch delete if not
  pushed. Same merge-prompt UX brokk uses.
- All commands run with a 30s timeout via tokio `Command` +
  `time::timeout`. No JGit; matches the `Environment.runShellCommand`
  pattern in brokk.

## Enrichment via ACP (`llm/enrich.rs`)

Triggered automatically on each issue import (default agent picked in
settings). Steps:

1. Spawn the default agent in **read-only mode in the repo root** (no
   worktree — enrichment doesn't write).
2. `session/new` with `cwd = <repo-root>`.
3. `prompt` with a structured request:

   ```
   You are analyzing a GitHub/Jira issue. Read the codebase as needed and
   return ONLY a JSON object inside a fenced ```json block, with this
   shape:

   {
     "summary": "1-3 sentence plain-English restatement of the issue",
     "affectedAreas": [{"path": "...", "why": "..."}],
     "keySymbols": ["ClassName.methodName", ...],
     "suggestedTests": ["short imperative test names"],
     "openQuestions": ["..."],
     "estimatedComplexity": "S" | "M" | "L"
   }

   Issue title: <title>
   Issue body:
   <body>
   ```

4. Stream `SessionUpdate` events; once the turn completes, parse the last
   assistant message for the fenced JSON; persist to `issues.enrichment`
   and move the card from `backlog` → `enriched`.
5. Close the session.

Failure modes (agent doesn't return parseable JSON, hits a permission
prompt, etc.) leave the issue in `backlog` with a `last_enrichment_error`
and a manual "Re-enrich" button. v1 doesn't try to be clever about
retries.

## Blocking analysis via ACP (`llm/blocking.rs`)

A **manual button** on the kanban (and a debounced auto-trigger when ≥3
issues become enriched). One ACP session, one prompt that includes the
title + summary + affectedAreas of every non-Done issue:

```
Given the following list of open issues with their summaries and
affectedAreas, identify pairs (A, B) where completing A is prerequisite
for B (A blocks B). Return ONLY a JSON object: { "edges": [{"blockerId":
"...", "blockedId": "...", "confidence": 0..1, "rationale": "..."}] }.
```

Token budgeting: cap at 60 issues per call; if more, partition by
ownership of `affectedAreas` paths and run multiple calls. Persist edges
in `blocking_edges`, replacing prior entries fully on each run (keep
`computed_at` for staleness). Render as a "Blocked by N" badge on each
card and a flow-arrows overlay when the user toggles it.

## Kanban

Columns (fixed in v1, configurable in v2):

`Backlog` → `Enriched` → `Ready` → `In Progress` → `In Review` → `Done`

Transitions:

- Manual drag-and-drop via `svelte-dnd-action` (always allowed, no FSM
  enforcement in v1).
- Automatic: `Backlog` → `Enriched` on successful enrichment;
  `Ready` → `In Progress` when the user starts a session;
  `In Progress` → `In Review` if a PR is detected for the branch;
  `In Review` → `Done` on PR merge. The PR detection is a 60s poll of
  GitHub's `pulls?head=...` endpoint — Jira-only repos don't get the auto
  move-to-review.

Local-only `kanban_column` (per the non-goal above). The provider's own
state is shown as a separate badge but not synced.

## Frontend layout (Svelte 5)

- `/kanban` (default): the board. Filter bar (provider, label, agent),
  drag-and-drop columns, "Run blocking analysis" button.
- `/issues/:id`: split view. Top: enrichment block + open questions.
  Middle: live `SessionPane` (streaming ACP `SessionUpdate` events,
  permission prompts inline). Bottom: associated worktree path, branch,
  "Open in editor" buttons.
- `/agents`: the registry browser (table from cached `registry.json`),
  enable/disable, install/uninstall, "Add custom agent" form. Status
  indicator per agent (installed, auth pending, error).
- `/settings`: GitHub PAT + Jira host/credentials, default agent for
  enrichment, default agent suggestion list for new sessions, base
  branch.

State stores (`issues.ts`, `agents.ts`, `sessions.ts`) are each backed by
a Tauri command for initial load and Tauri events for updates.

## Tauri commands surfaced to the frontend (`commands.rs`)

```
list_issues(filter)               -> Vec<Issue>
refresh_issues()                  -> ()
get_issue(id)                     -> IssueDetail
list_agents()                     -> Vec<AgentRecord>
fetch_registry(force_refresh)     -> Vec<RegistryAgent>
install_agent(registry_id)        -> AgentRecord
uninstall_agent(id)                -> ()
add_custom_agent(spec)            -> AgentRecord
start_session(issue_id, agent_id) -> SessionRecord
stop_session(session_id)          -> ()
send_prompt(session_id, text)     -> ()    // emits acp:update:<id> events
respond_permission(req_id, accept)-> ()
move_card(issue_id, column)       -> ()
run_blocking_analysis()           -> ()
```

## Risks and open issues

1. **Structured-output reliability across agents.** Different agents have
   different propensities to add prose around fenced JSON, ignore the
   format, or wrap in extra code fences. Mitigation: a tolerant parser
   that strips markdown fences, finds the largest valid JSON object, and
   falls back to "couldn't parse — re-run". Long-term: consider an
   ACP-level "structured response" extension if one lands.
2. **Per-agent quirks at launch.** Some `npx` agents are slow on first
   run (cold install). Some binary releases mismatch `cmd` against actual
   extracted layout. We need a clear timeout + error UI per agent; v1
   surfaces stdout/stderr in the agents page on failure.
3. **Authentication UX divergence.** Agent Auth is hands-off, but
   Terminal Auth needs a real pty with the user staring at the screen.
   Embedding a TTY view in the webview is non-trivial; v1 spawns Terminal
   Auth in the user's default terminal app and waits for the agent to
   exit before resuming.
4. **Worktree disk usage.** Many open issues × full repo checkout per
   worktree = real disk pressure on big monorepos. Default to **lazy
   worktrees** (`git worktree add` only on session start, removed on
   session close) — never for un-started issues.
5. **Branch collision with brokk.** Brokk also creates worktrees in
   `~/.brokk/worktrees/<repo>/`. We use a distinct branch prefix
   (`foreman/...`) and reuse `getNextWorktreePath`-style numeric names;
   brokk and foreman can coexist in the same repo without stepping on
   each other, but a user mass-deleting via brokk's UI can orphan our
   session records. v1 detects this on startup with `git worktree list
   --porcelain` and marks orphaned sessions `failed`.

## Verification (end-to-end, once the wrapper and UI land)

1. `cd brokk-foreman && cargo tauri dev` launches the dev app against a
   Vite dev server.
2. Open the agents page; the registry loads (verify network tab shows
   the CDN URL); install `claude-acp` (npx) or a custom local binary.
3. Configure GitHub PAT in settings; click Refresh on the kanban; verify
   open issues appear in `Backlog`.
4. Wait for auto-enrichment to flip cards to `Enriched`; click an issue;
   verify the JSON enrichment is rendered and the original prompt /
   response is in the audit trail.
5. Click "Run blocking analysis"; verify edges appear and `Blocked by N`
   badges render.
6. From an `Enriched` issue, click "Start session" with a chosen agent;
   verify a worktree is created at `~/.brokk/worktrees/<repo>/wt<N>` on
   the `foreman/<key>` branch and the agent process starts; verify
   `SessionPane` streams responses and permission prompts.
7. Send a prompt; observe streaming. Cancel; observe clean shutdown of
   the child process (`ps -p` shows it gone).
8. Open the worktree separately (`git -C <path> status`); verify it is
   real and on the right branch.
9. Drag the card to `Done`; click "Close session"; verify worktree
   removal and branch cleanup, and `sessions.status='closed'` in the DB.
10. Tests: `cargo test` for the registry parser (already in tree), the
    worktree shell-out (against a temp git repo), and the
    enrichment-JSON tolerant parser (varied real agent outputs as
    fixtures).

## Scope cut summary for v1 ship

Keep: kanban, issue list, enrichment-on-import, blocking analysis, ACP
sessions per issue with worktrees, registry browser + custom installs,
Agent Auth + (out-of-app) Terminal Auth.

Cut: in-app pty for Terminal Auth, GitHub Projects/Jira board sync,
multi-repo, configurable kanban columns, mobile/web build, auto-resume of
crashed sessions across restarts (we leave the session marked `failed`
and the user re-runs).
