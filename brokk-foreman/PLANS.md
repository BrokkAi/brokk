# brokk-foreman — design plan (v1)

Cross-platform desktop **pure ACP client**: pick any ACP-compliant agent
(from the public registry or a user-defined custom command), point it at
a local repo, and run an interactive session against it.

This document describes the v1 end state. Issue providers, kanban,
enrichment, blocking analysis, and worktree-per-session are deferred to
later milestones.

## Status

Landed in #3520 (initial scaffold):

- Cargo workspace + Tauri 2 `src-tauri` (Rust 2024, LGPL-3.0).
- Svelte 5 + Vite SPA frontend.
- Public ACP registry parser at `src-tauri/src/acp/registry.rs` matching
  upstream `agent.schema.json`, plus 11 tests against a pinned snapshot
  of the live registry.
- SQLite migration runner at `src-tauri/src/db/mod.rs` with `PRAGMA
  user_version` tracking, FK cascades, WAL, and 6 tests.
- 3-OS CI (ubuntu/macos/windows) running `cargo fmt --check`, `cargo
  build`, `cargo test`, `cargo clippy -D warnings`, then `pnpm install
  --frozen-lockfile`, `pnpm check`, `pnpm build`.

Next, in roughly this order:

1. **DB schema simplification** — drop `issues`, `blocking_edges`,
   `sessions`; keep `agents` and `config_kv`.
2. **Custom-agent CRUD** — `list_agents`, `add_custom_agent`,
   `uninstall_agent` against the `agents` table.
3. **Config** — `get_config` / `set_config` against `config_kv`
   (repo path + default agent).
4. **Registry fetch + cache** — extend `acp/registry.rs` with
   `reqwest` fetch, ETag revalidation, and persistence at
   `~/.brokk/foreman/registry-cache.json`.
5. **ACP client core** (`acp/launcher.rs`, `acp/client.rs`) — spawn
   binary/npx/uvx agents, perform `initialize` + `session/new`, stream
   `SessionUpdate` events to the frontend over Tauri events.
6. **Session UI** — `Session.svelte` with prompt input, streaming
   `SessionUpdate` list, permission modal.
7. **Install flow** (`acp/installer.rs`) — download + extract binary
   distributions; verify `npx` / `uvx` on PATH.
8. **Auth handling** (`acp/auth.rs`) — Agent Auth (delegated) and
   Terminal Auth (out-of-app spawn via `tauri-plugin-shell`).

## Context

A Tauri desktop app, `brokk-foreman`, that acts as a **pure ACP client**
against a local git repository. Users pick an ACP-compliant agent —
from the official ACP registry or supplied as a custom install — and
run one session at a time against a configured repo root. Streaming
`SessionUpdate` events are surfaced in the UI; permission requests pop
a modal.

The app does **no** agentic logic of its own. All LLM work runs through
ACP sessions whose backend agent is user-chosen and swappable (Brokk,
Claude Agent, Gemini, Codex, Goose, etc.). This decouples brokk-foreman
from any one vendor's stack and aligns with the ACP industry direction:
Zed and JetBrains shipped registry support in early 2026, and
brokk-foreman becomes a third client of the same registry.

## Non-goals (v1)

- No coupling to Brokk's Java backend (`BrokkExternalMcpServer`, Java
  agents, etc.). brokk-foreman runs without brokk installed.
- No issue providers (GitHub/Jira). Deferred.
- No kanban or workflow UI. Deferred.
- No LLM-driven enrichment or blocking-graph analysis. Deferred.
- No worktree-per-session. The session runs against the configured repo
  root directly. **Worktrees are the next milestone after v1 ships.**
- No multiple concurrent sessions. One at a time.
- No session persistence across app restarts.
- No multi-repo support. One repo path per app instance.
- No agent-side credential management beyond triggering each agent's own
  auth flow (the agent stores its own keys).

## Architecture summary

```
+-----------------------------------------------------------+
|                     Tauri main (Rust)                     |
|  +------------+  +-----------+  +-----------+             |
|  | ACP client |  | Registry  |  | Agent     |             |
|  | (one       |  | client    |  | installer |             |
|  |  session)  |  | + cache   |  |           |             |
|  +------+-----+  +-----+-----+  +-----+-----+             |
|         |              |              |                   |
|  +------v-------- SQLite (state.db) --v---------+         |
|         |    (agents + config_kv only)                    |
|  +------v---- IPC (tauri::Event/Command) --------+        |
|         |                                                  |
|  +------v--- Svelte frontend (Vite) -------------+         |
+-----------------------------------------------------------+
            |                          |
            v (spawns one at a time)   v (HTTPS)
   +------------------+        +-------------------+
   | ACP agent proc   |        | cdn.agentclient.. |
   | (stdio JSON-RPC, |        +-------------------+
   |  kill_on_drop)   |
   +------------------+
```

Process boundaries:

- **Tauri main** is one Rust process; it's the only one talking to disk,
  the registry CDN, and any ACP agent.
- **Svelte frontend** runs in the system webview. It only talks to the
  Rust backend via Tauri commands and events.
