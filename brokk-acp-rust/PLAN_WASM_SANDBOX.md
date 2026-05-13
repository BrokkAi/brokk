# Plan: WASM/WASI sandbox for brokk-acp-rust

## Motivation

`brokk-acp-rust` ships as an LLM-driven agent that runs whatever the model
decides to run. In a hardened deployment we can lean on `bwrap` /
`sandbox-exec` (see `src/tools/sandbox.rs`) and on container-level cgroups,
seccomp, and capability drops. But the target deployment we cannot solve
today is **a Docker image we do not control**: stripped-down base, no
`bwrap`, no seccomp profile we can install, env vars we did not set, an
ambient process tree that may include other workloads. In that posture the
agent process can read `~/.ssh/`, exfiltrate `OPENAI_API_KEY`, POST to
`pastebin.com`, write `/etc/cron.d/`, and the OS will let it.

The goal of this plan is to make the LLM-driven code paths execute under a
**WASM capability sandbox** so that, regardless of how privileged the host
container is, the parts of `brokk-acp-rust` that act on model output cannot
do any of those things. The non-WASM-able pieces (interactive OAuth, shell
exec when explicitly enabled, ACP stdio) stay in a small native host
process that the operator can audit.

## Threat model

In-scope adversary: a malicious or compromised LLM response that causes the
agent to try to do something it should not. This includes:

- Reading credentials from disk (`~/.ssh`, `~/.aws`, `.env`, `~/.codex/auth.json`).
- Reading credentials from process env (`OPENAI_API_KEY`, `AWS_*`, `GITHUB_TOKEN`, `BROKK_*`).
- Writing files outside the project workspace (`/etc`, `~/.bashrc`, cron dirs).
- Exfiltrating any of the above to attacker-controlled URLs.
- Reading the host process's memory (token cache, OAuth refresh token).

Out of scope: a compromised native host binary, a compromised wasmtime,
kernel exploits, side channels (timing, cache), DoS via wedging the model.
A compromised host is game over and WASM is not a defense against that —
this plan reduces the *trusted surface* of the host to a few hundred lines
that an operator can review.

Also out of scope (already covered): `runShellCommand` sandboxing in
trusted-host deployments. There is an existing PR that introduces a
"shell off" mode; this plan assumes shell is either disabled or runs in
the native host, and does not duplicate that work.

## Architecture: host / guest split

```
+----------------------------------------------------+
| brokk-acp host binary (native, audited, small)     |
|                                                    |
|   ACP stdio I/O  (agent-client-protocol crate)     |
|   OAuth flows    (codex_auth.rs, openrouter_auth.rs)|
|   Credential store (in host memory only)           |
|   Shell exec     (deferred to other PR)            |
|   Network policy state (Arc<RwLock<NetPolicy>>)    |
|                                                    |
|   wasmtime::Engine + wasi:http custom impl         |
|       |                                            |
|       v                                            |
|   +------------------------------------------+     |
|   | brokk-acp-core.wasm (guest module)       |     |
|   |                                          |     |
|   |   LLM agentic loop (tool_loop, agent)    |     |
|   |   Built-in tools: readFile, writeFile,   |     |
|   |     listDirectory, searchFileContents,   |     |
|   |     think                                |     |
|   |   Skills + AGENTS.md parsing             |     |
|   |   Bifrost client glue (if compatible)    |     |
|   |   LLM HTTP client over wasi:http         |     |
|   +------------------------------------------+     |
+----------------------------------------------------+
```

Boundary contract (WIT):

- **Host -> guest** (exports the guest implements): `run-session(session-id, prompt, tools-config) -> result`, plus streaming via host imports.
- **Guest -> host** (imports the guest can call): `emit-acp-update`, `request-permission`, `oauth-token-for(provider)`, `shell-exec` (returns "disabled" in WASM-locked mode), `request-network-approval(host, port)`.

The guest never receives the raw OAuth tokens at module init. The host
mints a short-lived bearer per request and injects it into the
`Authorization` header at the wasi-http layer (see Network policy below)
so the guest never sees the secret as a string at all.

## Capability matrix

