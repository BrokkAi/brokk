# Plan: Tool Calling for the Rust ACP server

## Current state

The Rust server is a simple chat proxy: it receives a prompt, sends it to the LLM, and streams the response. No agentic loop, no tool calling, no filesystem interaction.

## Major asset: Bifrost (brokk_analyzer)

The `brokk_analyzer` crate (repo `brokkai/bifrost`) is a native Rust code analyzer based on tree-sitter. It already provides a `SearchToolsService` with these ready-to-use tools:

| Bifrost tool | Description |
|---|---|
| `search_symbols` | Search for indexed symbols in the workspace |
| `get_symbol_locations` | Locate symbols in files |
| `get_symbol_summaries` | Concise symbol summaries |
| `get_symbol_sources` | Source code of symbols |
| `get_file_summaries` | Concise file summaries |
| `summarize_symbols` | Compact recursive summaries |
| `skim_files` | Quick overview of a file's symbols |
| `most_relevant_files` | Files related via git history and imports |
| `refresh` | Refresh the analyzer's index |

Bifrost supports: Java, JavaScript, TypeScript, Rust, Go, Python, C++, C#, PHP, Scala.

**Integration is direct**: add `brokk_analyzer` as a dependency, instantiate `SearchToolsService::new(cwd)`, and call `service.call_tool_value(name, args)`. No subprocess, no MCP, no serialization -- it's a native Rust function call.

## Goal

Allow the LLM to call tools via the OpenAI tool calling protocol, with an agentic loop that iterates until the LLM produces a final response. Code intelligence tools come from Bifrost; filesystem/shell tools are implemented in the ACP server.

---

## Phase 1: Tool calling infrastructure in the LLM client

### 1.1 Extend ChatMessage and ChatCompletionRequest (llm_client.rs)

```rust
#[derive(Serialize, Deserialize)]
struct ChatMessage {
    role: String,                              // "system", "user", "assistant", "tool"
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_calls: Option<Vec<ToolCall>>,         // assistant responses with tool calls
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_call_id: Option<String>,              // for role=tool messages
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,                      // tool name for role=tool
}

#[derive(Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<ToolDefinition>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<String>,               // "auto" | "none" | "required"
}

#[derive(Serialize, Deserialize, Clone)]
struct ToolDefinition {
    r#type: String,          // "function"
    function: FunctionDef,
}

#[derive(Serialize, Deserialize, Clone)]
struct FunctionDef {
    name: String,
    description: String,
    parameters: serde_json::Value,
}

#[derive(Serialize, Deserialize, Clone)]
struct ToolCall {
    id: String,
    r#type: String,          // "function"
    function: FunctionCall,
}

#[derive(Serialize, Deserialize, Clone)]
struct FunctionCall {
    name: String,
    arguments: String,       // JSON serialized as a string
}
```

### 1.2 Parse tool calls in the SSE stream

SSE chunks may contain `tool_calls` in fragments:

```json
{"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_abc", "function": {"name": "search_symbols", "arguments": ""}}]}}]}
{"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"patterns"}}]}}]}
{"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "\": [\"main\"]}"}}]}}]}
```

Fragments must be accumulated by index. `stream_chat` should return:

```rust
enum LlmResponse {
    Text(String),
    ToolCalls {
        text: String,                           // text emitted before the tool calls
        calls: Vec<ToolCall>,
    },
}
```

File to modify: `llm_client.rs`

---

## Phase 2: ToolRegistry and Bifrost integration

### 2.1 Unified ToolRegistry (tools/mod.rs)

```rust
struct ToolRegistry {
    /// Bifrost tools (code intelligence) -- direct calls to SearchToolsService
    bifrost: SearchToolsService,
    /// Working directory for filesystem tools
    cwd: PathBuf,
}

impl ToolRegistry {
    fn new(cwd: PathBuf) -> Result<Self, String>;

    /// Return all tool definitions in OpenAI format
    fn tool_definitions(&self) -> Vec<ToolDefinition>;

    /// Execute a tool by name + JSON arguments
    fn execute(&mut self, name: &str, args: serde_json::Value) -> ToolResult;
}
```

### 2.2 Bifrost tools (subprocess MCP integration) -- DONE

Implemented as out-of-process MCP rather than direct linking, to avoid having to modify bifrost (its `pyo3 extension-module` feature breaks rlib consumption from a normal Rust binary).

