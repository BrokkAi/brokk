# Plan: Replace HeadlessExecutor with a Native Java ACP Server

## Motivation

Today the architecture for headless/editor integration is a three-layer stack:

```
IDE/Editor  <-->  Python ACP Server (acp_server.py)  <-->  Java HeadlessExecutor (HTTP REST)  <-->  ContextManager
                    BrokkAcpBridge                           JobRunner, JobStore, Routers
                    BrokkAcpAgent                            SimpleHttpServer
                    ExecutorManager (subprocess mgmt)
```

The Python layer translates ACP JSON-RPC (stdio) into HTTP REST calls to a Java
subprocess. This involves ~1600 lines of Python bridge code, ~1340 lines of
executor management, ~730 lines of Java HTTP server, and multiple routers. There
are two processes, two languages, an HTTP polling loop for event streaming, auth
token generation, port allocation, and a stdin-EOF death signal for subprocess
lifecycle.

By implementing the ACP protocol directly in Java using the
[java-sdk](https://github.com/agentclientprotocol/java-sdk), the architecture
becomes:

```
IDE/Editor  <-->  Java ACP Agent (stdio JSON-RPC)  <-->  ContextManager
                    @Initialize, @Prompt, etc.
                    JobRunner, JobStore
```

This eliminates the Python bridge, the custom HTTP server, and the subprocess
lifecycle management entirely. The editor spawns one Java process that speaks ACP
natively over stdio.

## ACP protocol summary

The Agent Client Protocol uses **JSON-RPC 2.0 over stdio** (newline-delimited,
one message per line, UTF-8). The lifecycle has three phases:

1. **Initialization**: Client sends `initialize` with `protocolVersion` (uint16,
   major only), `clientInfo`, and `clientCapabilities` (fs, terminal support).
   Agent responds with negotiated version, `agentInfo`, `agentCapabilities`, and
   optional `authMethods`.

2. **Session setup**: `session/new` creates a session (params: `cwd`,
   `mcpServers`). Optional `session/load` replays conversation history via
   `session/update` notifications before responding. Optional `session/list`
   returns sessions with cursor-based pagination.

3. **Prompt turn**: `session/prompt` (params: `sessionId`, `prompt` as
   `ContentBlock[]`) triggers agent execution. The agent streams back via
   `session/update` notifications (text chunks, tool calls, plans, config
   updates). Returns `stopReason`: `end_turn`, `max_tokens`, `cancelled`, etc.
   Cancellation via `session/cancel` notification.

**Content blocks** are a discriminated union on `type`:
- `text` (baseline) -- markdown text
- `resource_link` (baseline) -- file reference by URI, name, optional mimeType/size
- `resource` (embedded, requires `promptCapabilities.embeddedContext`) -- inline file content
- `image` (requires `promptCapabilities.image`) -- base64 data + mimeType
- `audio` (requires `promptCapabilities.audio`) -- base64 data + mimeType

**Extension mechanism**: `_`-prefixed method names for custom JSON-RPC
requests/notifications. Unrecognized custom requests get `-32601 Method not
found`; unrecognized custom notifications are silently ignored. Custom metadata
via `_meta` field on all types (also used for capability advertisement).

**Error codes**: Standard JSON-RPC (`-32700` parse, `-32600` invalid request,
`-32601` method not found, `-32602` invalid params, `-32603` internal) plus
ACP-specific (`-32000` auth required, `-32002` resource not found).

Full spec: https://agentclientprotocol.com

## Key design decisions

### IConsoleIO is the adapter point

`IConsoleIO` already abstracts the output/notification channel from agent
execution to the client. Implementations exist for GUI (`Chrome`), CLI
(`HeadlessConsole`), and HTTP (`HeadlessHttpConsole`). The ACP migration adds one
more: `AcpConsoleIO`, which maps IConsoleIO calls directly to ACP `session/update`
JSON-RPC notifications.

The ACP Java SDK provides a `SyncPromptContext` during `@Prompt` handling, which
exposes `sendMessage()`, `sendThought()`, `readFile()`, `writeFile()`, etc.
`AcpConsoleIO` wraps this context object -- it gets constructed per-prompt-turn
and set as the active console on ContextManager.

The mapping is nearly 1:1:

| IConsoleIO method | ACP equivalent |
|---|---|
| `llmOutput(token, type, meta)` | `context.sendMessage(text)` or `context.sendThought(text)` (choose based on `meta.isReasoning`) |
| `beforeToolCall(request)` | `session/update` with tool_call (status: pending, includes toolName, args, destructive flag) |
| `afterToolOutput(result)` | `session/update` with tool_call_update (status: completed/failed, includes resultText, elapsed) |
| `showConfirmDialog(...)` | `session/request_permission` (blocks until client responds) |
| `toolError(msg, title)` | `context.sendMessage(errorText)` formatted as markdown error block |
| `showNotification(COST, msg, cost)` | `context.sendMessage(costText)` with cost metadata |
| `setTaskInProgress(bool)` | Implicit in prompt turn lifecycle |
| GUI-only methods (`updateGitRepo`, `disableActionButtons`, etc.) | no-op |

Everything downstream of IConsoleIO (Llm, CodeAgent, LutzAgent, ArchitectAgent,
tools, etc.) is untouched.

### Confirm dialogs become real permissions

Today `HeadlessHttpConsole.showConfirmDialog()` auto-approves everything and emits
a `CONFIRM_REQUEST` event that nobody responds to. With ACP,
`session/request_permission` actually blocks until the editor user clicks
allow/reject. This is a genuine capability upgrade.

The ACP spec defines four permission option kinds:
- `allow_once` -- approve this specific invocation
- `allow_always` -- approve all future invocations of this tool
- `reject_once` -- deny this specific invocation
- `reject_always` -- deny all future invocations of this tool

This enables fine-grained control over destructive operations (git reset, drop all
context, shell commands) that were previously auto-approved in headless mode.

### All configuration uses SessionConfigOption

The ACP spec is deprecating dedicated session mode methods (`session/set_mode`) in
favor of the unified `session/set_config_option` mechanism. All Brokk
configuration surfaces through config options with categories:

| Config option | Category | Values |
|---|---|---|
| Mode (LUTZ/CODE/ASK/PLAN) | `mode` | Select from available modes |
| Model selection | `model` | Select from `contextManager.getAvailableModels()` |
| Reasoning level | `thought_level` | low, medium, high, disable, default |

`session/set_config_option` params: `sessionId`, `configId`, `value`. The response
returns the complete list of all config options (to reflect dependent changes --
e.g., changing the model may affect available reasoning levels).

### Brokk-specific features use ACP extension methods

ACP reserves `_`-prefixed method names for custom extensions (JSON-RPC requests
and notifications). Brokk-specific features that go beyond the standard ACP
protocol surface through these extensions:

```
_brokk/context/get          - returns fragments, tokens, maxTokens
_brokk/context/drop         - drop fragment IDs
_brokk/context/drop_all     - drop everything
_brokk/context/pin          - toggle pin on fragment
_brokk/context/readonly     - toggle readonly on fragment
_brokk/context/compress     - compress history
_brokk/context/clear        - clear history
_brokk/context/add_files    - add files to context
_brokk/context/add_classes  - add class summaries
_brokk/context/add_methods  - add method sources
_brokk/completions          - file/symbol completions (for @mention autocomplete)
_brokk/costs                - session cost breakdown
_brokk/context/updated      - (notification) agent tells client context changed
```

The Java agent advertises these during `initialize` via `_meta` in
`agentCapabilities`:

```json
{
  "agentCapabilities": {
    "loadSession": true,
    "promptCapabilities": { "embeddedContext": true },
    "sessionCapabilities": { "list": true },
    "_meta": {
      "brokk": {
        "context": true,
        "completions": true,
        "costs": true
      }
    }
  }
}
```

brokk-code checks for these and enables the interactive context panel, @mention
autocomplete, etc. Generic ACP clients (Zed, etc.) ignore unknown extensions and
get the standard experience.

This means the interactive context modal in brokk-code works identically -- the
`ContextPanel` widget calls `_brokk/context/get` over the ACP JSON-RPC connection
instead of `GET /v1/context` over HTTP. Same data, same UI, different transport.

### Standard ACP features for standard operations

| Feature | ACP mechanism |
|---|---|
| `/context`, `/costs` (text output) | Slash commands (agent advertises via `session/update` `available_commands_update`, prompt handler intercepts `/`-prefixed text) |
| Mode (LUTZ/CODE/ASK/PLAN) | `SessionConfigOption` (select, category: mode) |
| Model selection | `SessionConfigOption` (select, category: model) |
| Reasoning level | `SessionConfigOption` (select, category: thought_level) |
| Session create/load/list | `session/new`, `session/load` (with history replay), `session/list` (with cursor pagination) |
| LLM streaming | `session/update` with `agent_message_chunk` content blocks |
| Extended thinking | `session/update` with `agent_thought_chunk` content blocks |
| Tool call reporting | `session/update` with `tool_call` / `tool_call_update` (status lifecycle: pending -> in_progress -> completed/failed) |
| Cancellation | `session/cancel` notification |
| Agent plan display | `session/update` with `plan` entries (maps `TaskEntry` -> `PlanEntry` with content, priority, status) |
| @file mentions from editor | `resource_link` content blocks in prompt (baseline, no capability needed) or `resource` blocks (requires `embeddedContext` capability) |

### Session load replays conversation history

When a client calls `session/load`, the agent must replay the entire conversation
via `session/update` notifications (`user_message_chunk` and
`agent_message_chunk`) before returning the response. This means
`BrokkAcpAgent.@LoadSession` reads the conversation history from ContextManager's
task history and streams each entry back as the appropriate message type.

### File I/O and terminal execution

The agents currently do their own file I/O and command execution directly.
Initially, keep this -- ACP's `fs/*` and `terminal/*` methods are optional
capabilities, not requirements. Adopt them incrementally later for specific
operations where editor mediation adds value (e.g., showing diffs in the editor,
routing build output through `terminal/*`).

### JobStore is kept as internal infrastructure

ACP is a protocol, not a persistence layer. JobStore continues to provide durable,
file-backed job persistence with idempotency keys. It's an internal implementation
detail that the ACP layer doesn't expose directly.

### Python can still be the deployment wrapper

If needed for deployment (e.g., PyPI distribution), Python can launch the Java
process and pass stdio through. This is trivial compared to the current HTTP
bridge -- it's just subprocess stdin/stdout forwarding.

## Migration phases

### Phase 1: Java ACP agent alongside HeadlessExecutor

Goal: prove the architecture works end-to-end with a minimal ACP agent.

1. Add the `acp-core` and `acp-agent-support` dependencies from the java-sdk
2. Create `AcpConsoleIO extends MemoryConsole`
   - Constructor takes `SyncPromptContext` from the SDK (available during `@Prompt`)
   - `llmOutput()` -> `context.sendMessage(text)` or `context.sendThought(text)`
   - `beforeToolCall()` / `afterToolOutput()` -> `session/update` tool_call
   - `showConfirmDialog()` -> `session/request_permission` with allow_once/reject_once options
   - `showNotification()` -> `context.sendMessage(text)` with formatting
   - GUI-only methods (`updateGitRepo`, `disableActionButtons`, etc.) -> no-op
3. Create `BrokkAcpAgent` Java class (annotation-based, using the SDK):
   - `@Initialize` -- return capabilities, agent info, advertise `_brokk/*` via `_meta`
   - `@NewSession` -- create session via ContextManager, return config options (mode, model, reasoning as `SessionConfigOption` selects), advertise slash commands
   - `@LoadSession` -- switch session via ContextManager, replay conversation history via `session/update` notifications (user_message_chunk, agent_message_chunk)
   - `@ListSessions` -- list sessions from ContextManager with cursor-based pagination
   - `@Prompt` -- intercept slash commands (`/context`, `/costs`), otherwise create AcpConsoleIO with `SyncPromptContext`, set as active IO, run job via JobRunner
   - `@SetConfigOption` -- handle mode/model/reasoning changes, return full config option list
   - `@Cancel` -- cancel active job
   - Custom method handlers for `_brokk/context/*` and `_brokk/completions`
4. Create `AcpServerMain` entry point:
   - Parse args: `--workspace-dir`, `--vendor`, `--brokk-api-key`
   - Create `MainProject` from workspace dir
   - Create `ContextManager`, initialize headless mode
   - Instantiate `BrokkAcpAgent` with the ContextManager
   - Create `StdioAcpAgentTransport` and start the agent
   - Much simpler than `HeadlessExecutorMain` -- no HTTP server, no port allocation, no auth tokens
5. Test directly with an ACP client (Zed or the SDK's `AcpClient.sync()` test harness)
6. The existing Python ACP server continues working in parallel -- nothing breaks

The SDK offers three API styles (annotation-based, sync builder, async/Reactor).
Use annotation-based (`@AcpAgent`, `@Initialize`, `@Prompt`, etc.) as it has
the least boilerplate and matches this codebase's style.

### Phase 2: brokk-code migrates to the Java ACP agent

Goal: brokk-code talks to the Java ACP server instead of the HTTP REST API.

1. Update `ExecutorManager` to speak ACP JSON-RPC over stdio instead of HTTP
   - Standard operations use the ACP client SDK (`session/prompt`, etc.)
   - Context operations use `_brokk/context/*` extension methods
   - Completions use `_brokk/completions`
   - Costs use `_brokk/costs`
2. Update `BrokkAcpBridge` -- this becomes much thinner since both sides speak ACP
3. Update `ContextPanel` action handlers to call through the new transport
4. Update `@mention` autocomplete to use `_brokk/completions`
5. Verify the full interactive context modal works end-to-end
6. Verify session management (create, load, list, switch, rename, delete)
7. Verify model/mode/reasoning config option flow

### Phase 3: Remove the old infrastructure

Goal: delete the code that's no longer needed.

1. Delete `HeadlessExecutorMain.java` and all routers (SessionsRouter, JobsRouter, ContextRouter, RepoRouter, ModelsRouter, CompletionsRouter, ActivityRouter)
2. Delete `SimpleHttpServer.java`
3. Delete `HeadlessHttpConsole.java` (replaced by `AcpConsoleIO`)
4. Delete the Python `acp_server.py` (the `BrokkAcpBridge` and `BrokkAcpAgent` classes)
5. Simplify `executor.py` / `ExecutorManager` -- it becomes a thin ACP client wrapper
6. Remove HTTP client dependencies (`httpx`), auth token generation, port management
7. Update deployment/packaging scripts

### Phase 4: Leverage ACP-native features

Goal: adopt ACP capabilities that weren't available in the old architecture.

1. Use `session/request_permission` for destructive operations (git reset, drop all context, etc.) with `allow_once`/`allow_always`/`reject_once`/`reject_always` options instead of auto-approving
2. Route selected file operations through `fs/read_text_file` and `fs/write_text_file` for editor buffer integration
3. Route build/test commands through `terminal/*` so editors can display output natively (terminal lifecycle: create -> output/wait_for_exit -> release)
4. Use `session/update` content blocks for rich diff display (diff content type: path + oldText + newText)
5. Adopt `session/load` history replay for conversation restoration
6. Emit `_brokk/context/updated` notifications when the agent auto-modifies context, so brokk-code can auto-refresh the panel
7. Map LUTZ/PLAN task lists to ACP's native `plan` session update (`TaskEntry` -> `PlanEntry` with content, priority: high/medium/low, status: pending/in_progress/completed)

## What stays the same

- ContextManager and all agent internals (CodeAgent, LutzAgent, ArchitectAgent, SearchAgent, BuildAgent, etc.)
- IConsoleIO interface (gains one new implementation, loses one)
- JobRunner and JobStore (internal job execution and persistence)
- All tool implementations
- The GUI app (Chrome) -- unaffected, it doesn't use the headless path
- Git operations, code intelligence, multi-analyzer -- all unchanged

## File inventory

### New files (Phase 1)

| File | Purpose | Est. LOC |
|---|---|---|
| `app/.../acp/BrokkAcpAgent.java` | Main agent with @Initialize, @Prompt, etc. | ~400-500 |
| `app/.../acp/AcpConsoleIO.java` | IConsoleIO -> ACP session/update adapter (wraps SyncPromptContext) | ~200-250 |
| `app/.../acp/AcpServerMain.java` | Entry point (arg parsing, wiring, StdioAcpAgentTransport) | ~100-150 |
| `app/.../acp/BrokkExtensionHandlers.java` | `_brokk/*` custom method handlers | ~200-300 |
| Tests (unit + integration) | AcpConsoleIO tests, SDK test client integration | ~300-400 |
| **Total new** | | **~1200-1600** |

### Deleted files (Phase 3)

| Files | Est. LOC removed |
|---|---|
| HeadlessExecutorMain + all routers | ~3000 |
| SimpleHttpServer, HeadlessHttpConsole | ~1000 |
| Python acp_server.py + executor.py bridge code | ~3000 |
| **Total deleted** | **~5000+** |

Net: significant reduction in codebase size and complexity.

## Dependencies

- [acp java-sdk](https://github.com/agentclientprotocol/java-sdk): `acp-core`, `acp-agent-support`, `acp-annotations`
- Java 21 (already required by this project)
- Optional: `acp-websocket-jetty` if HTTP/WebSocket transport is needed later

## References

- ACP specification: https://agentclientprotocol.com
- ACP Java SDK: https://github.com/agentclientprotocol/java-sdk
- ACP Java tutorial: https://github.com/markpollack/acp-java-tutorial
- ACP extensibility: https://agentclientprotocol.com/protocol/extensibility.md
- ACP transport: https://agentclientprotocol.com/protocol/transports.md
- Current HeadlessExecutor: `app/src/main/java/ai/brokk/executor/HeadlessExecutorMain.java`
- Current Python ACP bridge: `brokk-code/brokk_code/acp_server.py`
- Current executor manager: `brokk-code/brokk_code/executor.py`
- IConsoleIO interface: `app/src/main/java/ai/brokk/IConsoleIO.java`
