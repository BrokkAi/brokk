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

## Key design decisions

### IConsoleIO is the adapter point

`IConsoleIO` already abstracts the output/notification channel from agent
execution to the client. Implementations exist for GUI (`Chrome`), CLI
(`HeadlessConsole`), and HTTP (`HeadlessHttpConsole`). The ACP migration adds one
more: `AcpConsoleIO`, which maps IConsoleIO calls directly to ACP `session/update`
JSON-RPC notifications.

The mapping is nearly 1:1:

| IConsoleIO method | ACP equivalent |
|---|---|
| `llmOutput(token, type, meta)` | `session/update` with text content block |
| `beforeToolCall(request)` | `session/update` with tool_call (status: pending) |
| `afterToolOutput(result)` | `session/update` with tool_call_update (status: completed/failed) |
| `showConfirmDialog(...)` | `session/request_permission` (blocks until client responds) |
| `toolError(msg, title)` | `session/update` with text content block (error) |
| `showNotification(COST, msg, cost)` | `session/update` with text/meta content |
| `setTaskInProgress(bool)` | Implicit in prompt turn lifecycle |

Everything downstream of IConsoleIO (Llm, CodeAgent, LutzAgent, ArchitectAgent,
tools, etc.) is untouched.

### Confirm dialogs become real permissions

Today `HeadlessHttpConsole.showConfirmDialog()` auto-approves everything and emits
a `CONFIRM_REQUEST` event that nobody responds to. With ACP,
`session/request_permission` actually blocks until the editor user clicks
allow/reject. This is a genuine capability upgrade.

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
`agentCapabilities`. brokk-code checks for them and enables the interactive
context panel, @mention autocomplete, etc. Generic ACP clients (Zed, etc.) ignore
unknown extensions and get the standard experience.

This means the interactive context modal in brokk-code works identically — the
`ContextPanel` widget calls `_brokk/context/get` over the ACP JSON-RPC connection
instead of `GET /v1/context` over HTTP. Same data, same UI, different transport.

### Standard ACP features for standard operations

| Feature | ACP mechanism |
|---|---|
| `/context`, `/costs` (text output) | Slash commands (agent advertises, prompt handler intercepts) |
| Mode (LUTZ/CODE/ASK/PLAN) | `SessionConfigOption` (select, category: mode) |
| Model selection | `SessionConfigOption` (select, category: model) |
| Reasoning level | `SessionConfigOption` (select, category: thought_level) |
| Session create/load/resume/list | `session/new`, `session/load`, `session/list` |
| LLM streaming | `session/update` text content blocks |
| Tool call reporting | `session/update` tool_call / tool_call_update |
| Cancellation | `session/cancel` |
| Agent plan display | `session/update` agent plan entries |
| @file mentions from editor | `EmbeddedResource` content blocks in prompt |

### File I/O and terminal execution

The agents currently do their own file I/O and command execution directly.
Initially, keep this — ACP's `fs/*` and `terminal/*` methods are optional
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
bridge — it's just subprocess stdin/stdout forwarding.

## Migration phases

### Phase 1: Java ACP agent alongside HeadlessExecutor

Goal: prove the architecture works end-to-end with a minimal ACP agent.

1. Add the `acp-core` and `acp-agent-support` dependencies from the java-sdk
2. Create `AcpConsoleIO extends MemoryConsole`
   - `llmOutput()` → `session/update` text content
   - `beforeToolCall()` / `afterToolOutput()` → `session/update` tool_call
   - `showConfirmDialog()` → `session/request_permission`
   - `showNotification()` → `session/update` text content
   - GUI-only methods (`updateGitRepo`, `disableActionButtons`, etc.) → no-op
3. Create `BrokkAcpAgent` Java class with:
   - `@Initialize` — return capabilities, agent info, advertise `_brokk/*` extensions
   - `@NewSession` — create session via ContextManager, return config options (mode, model, reasoning), advertise slash commands
   - `@Prompt` — intercept slash commands (`/context`, `/costs`), otherwise create AcpConsoleIO, set as active IO, run job via JobRunner
   - `@SetConfigOption` — handle mode/model/reasoning changes
   - `@Cancel` — cancel active job
   - Custom method handlers for `_brokk/context/*` and `_brokk/completions`
