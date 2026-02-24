# Headless Executor Events

Headless execution writes durable per-job events that clients consume via:

- `GET /v1/jobs/{jobId}/events?after={seq}&limit={n}`

This document reflects the event types currently emitted by the executor.

## Event Envelope

Every entry in `events[]` has this envelope:

| Field | Type | Description |
|---|---|---|
| `seq` | long | Monotonic sequence number within the job stream |
| `timestamp` | long | Epoch milliseconds when the event was created |
| `type` | string | Event type identifier |
| `data` | any | Event-specific payload |

## Event Types

### `LLM_TOKEN`

Token-by-token model output.

`data` object:
- `token` (string)
- `messageType` (string, LangChain message type enum name)
- `isNewMessage` (boolean)
- `isReasoning` (boolean)
- `isTerminal` (boolean)

### `TOOL_CALL`

Emitted when the LLM requests a tool execution.

`data` object:
- `id` (string): Unique identifier for the tool call.
- `name` (string): Name of the tool being called.
- `arguments` (string): JSON-formatted string of tool arguments.

### `TOOL_OUTPUT`

Emitted when a tool execution completes.

`data` object:
- `id` (string): Matches the `id` from the corresponding `TOOL_CALL`.
- `name` (string): Name of the tool that was executed.
- `status` (string): Execution status (`SUCCESS`, `REQUEST_ERROR`, `INTERNAL_ERROR`, or `FATAL`).
- `resultText` (string): The textual output or error message from the tool.

### `NOTIFICATION`

Human-readable progress/status messages.

`data` is one of:
- Object form:
  - `level` (string, e.g. `INFO`, `WARNING`, `ERROR`)
  - `message` (string)
  - `title` (string, optional)
- String form:
  - Plain notification text (emitted by some ISSUE flows)

### `ERROR`

Tool/error notification emitted by console integration.

`data` object:
- `message` (string)
- `title` (string)

### `CONTEXT_BASELINE`

Baseline snapshot hints used by UIs when stream output is reset/reseeded.

`data` object:
- `count` (int)
- `snippet` (string)

### `STATE_HINT`

UI state hints for spinners/progress/state transitions.

`data` object:
- `name` (string)
- `value` (boolean or string)
- `details` (string, optional)
- `count` (int, optional)

Currently used `name` values include:
- `backgroundTask`
- `taskInProgress`
- `outputSpinner`
- `sessionSwitchSpinner`
- `actionButtonsEnabled`
- `workspaceUpdated`
- `gitRepoUpdated`
- `contextHistoryUpdated`

### `COMMAND_RESULT`

Structured result for build/test/lint/review-loop command execution in ISSUE workflows.

`data` object:
- `stage` (string)
- `command` (string)
- `attempt` (int, optional)
- `skipped` (boolean)
- `skipReason` (string, optional)
- `success` (boolean)
- `output` (string, truncated to max 25,000 chars)
- `outputTruncated` (boolean, optional; present when truncation happened)
- `exception` (string, optional)

### `CONFIRM_REQUEST`

Emitted when code requests a confirmation dialog (`showConfirmDialog`) in headless mode.

`data` object:
- `message` (string, as provided by caller)
- `title` (string, as provided by caller)
- `optionType` (int, Swing `JOptionPane` option constant)
- `messageType` (int, Swing `JOptionPane` message constant)
- `defaultDecision` (int, auto-selected return value in headless mode)

Headless default decision policy:
- `YES_NO_OPTION` and `YES_NO_CANCEL_OPTION` -> `YES_OPTION`
- `OK_CANCEL_OPTION` -> `OK_OPTION`
- Any other option type -> `OK_OPTION`

## Client Guidance

- Treat unknown `type` values as forward-compatible and ignore or log them.
- Handle both `NOTIFICATION` payload forms (object and string).
- Use `nextAfter` from `/v1/jobs/{jobId}/events` for incremental polling.
