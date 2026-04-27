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

7. **`runShellCommand` without sandbox**. `sh -c` runs anything (`cat /etc/passwd`, `curl | sh`, etc.). No seccomp, namespace, or allow-list. For an LLM-driven tool this is a major risk. Fix: out of immediate scope, but document and consider an ACP client confirmation.

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
