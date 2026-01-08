# Brokk Headless Executor API: curl Examples

Before creating jobs, ensure your payloads include a `plannerModel`. Every request to `POST /v1/jobs` must provide one.
`codeModel` remains optional, but keep in mind that even in CODE mode the API still requires `plannerModel` for
validation, while only `codeModel` influences CODE-mode execution.

## Environment

| Variable    | Example Value              | Description                                      |
|-------------|----------------------------|--------------------------------------------------|
| `AUTH_TOKEN`| `test-secret-token`        | Bearer token used for authenticated requests.    |
| `BASE`      | `http://127.0.0.1:8080`    | Base URL of the headless executor.               |
| `SESSION_ZIP` | `/tmp/session.zip`      | Path to the workspace/session archive to upload. |
| `IDEMP_KEY` | `job-$(date +%s)`          | Unique idempotency key per `POST /v1/jobs`.      |

Export the variables in your shell:

```bash
export AUTH_TOKEN="test-secret-token"
export BASE="http://127.0.0.1:8080"
export SESSION_ZIP="/tmp/session.zip"
export IDEMP_KEY="job-$(date +%s)"
```

## Health Checks

```bash
curl -sS "${BASE}/health/live"
curl -sS "${BASE}/health/ready"
```

## Create Session

```bash
curl -sS -X POST "${BASE}/v1/sessions" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "name": "My New Session"
}
JSON
```

## Import Session (load existing)

```bash
curl -sS -X PUT "${BASE}/v1/sessions" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/zip" \
  --data-binary @"${SESSION_ZIP}"
```

## Download Session Zip

```bash
curl -sS -X GET "${BASE}/v1/sessions/<session-id>" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -o "<session-id>.zip"
```

## Context Injection (Optional)

Before running ASK (or any mode), you can optionally inject specific files, classes, and methods into the context.
This is useful for precise, caller-driven context control. Note that ASK mode already uses SearchAgent for
automatic codebase discovery, so these endpoints are optional but provide finer-grained control.

### Add Files to Context

```bash
curl -sS -X POST "${BASE}/v1/context/files" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "relativePaths": [
    "src/main/java/com/example/api/UserController.java",
    "src/main/java/com/example/service/UserService.java",
    "src/main/resources/application.properties"
  ]
}
JSON
```

**Response (200 OK):**

```json
{
  "added": [
    { "id": "1", "relativePath": "src/main/java/com/example/api/UserController.java" },
    { "id": "2", "relativePath": "src/main/java/com/example/service/UserService.java" },
    { "id": "3", "relativePath": "src/main/resources/application.properties" }
  ]
}
```

### Add Class Summaries to Context

```bash
curl -sS -X POST "${BASE}/v1/context/classes" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "classNames": [
    "com.example.api.UserController",
    "com.example.service.UserService",
    "com.example.repository.UserRepository"
  ]
}
JSON
```

**Response (200 OK):**

```json
{
  "added": [
    { "id": "4", "className": "com.example.api.UserController" },
    { "id": "5", "className": "com.example.service.UserService" },
    { "id": "6", "className": "com.example.repository.UserRepository" }
  ]
}
```

### Add Method Sources to Context

```bash
curl -sS -X POST "${BASE}/v1/context/methods" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "methodNames": [
    "com.example.service.UserService.findUserById",
    "com.example.service.UserService.updateUser",
    "com.example.api.UserController.getUser"
  ]
}
JSON
```

**Response (200 OK):**

```json
{
  "added": [
    { "id": "7", "methodName": "com.example.service.UserService.findUserById" },
    { "id": "8", "methodName": "com.example.service.UserService.updateUser" },
    { "id": "9", "methodName": "com.example.api.UserController.getUser" }
  ]
}
```

### Add Free-form Text to Context

Add arbitrary text to the current session context. Useful for sharing design notes, stack traces, or snippets that are not yet in the repo.

```bash
curl -sS -X POST "${BASE}/v1/context/text" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "text": "Here is a stack trace and some notes to guide the next action..."
}
JSON
```

Response (200 OK):
```json
{ "id": "context-fragment-id", "chars": 87 }
```

Behavior notes:
- Size limit: Up to 1 MiB (UTF-8 bytes). Larger payloads are rejected with HTTP 400.
- Logging: Only the size is logged; the text content is never logged.
- Blank text is rejected with HTTP 400.
- Fragments added via this endpoint are not auto-removed; if you need job-scoped text that is automatically cleaned up, use inline job seeding in POST /v1/jobs (see below).

### Workflow: Pre-seed Context, Then Ask