- **ACP agent** runs as a child process of the Rust main, one at a time.

## Cargo layout

`brokk-foreman/` lives at the brokk monorepo root, parallel to
`brokk-acp-rust/`. It is its own Cargo workspace; brokk-acp-rust and
brokk-foreman do not share runtime code (one is an ACP server, the other
a client). They could share a small types crate later if needed.

```
brokk-foreman/
  Cargo.toml                  # workspace root
  src-tauri/
    Cargo.toml
    tauri.conf.json
    build.rs
    src/
      main.rs                 # delegates to lib::run()
      lib.rs                  # Tauri setup, command registration, DB init
      commands.rs             # #[tauri::command] handlers
      events.rs               # event payload types
      config.rs               # data dir + config_kv helpers
      acp/
        mod.rs
        registry.rs           # fetch + cache + parse registry.json
        installer.rs          # binary download/extract; npx/uvx verify
        launcher.rs           # spawn agent process
        client.rs             # AcpSession wrapping agent_client_protocol
        auth.rs               # Agent Auth + Terminal Auth
      db/
        mod.rs                # rusqlite, migrations, queries
        schema.sql            # baseline schema (agents + config_kv)
  src/                        # Svelte frontend
    App.svelte                # router shell
    main.ts
    routes/
      Settings.svelte         # repo path + default agent
      Agents.svelte           # registry browser + custom-agent form
      Session.svelte          # prompt input + streaming updates
    lib/
      api.ts                  # typed Tauri invoke wrappers
      events.ts               # Tauri event subscriptions
      stores/
        agents.ts
        session.ts
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
- `reqwest` (rustls) for the registry CDN
- `rusqlite` (bundled) for state
- `directories`, `which`
- `flate2`, `tar`, `zip` for binary distribution archive extraction
- `tracing`, `tracing-subscriber`
- `thiserror`, `anyhow`, `uuid`, `url`

Frontend (`package.json`):

- `svelte` 5, `vite`
- `@tauri-apps/api`, `@tauri-apps/plugin-shell`

## Data model (SQLite, `~/.brokk/foreman/state.db`)

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

CREATE TABLE config_kv (
  key             TEXT PRIMARY KEY,
  value           TEXT NOT NULL
);
```

The migration runner opens the DB with `PRAGMA foreign_keys = ON` and
`PRAGMA journal_mode = WAL` on every connection. v1 has no FK
relationships among the remaining tables, but the migration runner keeps
the pragma for forward compatibility with worktrees / sessions tables.

## ACP registry integration

- Registry URL: `https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`
- Schema URL: `https://cdn.agentclientprotocol.com/registry/v1/latest/agent.schema.json`
- Format: `{ "version": "1.0.0", "agents": [...] }`. CDN sets
  `Cache-Control: max-age=300`.
- `acp/registry.rs` parses with strict `deny_unknown_fields` on the
  per-agent types so any upstream schema drift breaks tests instead of
  silently dropping fields.
- A follow-up extension fetches with `reqwest`, validates `version ==
  "1.0.0"`, persists raw JSON at `~/.brokk/foreman/registry-cache.json`,
  and respects the CDN cache TTL plus `If-None-Match`/`ETag`
  revalidation.
- `RegistryAgent` mirrors the upstream schema: `id`, `name`, `version`,
  `description`, `repository`, `website`, `authors`, `license`, `icon`,
  `distribution` with three variants (`binary` / `npx` / `uvx`).
- `Platform::current()` resolves the running host to one of the
  registry's six published targets (`darwin-aarch64`, `darwin-x86_64`,
  `linux-aarch64`, `linux-x86_64`, `windows-aarch64`, `windows-x86_64`).
  `RegistryAgent::supported_on_host()` filters the registry browser to
  "things you could install today".

### Custom agents

A custom agent is the same shape as a registry entry but tagged
`source: "custom"`, with a free-form `cmd` / `args` / `env` instead of
a registry distribution block. Stored in the `agents` table with
`source='custom'`. UI: an "Add agent" form with name + binary path +
args + env editor; this gets stored as a synthetic single-target binary
distribution keyed to the current platform (no archive download).

### Install flow

- For `npx` and `uvx`: nothing to install ahead of time; we just verify
  `npx` (or `uvx`) is on PATH at activation time via the `which` crate.
- For `binary`: download `archive` to
  `~/.brokk/foreman/agents/<id>/<version>.<ext>`, extract to
  `~/.brokk/foreman/agents/<id>/<version>/`, store the resulting
  absolute path of `cmd` in `agents.installed_path`. Reject
  `.dmg/.pkg/.deb/.rpm/.msi/.appimage` per the registry spec.

### Auth handling

When `session/new` returns `AUTH_REQUIRED`, the response declares either:

- `type: "agent"` — Agent Auth: the agent owns the OAuth flow (browser +
  local callback). brokk-foreman just calls `authenticate` per the ACP
  spec and waits.