| Capability | What the guest can do | Mechanism | Enforcement |
|---|---|---|---|
| Filesystem | Read/write under preopened roots only | `wasmtime::WasiCtxBuilder::preopened_dir(...)` per session, typically `/workspace -> $CWD` | WASI: opening any non-preopened path returns `errno::ACCES`. No syscall escape. |
| Env vars | See vars host explicitly passes | `WasiCtxBuilder::env(...)` | Default empty. Whitelist hard-coded in host (e.g. `LANG`, `TZ`). No secrets passed. |
| Network | Outbound HTTP only via `wasi:http`, per-request approval | Custom `WasiHttpView::send_request` consulting `Arc<RwLock<NetPolicy>>` | See Network policy section. |
| Process spawn | Nothing | WASI has no `exec`/`fork`/`spawn` | Structural: no import to call. |
| Sockets (raw) | Nothing | WASI sockets not added to the linker | Structural. |
| Memory | Own linear memory only | wasmtime engine guarantee | Hardware-bounded. |
| Clock / random | Yes (monotonic + wall) | Default WASI clocks and `random_get` | Harmless. |

Two key properties of this matrix:

1. Every row is **structural**: removing the import means the syscall is
   not even an addressable function in the module's import table. We are
   not relying on runtime checks against a regex on paths or hosts; the
   wasmtime ABI itself fences these off.
2. There is **no env-var inheritance**. Today the agent process inherits
   the parent shell's full environment. After this plan, the guest sees
   only what the host hands it explicitly. Even if the LLM tells the
   agent to call `std::env::var("OPENAI_API_KEY")`, it gets `Err(NotPresent)`.

## Network policy

This is the most subtle part because it has to support a runtime-mutable
allowlist driven by user approval. Validated against wasmtime ~31 (May
2026): `WasiHttpView::send_request` is called `&mut self` per outbound
request, has access to `request.uri()`, and can return
`HttpResult::Err(ErrorCode::HttpRequestDenied)` which the guest's
`wasi-http` bindings surface as a request error. There is no built-in
declarative allowlist (tracked at `bytecodealliance/wasmtime#10527`) —
we own the policy code, which we want anyway.

### Policy structure

```rust
pub struct NetPolicy {
    /// Always deny, checked first. Sub-domain match.
    /// Sourced from CLI `--deny-host`, env BROKK_ACP_DENY_HOSTS, config file.
    blacklist: HashSet<HostPort>,

    /// Allowed without prompting. Sourced from same channels as blacklist
    /// plus "essential" defaults (e.g. the configured LLM provider).
    startup_allow: HashSet<HostPort>,

    /// Allowed only for the current session, populated by user approval.
    /// Wiped on session end.
    runtime_allow: HashSet<HostPort>,

    /// While `Some`, the next matching request is held until decision is
    /// recorded (decision -> add to runtime_allow OR return denied).
    /// Prevents stampedes of identical approval prompts.
    pending: HashMap<HostPort, oneshot::Sender<Decision>>,

    /// What to do when a request hits none of the above. Default `Ask`.
    /// `Deny` for fully air-gapped operation.
    default_action: DefaultAction,
}

enum DefaultAction { Deny, Ask }
```

### Decision order, per outbound request

1. Extract `host:port` from `request.uri()`.
2. If `host:port` matches `blacklist` -> return `HttpRequestDenied`.
   Blacklist beats allowlist, always. Reason: an operator who deny-listed
   `metadata.google.internal` does not want a later config typo to
   override it.
3. If `host:port` matches `startup_allow` or `runtime_allow` -> proceed.
4. If `default_action == Deny` -> return `HttpRequestDenied` with a
   message naming the host so the guest's error includes it.
5. If `default_action == Ask`:
   - If there is already a `pending` slot for this host -> wait on the
     existing channel (de-duplicate the prompt).
   - Otherwise, send an ACP `request_permission` to the client with the
     host:port and a description ("Agent is trying to reach X").
   - Wait for the user's decision (with a timeout, configurable).
   - On approve: insert into `runtime_allow`, proceed.
   - On deny: return `HttpRequestDenied`.

### Configuration surface

- **Startup**: `--allow-host=api.openai.com:443 --deny-host=pastebin.com:443`,
  repeatable. Or `BROKK_ACP_ALLOW_HOSTS` / `BROKK_ACP_DENY_HOSTS` (comma-
  separated). Or a `netpolicy.toml` in `~/.config/brokk-acp/` with the
  same lists plus the `default_action` knob.
- **Implicit allow**: the configured LLM provider's base URL is added to
  `startup_allow` automatically so the agent works out of the box. This
  is the only implicit entry.