4. Add a stdio transport entry point (new main class) that wires everything up
5. Test directly with an ACP client (Zed or a test harness)
6. The existing Python ACP server continues working in parallel — nothing breaks

### Phase 2: brokk-code migrates to the Java ACP agent

Goal: brokk-code talks to the Java ACP server instead of the HTTP REST API.

1. Update `ExecutorManager` to speak ACP JSON-RPC over stdio instead of HTTP
   - Standard operations use the ACP client SDK (`session/prompt`, etc.)
   - Context operations use `_brokk/context/*` extension methods
   - Completions use `_brokk/completions`
   - Costs use `_brokk/costs`
2. Update `BrokkAcpBridge` — this becomes much thinner since both sides speak ACP
3. Update `ContextPanel` action handlers to call through the new transport
4. Update `@mention` autocomplete to use `_brokk/completions`
5. Verify the full interactive context modal works end-to-end
6. Verify session management (create, load, resume, list, switch, rename, delete)
7. Verify model/mode/reasoning config option flow

### Phase 3: Remove the old infrastructure

Goal: delete the code that's no longer needed.

1. Delete `HeadlessExecutorMain.java` and all routers (SessionsRouter, JobsRouter, ContextRouter, RepoRouter, ModelsRouter, CompletionsRouter, ActivityRouter)
2. Delete `SimpleHttpServer.java`
3. Delete `HeadlessHttpConsole.java` (replaced by `AcpConsoleIO`)
4. Delete the Python `acp_server.py` (the `BrokkAcpBridge` and `BrokkAcpAgent` classes)
5. Simplify `executor.py` / `ExecutorManager` — it becomes a thin ACP client wrapper
6. Remove HTTP client dependencies (`httpx`), auth token generation, port management
7. Update deployment/packaging scripts

### Phase 4: Leverage ACP-native features

Goal: adopt ACP capabilities that weren't available in the old architecture.

1. Use `session/request_permission` for destructive operations (git reset, drop all context, etc.) instead of auto-approving
2. Route selected file operations through `fs/read_text_file` and `fs/write_text_file` for editor buffer integration
3. Route build/test commands through `terminal/*` so editors can display output natively
4. Use `session/update` content blocks for rich diff display
5. Adopt `session/load` history replay for conversation restoration
6. Emit `_brokk/context/updated` notifications when the agent auto-modifies context, so brokk-code can auto-refresh the panel

## What stays the same

- ContextManager and all agent internals (CodeAgent, LutzAgent, ArchitectAgent, SearchAgent, BuildAgent, etc.)
- IConsoleIO interface (gains one new implementation, loses one)
- JobRunner and JobStore (internal job execution and persistence)
- All tool implementations
- The GUI app (Chrome) — unaffected, it doesn't use the headless path
- Git operations, code intelligence, multi-analyzer — all unchanged

## Dependencies

- [acp java-sdk](https://github.com/agentclientprotocol/java-sdk): `acp-core`, `acp-agent-support`, `acp-annotations`
- Java 17+ (already required)
- Optional: `acp-websocket-jetty` if HTTP/WebSocket transport is needed later

## References

- ACP specification: https://agentclientprotocol.com
- ACP Java SDK: https://github.com/agentclientprotocol/java-sdk
- ACP extensibility: https://agentclientprotocol.com/protocol/extensibility.md
- ACP transport: https://agentclientprotocol.com/protocol/transports.md
- Current HeadlessExecutor: `app/src/main/java/ai/brokk/executor/HeadlessExecutorMain.java`
- Current Python ACP bridge: `brokk-code/brokk_code/acp_server.py`
- Current executor manager: `brokk-code/brokk_code/executor.py`
- IConsoleIO interface: `app/src/main/java/ai/brokk/IConsoleIO.java`