- `type: "terminal"` — Terminal Auth: relaunch the agent with the
  response's `args`/`env` *replacing* the defaults, attached to a TTY.
  v1 spawns Terminal Auth in the user's default terminal app via
  `tauri-plugin-shell` and waits for the agent to exit before resuming;
  in-app pty is a v2 enhancement.

## ACP client surface (`acp/client.rs`, `acp/launcher.rs`)

The `agent-client-protocol` crate handles the wire protocol. Our wrapper
provides:

- `AcpSession::start(agent: &AgentRecord, cwd: &Path) -> AcpSession` —
  spawns the configured binary/npx/uvx with `cmd + args + env`, wires
  stdin/stdout to the protocol's `Connection`, performs `initialize`,
  then calls `session/new` with `cwd` set to the configured repo path.
- `AcpSession::prompt(text)` — sends a `prompt` request; returns once
  the turn completes. `SessionUpdate` events are forwarded to the
  frontend during the turn via Tauri events.
- `AcpSession::cancel()` — issues `cancel`.
- `AcpSession::shutdown()` — drops the connection; `kill_on_drop` on
  the spawned `Child` ensures the subprocess goes away.

Streaming: `SessionUpdate` events are forwarded to the frontend as Tauri
events on channel `acp:update`. The frontend renders them in
`Session.svelte`. Permission requests (`requestPermission`) are
surfaced as a modal in the session view; the user accepts/rejects and
the response is forwarded back through the ACP connection. **No
auto-accept** in v1.

Subprocess pattern: model the spawn after
`brokk-acp-rust/src/bifrost_client.rs` — `tokio::process::Command` with
`stdin/stdout/stderr` piped, `kill_on_drop(true)`, `BufReader`-wrapped
`ChildStdout`. The wire framing is handled by
`agent_client_protocol::ConnectionTo`; we don't roll our own JSON-RPC.

## Frontend layout (Svelte 5)

- `/settings` (default on first run): repo path + default agent.
- `/agents`: registry browser (table from cached `registry.json`),
  enable/disable, install/uninstall, "Add custom agent" form. Status
  indicator per agent (installed, auth pending, error).
- `/session`: pick an agent (default pre-selected); prompt input
  textarea; streaming `SessionUpdate` list; permission modal; Cancel +
  Stop buttons.

State stores (`agents.ts`, `session.ts`) are each backed by a Tauri
command for initial load and Tauri events for updates.

## Tauri commands surfaced to the frontend (`commands.rs`)

```
list_agents()                     -> Vec<AgentRecord>
fetch_registry(force_refresh)     -> Vec<RegistryAgent>
install_agent(registry_id)        -> AgentRecord
uninstall_agent(id)               -> ()
add_custom_agent(spec)            -> AgentRecord
get_config()                      -> Config
set_config(config)                -> ()
start_session(agent_id)           -> SessionRecord
send_prompt(text)                 -> ()       // emits acp:update events
respond_permission(req_id, accept)-> ()
stop_session()                    -> ()
```

## Risks and open issues

1. **Per-agent quirks at launch.** Some `npx` agents are slow on first
   run (cold install). Some binary releases mismatch `cmd` against the
   actual extracted layout. We need a clear timeout + error UI per
   agent; v1 surfaces stdout/stderr in the agents page on failure.
2. **Authentication UX divergence.** Agent Auth is hands-off, but
   Terminal Auth needs a real pty with the user staring at the screen.
   Embedding a TTY view in the webview is non-trivial; v1 spawns
   Terminal Auth in the user's default terminal app and waits for the
   agent to exit before resuming.
3. **Structured-output reliability across agents** is a v2 concern only
   (no enrichment in v1).

## Verification (end-to-end, once the wrapper and UI land)

1. `cd brokk-foreman && cargo tauri dev` launches the dev app against a
   Vite dev server.
2. Open the agents page; the registry loads (verify network tab shows
   the CDN URL); install `claude-acp` (npx) or a custom local binary.
3. Configure repo path in settings.
4. From `/session`, pick an agent and click Start. Verify the agent
   process appears in `ps` and Rust logs show `initialize` +
   `session/new` succeeding.
5. Send a prompt; observe streaming `SessionUpdate` events in the page.
6. Trigger a tool-use turn (e.g. "list files in this repo"); verify the
   permission modal appears and accepting forwards through ACP.
7. Click Cancel mid-turn; observe clean cancellation.
8. Click Stop; verify clean shutdown of the child process (`ps -p`
   shows it gone).
9. Repeat with an `npx`-distributed registry agent.
10. Tests: `cargo test` for the registry parser, the migration runner,
    and any added integration tests for the ACP client wrapper.

## Scope cut summary for v1 ship

Keep: agent picker (registry browser + custom installs), single
session against a configured repo path, prompt + streaming updates,
permission modal, Agent Auth + (out-of-app) Terminal Auth.

Cut from v1 (deferred to later milestones): worktree-per-session
(next), GitHub/Jira issue providers, kanban UI, enrichment-on-import,
blocking-graph analysis, in-app pty for Terminal Auth, multi-repo,
configurable kanban columns, mobile/web build, auto-resume of crashed
sessions across restarts, multiple concurrent sessions.