- **Runtime mutation**: only via approval. There is intentionally no
  "agent can request to permanently allow X" path — runtime allows are
  session-scoped. Permanence is an operator decision: edit
  `netpolicy.toml` and restart.

### What the guest sees on denial

`HttpRequestDenied` surfaces as a request error in the guest's HTTP
client. The LLM loop's `tools/llm_client` will translate it into a
`ToolStatus::RequestError` with a message that names the host, so the
model can adjust ("I tried to reach pastebin.com but it is blocked") and
the user sees it in the ACP tool-call card.

## Implementation roadmap

Phase ordering optimized so each phase is independently testable and
reverts cleanly if it fails.

### Phase 0 — Spike (estimate: 1 day)

Goal: prove the embedding path works in this codebase, not in a toy
crate. Pick the smallest tool that has zero environmental coupling:
`tools/filesystem.rs::read_file_tool` is the candidate but even better is
`agents_md.rs` (pure parsing of a string).

1. Add a `brokk-acp-core` library crate (no main, no tokio runtime
   assumptions yet) and move `agents_md.rs` into it.
2. Add a `brokk-acp-wasm` build target that compiles
   `brokk-acp-core` plus a thin component wrapper to `wasm32-wasip2`.
3. In the existing `src/main.rs` host, load the produced `.wasm` via
   `wasmtime` and call its `parse_agents_md` export.
4. Wire it into one code path (the discovery of `AGENTS.md`) behind a
   feature flag `wasm-guest` so we can flip it off if anything regresses.

Exit criterion: `cargo test` passes, the discovery path still works end-
to-end in Zed, and `wasm-guest=true` actually loads and executes the
module. If this phase reveals that the toolchain churn is unacceptable
(e.g. wasi-p2 component model immature in our pinned wasmtime), we stop
here and document the blocker.

### Phase 1 — WIT contract and crate split (estimate: 2 days)

1. Define `wit/brokk-acp.wit` describing host imports and guest exports.
   Initial scope: `oauth-token`, `request-network-approval`, `emit-acp-update`,
   plus exports for `run-prompt(session-id, prompt) -> stream`.
2. Split the source tree:
   - `brokk-acp-host` (binary) — current `main.rs`, `agent.rs` ACP wiring,
     OAuth flows, wasmtime embedder, NetPolicy.
   - `brokk-acp-core` (library, `crate-type=cdylib` for wasm) — the
     tool loop, llm_client, tools, skills.
   - Shared types crate `brokk-acp-types` for what crosses the WIT
     boundary (kept thin, mostly enums).
3. Generate host and guest bindings (`wasmtime::component::bindgen!` and
   `wit-bindgen` respectively).
4. Keep the existing native binary working in parallel with a feature
   flag. Both build paths land in CI.

### Phase 2 — Network: migrate off `reqwest` (estimate: 3 days)

This is the biggest single piece of work. `reqwest`'s native backend uses
`hyper`/`tokio::net` which has no WASI port. Two options, and we should
pick now:

- **Option A**: `waki` crate — minimal `wasi:http`-only client with a
  reqwest-like API. Smallest delta to call sites.
- **Option B**: keep `reqwest` only when compiled for the host, abstract
  the LLM client behind a trait, provide a `wasi:http`-backed impl for
  the guest.

Recommend Option A on call-site count. The call sites are
[llm_client.rs](brokk-acp-rust/src/llm_client.rs) and
[bifrost_client.rs](brokk-acp-rust/src/bifrost_client.rs); both use
streaming bodies which `waki` supports via the standard
`incoming-body.stream()`.

Host side: implement custom `WasiHttpView::send_request` enforcing
`NetPolicy`. The default `wasmtime_wasi_http` implementation actually
dials the network, so we wrap it: check policy, then delegate to the
default impl on allow.

SSE streaming: `wasi:http` exposes the body as a `stream` resource; the
guest reads chunks. The current SSE parsing logic in `llm_client.rs`
doesn't depend on reqwest internals, just on a byte stream, so the
parser code moves verbatim.

### Phase 3 — Move filesystem tools into the guest (estimate: 1 day)