- `src/bifrost_client.rs` spawns `bifrost --server searchtools --root <cwd>` per session, performs the MCP handshake (`initialize` -> `notifications/initialized` -> `tools/list`), and exposes a `call_tool(name, args)` method that proxies to `tools/call` over JSON-RPC stdio.
- `ToolRegistry` holds an `Option<Arc<BifrostClient>>` and advertises bifrost's tools (the 9 search tools) alongside the existing 6 filesystem/shell/think tools whenever the subprocess is up.
- One `ToolRegistry` (and therefore one bifrost subprocess) per ACP session, cached in `SessionStore::registries`. Bifrost's built-in `ProjectChangeWatcher` keeps the analyzer fresh across turns.
- New `--bifrost-binary` CLI flag (also `BROKK_BIFROST_BINARY` env var). Unset = graceful degradation; the 6 existing tools still work.

Local install (one-time): `RUSTFLAGS="-C link-arg=-Wl,-undefined,dynamic_lookup" cargo install --path /path/to/bifrost --bin bifrost`. The RUSTFLAGS dance is required only because bifrost's `extension-module` feature is unconditional on its master branch; once it gates pyo3 behind a feature, the flag goes away.

### 2.3 Additional tools (filesystem + shell)

In addition to the Bifrost tools, the ACP server needs:

| Tool | Description | Implementation |
|---|---|---|
| `readFile` | Read a file | `std::fs::read_to_string` with path validation |
| `writeFile` | Write/create a file | `std::fs::write` with path validation |
| `listDirectory` | List a directory | `std::fs::read_dir` |
| `runShellCommand` | Run a shell command | `tokio::process::Command` |
| `think` | Reflection scratchpad (no-op) | Return the text as-is |

These tools are simple and each takes a few dozen lines to implement.

Files to create:
- `tools/mod.rs` (ToolRegistry, ToolResult, ToolDefinition)
- `tools/filesystem.rs` (readFile, writeFile, listDirectory)
- `tools/shell.rs` (runShellCommand)

---

## Phase 3: The agentic loop

### 3.1 Module tool_loop.rs

Reference: `LutzAgent.java` lines 900-1100.

```
user prompt
    |
    v
[system prompt + history + user prompt]
    |
    v
[Send to LLM with tools=registry.tool_definitions()]
    |
    v
LlmResponse::Text? ----> Final response, exit
LlmResponse::ToolCalls?
    |
    v
For each tool_call:
  [Notify the ACP client: "Searching for symbols..."]
  [registry.execute(name, args)]
  [Append a role=tool message with the result]
    |
    v
[Send back to LLM with the enriched history] ----> loop
```

Safeguards:
- **Turn limit**: max 25 iterations (configurable via `--max-turns`)
- **Cancellation**: check the `CancellationToken` before each LLM and tool call
- **Fatal errors**: stop the loop on a critical internal error
- **Result size**: truncate overly long tool results (>50KB)

### 3.2 Integration in agent.rs

Replace the simple `stream_chat` with `tool_loop::run(llm, registry, messages, cancel, on_event)`.

The `on_event` callback sends ACP notifications to the client:
- Text tokens -> `SessionUpdate::AgentMessageChunk`
- Tool call start -> italic message "_Searching for symbols..._"
- Tool result -> not displayed to the client (internal to the LLM)

---

## Phase 4: Security

### 4.1 Path validation

All filesystem tools (`readFile`, `writeFile`, `listDirectory`) must:
- Resolve the path relative to the session's cwd
- Verify the canonical path stays under the cwd (no `../../etc/passwd`)
- Reject absolute paths unless they are under the cwd

```rust
fn safe_resolve(cwd: &Path, requested: &str) -> Result<PathBuf, String> {
    let resolved = cwd.join(requested).canonicalize()?;
    if !resolved.starts_with(cwd.canonicalize()?) {
        return Err("Path escapes the working directory".into());
    }
    Ok(resolved)
}
```

### 4.2 Shell sandboxing

`runShellCommand`:
- Timeout: 60 seconds by default
- cwd: always the session's cwd
- stdin: closed (no interactive commands)
- Output size: truncate stdout+stderr to 100KB
- No interactive shell: run via `sh -c "..."` or `bash -c "..."`

---

## Phase 5: ACP display

During the agentic loop, send `SessionUpdate::AgentMessageChunk`:

```
_Searching for symbols..._
_Getting file summaries..._
_Running shell command..._
```

Headline mapping (taken from `ToolRegistry.java` and adapted):