```bash
# 1. Add specific files to context
curl -sS -X POST "${BASE}/v1/context/files" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '{"relativePaths": ["src/main/java/com/example/service/UserService.java"]}'

# 2. Add class summaries
curl -sS -X POST "${BASE}/v1/context/classes" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '{"classNames": ["com.example.repository.UserRepository"]}'

# 3. Now run ASK with pre-seeded context
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Based on the code I just added, explain the UserService class structure and its key responsibilities.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

---

## Create Job

All job payloads must include `plannerModel`. The examples below use inline JSON via stdin; swap in real identifiers.

### Job-scoped free-form text seeding (inline)

You can seed free-form text that applies only to the newly created job. These fragments are added just before execution and automatically cleaned up when the job finishes.

Two equivalent payload shapes are supported:
- Top-level array: `contextText: [ "...", "..." ]`
- Nested object: `context: { text: [ "...", "..." ] }`

Notes:
- Each entry must be non-blank and at most 1 MiB (UTF-8 bytes); invalid entries cause a 400 error.
- Only the sizes are logged; text content is never logged.
- Omitting these fields leaves behavior unchanged.

Example (top-level contextText):
```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Use the notes I added to plan the next steps.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "contextText": [
    "Design notes for the logging overhaul...",
    "Known issues and constraints for the auth refactor..."
  ],
  "tags": { "mode": "ASK" }
}
JSON
```

Example (nested context.text):
```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Plan next steps based on the notes provided.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "context": {
    "text": [
      "Observations from the last run...",
      "Risks we need to mitigate in the next iteration..."
    ]
  },
  "tags": { "mode": "ASK" }
}
JSON
```

The job creation response includes `contextTextFragmentIds` when text is accepted, listing the fragment IDs added for the job.

### Minimal Job (default ARCHITECT behavior)

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "echo minimal job",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5"
}
JSON
```

### ARCHITECT Mode (with optional code model override)

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Design a REST client for the new API.",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ARCHITECT"
  }
}
JSON
```

To override the code model in ARCHITECT mode, add `"codeModel": "gpt-5-mini"` (or any supported model) to the payload.

### LUTZ Mode

LUTZ mode automatically decomposes a complex objective into tasks using SearchAgent planning,
then executes each task sequentially with the Architect agent. Ideal for multi-step goals that
benefit from structured decomposition and reasoning before implementation.

#### Example: Complex refactoring with planning

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Refactor the authentication module: improve error handling, add logging, and create unit tests.",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "tags": {
    "mode": "LUTZ"
  }
}
JSON
```

**Response (201 Created):**

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "RUNNING"
}
```

**Event stream (demonstrates two-phase behavior):**

```bash
curl -sS "http://localhost:8080/v1/jobs/550e8400-e29b-41d4-a716-446655440000/events?after=0" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

**Sample events:**

```json
[
  {
    "seq": 1,
    "type": "NOTIFICATION",
    "data": "Job started: 550e8400-e29b-41d4-a716-446655440000"
  },
  {
    "seq": 2,
    "type": "LLM_TOKEN",
    "data": "Planning phase: Analyzing refactoring objectives..."
  },
  {
    "seq": 3,
    "type": "NOTIFICATION",
    "data": "Task list generated with 3 subtasks"
  },
  {
    "seq": 4,
    "type": "LLM_TOKEN",
    "data": "Executing task 1/3: Improve error handling..."
  },
  {
    "seq": 5,
    "type": "LLM_TOKEN",
    "data": "[code changes shown here]"
  },
  {
    "seq": 6,
    "type": "NOTIFICATION",
    "data": "Task 1 completed, progress: 33%"
  },
  {
    "seq": 7,
    "type": "LLM_TOKEN",
    "data": "Executing task 2/3: Add logging..."
  },
  {
    "seq": 8,
    "type": "LLM_TOKEN",
    "data": "[code changes shown here]"
  },
  {
    "seq": 9,
    "type": "NOTIFICATION",
    "data": "Task 2 completed, progress: 66%"
  },
  {
    "seq": 10,
    "type": "LLM_TOKEN",
    "data": "Executing task 3/3: Create unit tests..."
  },
  {
    "seq": 11,
    "type": "LLM_TOKEN",
    "data": "[code changes shown here]"
  },
  {
    "seq": 12,
    "type": "NOTIFICATION",
    "data": "Task 3 completed, progress: 100%"
  }
]
```

**Key characteristics of LUTZ mode:**