`tools/filesystem.rs` is mostly `tokio::fs::read_to_string` etc. The WASI
preview-2 `wasi:filesystem` works via the standard `std::fs` once the
guest's preopens are set up. The tool functions move into `brokk-acp-core`
unchanged. The host's `WasiCtxBuilder` does `preopened_dir(project_root,
"/workspace")` per session.

One thing to fix: today filesystem tools accept any absolute path. In the
guest, paths must be rewritten relative to the preopen, and absolute
paths outside the workspace must be rejected at the tool entry rather
than relying on WASI to error out (so the LLM gets a friendly message).
This is a small `path_translate` helper in `brokk-acp-core::tools`.

### Phase 4 — Move the tool loop and agent into the guest (estimate: 3 days)

The tool loop (`tool_loop.rs`, `tool_loop/`, `agent.rs`, `multi_backend.rs`,
`session.rs`) is plain Rust async logic. The trick is tokio: the WASI
async story uses `pollable` resources, and `tokio::time` etc. work via
the `tokio-wasi` shim (`tokio` upstream now supports wasi for the
`current_thread` runtime with the `wasi` feature). The plan:

- Guest uses `#[tokio::main(flavor = "current_thread")]` everywhere.
- Remove any `tokio::spawn` that requires multi-thread (none should
  remain after audit; the agent loop is single-task).
- ACP emissions go through the host import `emit-acp-update` rather than
  by holding a `Notifier<>` directly. Host translates back into
  `SessionNotification` and writes to stdout.

### Phase 5 — Skills, Bifrost, polish (estimate: 2 days)

- `skills.rs` and `agents_md.rs`: pure parsing, moves cleanly.
- `bifrost_client.rs`: this calls a *Java* analyzer over HTTP. The HTTP
  goes through the same wasi:http policy path. `localhost:<port>` should
  be added to `startup_allow` automatically when bifrost is enabled.
- `codex_client.rs`, `codex_auth.rs`, `openrouter_auth.rs`,
  `discovery.rs`: **stay in the host**. These interact with files
  outside the workspace (`~/.codex/auth.json`, `~/.config/openrouter/`)
  and open loopback HTTP for OAuth callbacks. Their results (a bearer
  token) are passed to the guest via the `oauth-token` import.

### Phase 6 — CLI surface and packaging (estimate: 1 day)

- New flags: `--sandbox=wasm|none` (default `wasm` once stable),
  `--allow-host`, `--deny-host`, `--netpolicy-file`,
  `--network-default=ask|deny`.
- Single binary: the `.wasm` is included via `include_bytes!` so there is
  no separate file to ship and no path-resolution complication in
  Docker. Build size: `brokk-acp-core.wasm` should be a few MB; we'll
  monitor.
- Document the threat model and what the sandbox does / does not
  protect, in README.md, with a section "What happens in untrusted
  containers".

### Phase 7 — Tests (interleaved with each phase, called out here)

- **Capability tests** (Phase 0/1): a tiny WASI module that tries to open
  `/etc/passwd`, read `OPENAI_API_KEY`, exec `sh`. Asserts each fails
  the expected way. Lives in `brokk-acp-host/tests/sandbox_caps.rs`.
- **Network policy tests** (Phase 2): mock guest that requests
  `pastebin.com` (in blacklist), `api.openai.com` (in allowlist),
  `unknown.example.com` (triggers ask). Asserts each takes the right
  path. Use a fake ACP client that auto-approves or auto-denies to
  drive the approval flow.
- **End-to-end** (Phase 5+): run `brokk-acp --sandbox=wasm` against Zed
  with each scenario from the existing PLANS.md verification section
  (read file, write file, modify file, run shell, search symbols). All
  should still work; shell respects the existing PR's mode.

## Files to touch