```rust
fn headline(tool_name: &str) -> &str {
    match tool_name {
        "search_symbols" => "Searching for symbols",
        "get_symbol_locations" => "Finding files for symbols",
        "get_symbol_sources" => "Fetching symbol source",
        "get_file_summaries" => "Getting file summaries",
        "skim_files" => "Skimming files",
        "most_relevant_files" => "Finding related files",
        "readFile" => "Reading file",
        "writeFile" => "Writing file",
        "listDirectory" => "Listing directory",
        "runShellCommand" => "Running shell command",
        "think" => "Thinking",
        _ => tool_name,
    }
}
```

---

## Implementation order

| Phase | Work | Estimate | Dependencies |
|---|---|---|---|
| 1 | Extend `llm_client.rs`: tools + tool_calls SSE | ~2 days | - |
| 2 | ToolRegistry + Bifrost integration + filesystem/shell tools | ~2-3 days | Bifrost crate |
| 3 | Agentic loop `tool_loop.rs` + integration in `agent.rs` | ~2 days | Phases 1+2 |
| 4 | Security (path validation, shell sandboxing) | ~1 day | Phase 2 |
| 5 | ACP display (headlines, notifications) | ~0.5 day | Phase 3 |

**Total estimate: ~7-8 days.**

Without Bifrost it would be 12-15+ days (reimplementing tree-sitter, grammars, index, etc.).

---

## Dependencies to add to Cargo.toml

```toml
# Bifrost -- native Rust code analyzer (code intelligence)
brokk_analyzer = { git = "https://github.com/brokkai/bifrost.git" }

# Already present: tokio (with the "process" feature for the shell)
# Already present: serde_json, uuid, etc.
```

That's it. Bifrost brings in tree-sitter + grammars + git2 + walkdir + glob as transitive dependencies.

---

## Open questions

1. **Bifrost version**: pin to `main` or to a specific tag?

2. **Models without tool calling**: some local models (small Ollamas) do not support tool calling. Do we need a text-based fallback? Or simply not send the tools and stay in plain chat mode?

3. **Automatic refresh**: Bifrost has a `ProjectChangeWatcher` that refreshes the index when files change. Should we enable it or let the LLM call `refresh` manually?

4. **MCP tools from the ACP client**: the ACP protocol lets the client provide MCP servers. Should we connect to them for additional tools? That is significant additional scope.

5. **Tool call persistence**: should intermediate tool calls be persisted in the session zip for replay? The current zip format supports `ChatMessageDto` with role/contentId, so it is feasible.

---

## Audit -- issues identified (2026-04-24)

Full review of the Rust ACP server. Issues classified by severity.

### Critical bugs

1. **Broken streaming** (`tool_loop.rs:46-68`). The `on_token` callback buffers tokens in a `Vec<String>` and only flushes them after `stream_chat` finishes. The ACP client therefore sees nothing until the LLM response is complete. Fix: pass `on_text` directly via an `Arc<Mutex<dyn FnMut>>` shared across turns.

2. **Session mode and model not persisted** (`session.rs`). `get_session` always reloads with `mode: SessionMode::Lutz` and `model: default_model`; changes via `set_mode` are never written to the zip. Fix: add `mode` and `model` to `SessionManifest`.

3. **Incomplete history replay** (`agent.rs:156-158`). Only `turn.agent_response` is sent back to the client; user prompts are omitted. Fix: also send `turn.user_prompt` (role user).

4. **Tool calls not persisted in history**. `ConversationTurn` only stores `user_prompt` + `agent_response`. On reload, the LLM no longer sees its intermediate tool calls/results. Larger scope -- see "deferred improvements".

5. **Malformed tool arguments silently swallowed** (`tool_loop.rs:92`). `serde_json::from_str(...).unwrap_or_default()` invokes the tool with `{}` without signaling the error to the LLM. Fix: return a parsing error `tool_result`.

### Security

6. **Hole in `safe_resolve_for_write`** (`tools/mod.rs:236-255`). If the parent of the requested path does not exist, the `starts_with(cwd)` check is skipped entirely and the non-canonicalized `joined` path is returned. Path traversal is possible via `../new_dir/../../tmp/evil`. Fix: walk up to the first existing ancestor, canonicalize that, and validate the prefix.