- **Two-phase execution**: Planning phase generates tasks, execution phase runs them sequentially
- **Intelligent decomposition**: SearchAgent breaks down complex objectives into manageable subtasks
- **Progress tracking**: Progress updates after each subtask completes (not per-subtask granularity)
- **Full implementation**: Uses both `plannerModel` (for reasoning) and `codeModel` (for code generation)
- **Atomic commits**: Can auto-commit and auto-compress per task based on settings
- **Event streaming**: All phases emit events to `/v1/jobs/{jobId}/events` in real-time

### ASK Mode

ASK mode enables read-only exploration of your codebase using natural language queries.
Under the hood, ASK uses the SearchAgent to discover and inspect code symbols, classes, methods, and file contents without making any modifications or commits.

You can run ASK with no pre-seeded context and let SearchAgent automatically discover relevant code, or optionally pre-seed specific context using the `/v1/context/*` endpoints (see Context Injection above) for precise control.

#### Example: Ask about code structure

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Summarize recent changes in the repo.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

#### Example: Search for symbols

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Find all classes and methods related to authentication. Show me where AuthenticationManager and LoginController are defined.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

ASK will search for these symbols and return their locations and signatures without modifying any code.

#### Example: Inspect file summaries

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Show me the structure of all classes in the 'src/main/java/com/example/service' directory. List their fields and method signatures.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

ASK will retrieve class skeletons with method signatures and fields, providing a high-level overview without retrieving full source code.

#### Key characteristics of ASK mode

- Read-only: No code modifications, commits, or builds
- Intelligent search: Uses SearchAgent to find relevant code based on natural language queries
- Multiple inspection tools: Searches symbols, classes, methods, usages, file contents, and git history
- Responsive: Streams results back via `/v1/jobs/{jobId}/events` as they are discovered
- Requires `plannerModel`: Specify which LLM to use for understanding your query and navigating the codebase
- Ignores `codeModel`: Code model is not used in ASK mode

### SEARCH Mode (read-only repository scan)

SEARCH mode is a read-only mode focused on explicit repository scanning and discovery. It behaves similarly to ASK (no code modifications, no commits), but it lets the caller explicitly choose the scanning LLM via the optional `scanModel` field in the job payload.

Key points:
- Read-only: SEARCH will not write, commit, or modify repository files. No code diffs or git commits are produced.
- Uses a scan model: When creating a SEARCH job you may optionally supply a `scanModel` in the job payload. If provided, the executor will use that model for scanning and searching the repository. If `scanModel` is not provided, the executor falls back to the project's default scan model (via the Service's `getScanModel()`).
- `plannerModel` is still required by the API for validation (kept for API uniformity), but SEARCH prefers `scanModel` to select the actual scanning LLM. `codeModel` is ignored in SEARCH mode.
- Behavior vs ASK: ASK also performs read-only searches using the SearchAgent; SEARCH exposes an explicit `scanModel` override and is intended as the canonical "scan-only" mode when callers want to pick the scanning LLM. Functionally the streamed output and read-only guarantees are the same.
- Behavior vs LUTZ: LUTZ is a two-phase planning+execution workflow (SearchAgent generates a task list, then Architect executes tasks possibly producing code). SEARCH does not plan or execute — it only discovers and returns information.

#### Example: SEARCH with explicit scan model

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Find all usages of AuthenticationManager and summarize where it's referenced.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "scanModel": "gpt-5-mini",
  "tags": {
    "mode": "SEARCH"
  }
}
JSON
```

**Notes:**
- `plannerModel` remains required by the API and is used for validating the job request; SEARCH will use `scanModel` (if present) as the actual scanning model.
- `codeModel` is ignored in SEARCH mode; no code generation is performed.

### CODE Mode

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "replace-with-session-id",
  "taskInput": "Implement a utility to sanitize filenames.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "tags": {
    "mode": "CODE"
  }
}
JSON
```

**Note:** `plannerModel` is still required here for validation, but CODE execution uses `codeModel`. Omit `codeModel`
to fall back to the project's default code model.

## Job Status

```bash
curl -sS "${BASE}/v1/jobs/<job-id>" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

## Job Events

```bash
curl -sS "${BASE}/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

## Cancel Job

```bash
curl -sS -X POST "${BASE}/v1/jobs/<job-id>/cancel" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

## Troubleshooting

- Missing `plannerModel` triggers `HTTP 400` with a validation error (`plannerModel is required`).
- Providing an unknown `plannerModel` yields a job that transitions to `FAILED` with an error containing `MODEL_UNAVAILABLE`.
- In CODE mode, changing `plannerModel` does not alter execution, but it must still be supplied; `codeModel` selects the LLM used for code actions.
- Free-form text that exceeds 1 MiB (UTF-8 bytes) is rejected with `HTTP 400`.
- Blank free-form text is rejected with `HTTP 400`.