| File | Change |
|---|---|
| `Cargo.toml` (workspace) | Add `brokk-acp-host`, `brokk-acp-core`, `brokk-acp-types` members |
| `brokk-acp-host/Cargo.toml` | New crate. Deps: `wasmtime`, `wasmtime-wasi`, `wasmtime-wasi-http`, current ACP/OAuth deps |
| `brokk-acp-core/Cargo.toml` | New crate. Deps: `waki` (or chosen wasi:http client), `serde`, `serde_json`, ACP types (no-default-features where needed), `tokio` with `wasi` feature |
| `wit/brokk-acp.wit` | New. Host imports + guest exports |
| `src/main.rs` | Becomes thin: ACP loop + wasmtime embedder |
| `src/agent.rs` | Splits: ACP-facing half stays in host; LLM loop half moves to core |
| `src/llm_client.rs`, `src/bifrost_client.rs` | Move to core, rewrite HTTP on top of `waki` |
| `src/tools/filesystem.rs`, `src/tools/mod.rs` (built-in tools) | Move to core, add `path_translate` |
| `src/tools/shell.rs`, `src/tools/sandbox.rs` | Stay in host (or remain dormant if shell disabled in WASM mode) |
| `src/codex_auth.rs`, `src/openrouter_auth.rs`, `src/discovery.rs` | Stay in host |
| `src/skills.rs`, `src/agents_md.rs` | Move to core |
| `src/session.rs`, `src/tool_loop.rs`, `src/tool_loop/`, `src/multi_backend.rs` | Move to core |
| `brokk-acp-host/src/netpolicy.rs` | New: `NetPolicy`, `WasiHttpView` impl |
| `brokk-acp-host/tests/sandbox_caps.rs` | New: capability assertions |
| `brokk-acp-host/tests/netpolicy.rs` | New: approval-flow tests |
| `README.md` | New section on sandbox and what it covers |
| `brokk-code/brokk_code/rust_acp_install.py` | Build script picks up the new binary name + bundles the .wasm if not `include_bytes!`'d |

## Risks and known limitations

- **Tokio on WASI**: only `current_thread` flavor is supported. Any
  multi-threaded assumption in the existing async code becomes a
  compile error in the guest. Mitigation: Phase 4 includes an explicit
  audit; if anything resists single-threading, we either fix it or pull
  it back into the host across the WIT boundary.
- **wasi-http maturity**: this is wasi:p2. Some HTTP semantics (cookies,
  redirects, retries) are simpler than `reqwest`. We do explicit
  redirect handling in `llm_client.rs` already; cookies (used by
  `reqwest` for the OAuth flow) stay in the host. Verify chunked
  transfer encoding works for SSE — `waki`'s body stream should be
  fine but Phase 2 has an explicit test.
- **Build complexity**: cross-compiling to `wasm32-wasip2`, then
  componentizing, is a multi-step process. We commit to a
  reproducible build via `cargo xtask wasm-build` plus include_bytes! so
  consumers don't need a wasm toolchain.
- **Bifrost incompatibility**: if `brokk_analyzer` pulls in things that
  cannot compile to wasi (tree-sitter native bindings would be the
  obvious risk), Bifrost integration may have to stay in the host as a
  side car the guest talks to over the same wasi:http path. Document
  the decision when it surfaces in Phase 5.
- **No host kernel-level enforcement**: WASM protects against the LLM
  driving the agent into bad behavior. It does not protect against a
  compromised native binary. Standard supply-chain hygiene (deps
  audit, no single-maintainer credential crates — already practiced
  here per `Cargo.toml` comments) is still the floor.

## What this plan explicitly does not solve

- `runShellCommand` running unsandboxed when the operator enables it.
  That belongs to the existing shell-mode PR. When shell is enabled in
  the host, the host process is the trust boundary for it, exactly as
  today.
- DoS / cost attacks (model burns tokens, makes 10k allowed requests).
  Out of scope; rate-limiting is a separate concern handled at the
  provider layer.
- A malicious wasmtime build. We pin a version, audit deps, ship from
  source.

## Open decisions

1. **wasi:http client**: `waki` vs custom-on-top-of-`wasi-http` raw
   bindings. Default to `waki` unless Phase 2 spike reveals a blocker.
2. **NetPolicy storage format**: TOML in `~/.config/brokk-acp/netpolicy.toml`
   vs reusing the existing brokk config dir. Default to a
   brokk-acp-specific file so an operator can review just this surface.
3. **Default `default_action`**: `Ask` (interactive) or `Deny` (strict).
   Recommend `Ask` for desktop usage, `Deny` when `--non-interactive` is
   set. The host can switch automatically based on whether stdin is a
   tty.
4. **Whether to expose the network policy via ACP MCP tools** so an
   external auditor agent can inspect it at runtime. Out of scope for
   v1.

## Phase ordering rationale

Phase 0 (spike) and Phase 1 (WIT/crate split) deliberately come before
the heavy lifting (Phase 2: reqwest migration) because they let us bail
cheap if the toolchain story is bad. The most expensive irreversible
work is Phase 2; we should not start it until Phase 0/1 are green.

Phase 7 (tests) is listed last for ordering but the capability tests in
Phase 1 are the bar for whether the sandbox is real. Until those pass,
nothing else matters.