7. **`runShellCommand` without sandbox** -- DONE (#3390). `src/tools/sandbox.rs` now wraps shell calls in `sandbox-exec` (macOS Seatbelt) or `bwrap` (Linux Bubblewrap), driven by `SandboxPolicy::from_permission_mode`: BypassPermissions -> None, ReadOnly/Default -> ReadOnly, AcceptEdits -> WorkspaceWrite. Policy is mirrored from `app/src/main/java/ai/brokk/util/Environment.java`. Windows is log-and-skip. On Linux without `bwrap` installed we log-once and fall back to unwrapped exec.

8. **Symlinks followed blindly**. A symlink under cwd pointing outside lets writes through. Mitigated by `canonicalize` in `safe_resolve` for reads; writes remain exposed.

### Concurrency / performance

9. **Blocking I/O under the tokio runtime** (`session.rs`, `tools/filesystem.rs`). All `std::fs` and zip calls are sync from `async` methods. Fix: wrap them in `tokio::task::spawn_blocking`.

10. **`add_turn` blocks the entire `SessionStore`** (`session.rs:605-618`). The `RwLock::write()` on `sessions` is held during the whole zip compression. Fix: clone the needed data outside the lock before `spawn_blocking`.

11. **`list_models()` redone on every `session/new`** (`agent.rs:106`). One HTTP call per session creation for a list already fetched at init. Fix: cache the list in `SessionStore`.

12. **`search_file_contents` without size filter or binary detection** (`filesystem.rs:161`). Tries to read every file not filtered out by the glob. Fix: skip if > 1 MiB or if the first bytes contain a NUL.

13. **No eviction of in-memory sessions**. `SessionStore::sessions` grows indefinitely. Out of immediate scope (YAGNI).

### Missing features

14. **Bifrost/brokk_analyzer never integrated** -- DONE. Now spawned as an MCP subprocess from `src/bifrost_client.rs` and dispatched through `ToolRegistry::execute_bifrost`. See Phase 2.2 above.

15. **`max_turns` hardcoded to 25** (`agent.rs:271`). Fix: expose `--max-turns`.

16. **No fallback for models without tool calling**. Many small Ollamas break. Out of immediate scope.

17. **Invalid mode silently accepted** (`agent.rs:315-317`). `SessionMode::parse` None ignored and `SetSessionModeResponse::new()` replies success. Fix: return a JSON-RPC error or an error `meta`.

18. **`SetSessionModeResponse` succeeds even if the session does not exist**. Fix: validate existence before responding.

### Code quality

19. **Truncated tool calls returned on cancel** (`llm_client.rs:430-437`). If the stream is cut mid-arguments, `tool_acc.is_empty()` is false and partial calls bubble up. Fix: if `cancel.is_cancelled()`, return only the text.

20. **Logs that swallow zip write errors** (`session.rs`). `append_turn_to_zip` logs `warn` and continues; memory believes the data was persisted while disk is desynchronized. Fix (partial): propagate the error via `Result`.

21. **No tests**. No `#[test]` nor `tests/`. Gap vs. the Java side. Out of immediate scope.

22. **`ChatMessage.role` not typed**. `String` everywhere. Out of immediate scope (YAGNI).

---

## Remediation plan (order of attack)

1. Streaming (critical bug)
2. Path-traversal `safe_resolve_for_write`
3. Mode/model persistence
4. User prompt replay
5. Malformed arguments -> error
6. Blocking I/O -> spawn_blocking
7. Configurable `max_turns`
8. Binary/size filter in `search_file_contents`
9. Model cache
10. `set_mode` validation
11. Truncated tool calls on cancel

---

# Future plan: Rich ACP `tool_call` notifications

## Context

PR #3391 added per-call permission gating to the Rust ACP server, but the
client (Zed) still only sees a one-line italic chunk like `_Reading file..._`
emitted as an `agent_message_chunk`. There's no file path, no input
summary, no output, no progress — the user can't tell what the agent is
actually doing or what came back.

ACP already provides the right surface: `SessionUpdate::ToolCall` and
`SessionUpdate::ToolCallUpdate`, which clients render as proper expandable
cards with title / kind / status / content / locations / raw input+output.
We just don't emit them. The reference (`claude-agent-acp`) emits these
for every tool, with kind-appropriate content (text for reads, diffs for
writes, terminal output for shell).

This plan wires those notifications into our existing tool loop, drops
the redundant italic headlines, and gives `writeFile` a real Diff content
block by capturing prior file content before dispatch.

Out of scope: ACP Terminal protocol embed for shell (separate, larger
piece), parsing Bifrost JSON outputs for file locations (tracked at #3390
already covers the related sandbox piece — file a follow-up if we want
location parsing).

---

## Design

### Lifecycle (per tool call, uniform across all tools)

1. **Pending** — emit `SessionUpdate::ToolCall { status: Pending }` immediately when the call arrives, before the permission gate runs. Same `tool_call_id` is reused throughout.
2. **Permission gate** — existing logic. The `RequestPermissionRequest` we already build includes a `ToolCallUpdate` for the pending call so the client renders the card alongside the prompt; no change there.
3. **Rejected** (gate auto-rejects, user picks Reject, or cancels) — emit `SessionUpdate::ToolCallUpdate { status: Failed, content: [denial reason] }`, then push the synthetic `"Tool use denied..."` tool result to the LLM (existing behavior).
4. **InProgress** — emit `SessionUpdate::ToolCallUpdate { status: InProgress }` once the gate has cleared and we're about to call `registry.execute(...)`.
5. **Completed / Failed** — emit `SessionUpdate::ToolCallUpdate { status, content, raw_output }`. `Failed` if `ToolStatus::RequestError` or `InternalError`; `Completed` otherwise.

### Per-tool title, locations, content

| Tool | title | kind | locations | content (Completed) |
| --- | --- | --- | --- | --- |
| `readFile` | ``Read `<path>` `` | Read | `[{path}]` | text(output, truncated) |
| `writeFile` | ``Write `<path>` `` | Edit | `[{path}]` | **Diff{path, old_text, new_text}** |
| `listDirectory` | ``List `<path>` `` | Read | `[{path}]` | text(output) |
| `searchFileContents` | ``Search `<pattern>` `` | Search | `[]` (v1 — could parse later) | text(output) |
| `runShellCommand` | ``Run `<first line of command>` `` | Execute | `[]` | text(output) |
| `think` | `Think` | Think | `[]` | text(thought) |
| Bifrost tools | `<existing headline> — <brief input>` | from `ToolRegistry::tool_kind` | `[]` (v1) | text(output) |

`raw_input` is always the parsed JSON args; `raw_output` is always the tool's stringified output.

### Diff capture for `writeFile`

The current `filesystem::write_file` (`brokk-acp-rust/src/tools/filesystem.rs:36-65`) doesn't read the existing file. To produce a Diff content block:

- In `tool_loop.rs`, immediately before dispatching `writeFile`, attempt to read the existing content via `std::fs::read_to_string(safe_resolve_for_write(...))`. If the file doesn't exist, treat as `old_text = ""`.
- After execution succeeds, build `Diff { path, old_text, new_text: <content arg> }` and put it in the `Completed` update.
- On failure, fall back to text content (the error message).
- Failure to read the old content (e.g. binary file) — emit a text content with a note like ``"(prior content unavailable; new content written)"``, still Completed.

This is the only tool that needs special pre-dispatch handling. Everything else just observes the input args and the output string.

### Drop the italic headlines

- Remove the `on_tool_event: impl FnMut(&str)` parameter from `tool_loop::run` (`brokk-acp-rust/src/tool_loop.rs`).
- Remove the headline-emit closure in `agent.rs` (the `|headline| send_message(...)` lambda passed into `tool_loop::run` at the prompt handler).
- The `ToolRegistry::headline(...)` static-string mapping (`tools/mod.rs:276-295`) becomes the source of titles for Bifrost tools. Rename it to `display_name(...)` for clarity, keep the same content.
- Tool-event tracing logs (`tracing::info!`) stay — they're stderr-only debug, not user-visible.

---

## Implementation

### New file: `brokk-acp-rust/src/tool_loop/announce.rs`

Free functions, no state:

```rust
pub fn initial_tool_call(
    tool_call_id: &str,
    tool_name: &str,
    kind: ToolKind,
    raw_input: &serde_json::Value,
) -> ToolCall;

pub fn update_in_progress(tool_call_id: &str) -> ToolCallUpdate;

pub fn update_failed(
    tool_call_id: &str,
    reason: &str,
    raw_output: Option<serde_json::Value>,
) -> ToolCallUpdate;

pub fn update_completed(
    tool_call_id: &str,
    tool_name: &str,
    output: &str,
    diff: Option<Diff>,
) -> ToolCallUpdate;

fn tool_title(tool_name: &str, raw_input: &serde_json::Value) -> String;
fn tool_locations(tool_name: &str, raw_input: &serde_json::Value) -> Vec<ToolCallLocation>;
```

Internally the title / location builders match on `tool_name` per the table above. Bifrost tools fall through to the generic `<display_name> — <brief input>` form.

### Modify `brokk-acp-rust/src/tool_loop.rs`

Make `tool_loop::run` no longer take `on_tool_event`. The per-call body becomes:

1. Parse `raw_input` as JSON (we already do this for arg validation).
2. Call `announce::initial_tool_call(...)`; send as `SessionUpdate::ToolCall`.
3. Run `consult_gate(...)` (existing).
4. On `GateDecision::Reject(msg)` — emit `update_failed(...)` and push synthetic tool result; continue loop.
5. On `GateDecision::Allow` — emit `update_in_progress(...)`.
6. **Special-case writeFile**: read existing file content before dispatch.
7. Call `execute_tool(...)` (existing).
8. Map `ToolStatus` → `ToolCallStatus`. Build `Diff` if writeFile succeeded; otherwise text content.
9. Emit `update_completed(...)` or `update_failed(...)` accordingly.
10. Push the existing tool-result message to the LLM (unchanged).

### Modify `brokk-acp-rust/src/agent.rs`

Drop the headline-emitting lambda passed to `tool_loop::run` (the `|headline| send_message(...)` block in the prompt handler at the spawned task). `cx` is already passed through to `tool_loop` for permission requests; we'll reuse it for the new notifications.

### Modify `brokk-acp-rust/src/tools/filesystem.rs`

No structural change needed — the diff capture happens upstream in the tool loop. Just verify `write_file` still returns its existing string success message; that becomes the `raw_output` (the Diff is the user-facing content).

### Modify `brokk-acp-rust/src/tools/mod.rs`

Rename `ToolRegistry::headline(...)` to `display_name(...)` — no behavior change, clearer name now that this is a title builder rather than a status announcer.

---

## Critical files

- `brokk-acp-rust/src/tool_loop.rs` — main lifecycle wiring
- `brokk-acp-rust/src/tool_loop/announce.rs` — new helper module
- `brokk-acp-rust/src/agent.rs` — drop the old headline lambda (prompt handler in the spawned task)
- `brokk-acp-rust/src/tools/mod.rs` — rename `headline` → `display_name`
- `brokk-acp-rust/src/tools/filesystem.rs` — read-only audit; no change

ACP schema (read-only reference, all already in our pinned `agent-client-protocol = 0.11`):

- `SessionUpdate::ToolCall(ToolCall)`, `SessionUpdate::ToolCallUpdate(ToolCallUpdate)` — `client.rs` in `agent-client-protocol-schema-0.12.0`
- `ToolCall`, `ToolCallUpdate`, `ToolCallUpdateFields`, `ToolCallStatus`, `ToolCallContent { Content | Diff | Terminal }`, `ToolCallLocation`, `Diff` — `tool_call.rs` in the schema crate

---

## Verification

End-to-end in Zed against `brokk install --rust`:

1. Ask the agent to **read a file**. Expect: a tool-call card with title `Read <path>`, status transitions Pending → InProgress → Completed, file content visible inline.
2. Ask the agent to **write a new file**. Expect: card with title `Write <path>`, **inline diff** showing empty → new content, status Completed.
3. Ask the agent to **modify an existing file**. Expect: inline diff showing old → new content.
4. Ask the agent to **run a shell command** like `ls`. Expect: card with title ``Run `ls` ``, output text inline. (In `default` mode this also shows the permission prompt before the card progresses past Pending.)
5. Ask the agent to **search for a symbol** via Bifrost's `search_symbols`. Expect: card with the bifrost tool's display name, JSON results inline.
6. **Reject** a write via the permission prompt. Expect: card status flips to Failed with the denial message, and the LLM gets the synthetic tool-result.
7. **Verify the italic `_Reading file..._` chunks are gone** from the agent's text stream — the cards should be the only surface.
8. **Cancel** an in-flight prompt. Expect: any pending tool-call card cleanly resolves to Failed.

Automated:

- `cargo build`, `cargo clippy --all-targets -- -D warnings`, `cargo test` all clean.
- Add at least one unit test in `tool_loop/announce.rs` covering `tool_title` and `tool_locations` for the seven built-in tools (so renames don't silently break the wire output).

---

## Out of scope (follow-ups)

- ACP Terminal protocol embed for `runShellCommand` (live streaming output via `terminal/create`). Significantly larger work; would replace our synchronous shell runner.
- Parsing Bifrost JSON output to extract file paths into `locations`. Per-tool schema work; defer.
- `tool_call_update` mid-execution streaming (e.g. progress messages). v1 is start → end only.
