# Running the Headless Executor

The Headless Executor runs Brokk sessions in a server mode, controllable via HTTP+JSON API. It's designed for remote execution, CI/CD pipelines, and programmatic task automation.

## Configuration

The executor requires the following configuration, provided via **environment variables** or **command-line arguments** (arguments take precedence):

| Configuration | Env Var | Argument | Required | Description |
|--------------|---------|----------|----------|-------------|
| Executor ID | `EXEC_ID` | `--exec-id` | Yes | UUID identifying this executor instance |
| Listen Address | `LISTEN_ADDR` | `--listen-addr` | Yes | Host:port to bind (e.g., `0.0.0.0:8080`) |
| Auth Token | `AUTH_TOKEN` | `--auth-token` | Yes | Bearer token for API authentication |
| Workspace Dir | `WORKSPACE_DIR` | `--workspace-dir` | Yes | Path to the project workspace |
| Sessions Dir | `SESSIONS_DIR` | `--sessions-dir` | No | Path to store sessions (defaults to `<workspace>/.brokk/sessions`) |

## Running from Source

Run the headless executor with Gradle:

```bash
./gradlew :app:runHeadlessExecutor
```

### Examples

**Using environment variables:**
```bash
export EXEC_ID="550e8400-e29b-41d4-a716-446655440000"
export LISTEN_ADDR="localhost:8080"
export AUTH_TOKEN="my-secret-token"
export WORKSPACE_DIR="/path/to/workspace"
./gradlew :app:runHeadlessExecutor
```

## ASK Mode: Read-Only Codebase Search

ASK mode enables **interactive, read-only exploration** of your repository. It leverages the internal `SearchAgent` to understand your queries and automatically discover relevant code without making any modifications.

### How ASK Works

When you submit an ASK job, the system:
1. Receives your natural language query
2. Uses the SearchAgent to intelligently search the codebase
3. Discovers relevant files, symbols, and code structures
4. Streams results back as events (no UI prompts needed in headless mode)
5. Provides findings without making any commits or code changes

### Optional pre-scan (seed workspace)

ASK supports an optional repository pre-scan that can be used to seed the Workspace with additional context before the ASK reasoning runs. To enable this behavior, include the boolean flag `"preScan": true` in the job payload. When `preScan` is true the executor will run a lightweight repository scan prior to reasoning; this can improve recall for large repos or vague queries.

Model selection semantics for the pre-scan:
- The ASK reasoning always uses `plannerModel` (this remains required).
- The pre-scan uses the `scanModel` if provided in the job payload (string). If `scanModel` is omitted, the executor falls back to the project's default scan model (Service.getScanModel()).
- `codeModel` is ignored for ASK (ASK is read-only).

Example job fields for pre-scan behavior:
- `"plannerModel": "gpt-5"`  — required for ASK reasoning
- `"preScan": true`         — enable repository pre-scan before reasoning
- `"scanModel": "gpt-5-mini"` — optional override used only for the pre-scan step

### Supported Search & Inspection Capabilities

- **Symbol search**: Find class, method, and field definitions by name or pattern
- **Class inspection**: Get full source code or skeleton views of classes
- **Method lookup**: Retrieve specific method implementations
- **File summaries**: Get a quick overview of class structures in files (fields, method signatures)
- **Usages**: Discover where symbols are used throughout the codebase
- **File search**: Search for files by name or content patterns
- **Git history**: Search commit messages for context about changes
- **Related code**: Automatically find related classes and dependencies

### Configuration

ASK mode requires:
- `plannerModel`: The LLM model to use for understanding queries and searching
- `preScan` (optional): When `true`, run a repository pre-scan that seeds the Workspace before reasoning
- `scanModel` (optional): Model name to use for the pre-scan; if omitted, the project default scan model is used
- `autoCompress` (optional): Enable automatic context compression (recommended for large codebases)

ASK mode **ignores** `codeModel` since it does not perform code generation.

### Example Workflows

Basic ASK (no pre-scan):

```bash
# Submit a standard ASK query (no pre-scan)
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ask-query-001" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Where is the UserService class defined? Show me its public methods and explain what this class does.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-4",
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

ASK with pre-scan (use explicit scan model):

```bash
# Submit an ASK query and request a repository pre-scan using a chosen scan model.
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ask-prescan-001" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Find UserService and summarize its responsibilities and public methods.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "scanModel": "gpt-5-mini",   # optional: model to use for the pre-scan step
  "preScan": true,
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

ASK with pre-scan (use project default scan model):

```bash
# Submit ASK with preScan=true but omit scanModel to use the project's default scan model.
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ask-prescan-002" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Summarize where AuthenticationManager is used across the repo.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "preScan": true,
  "tags": {
    "mode": "ASK"
  }
}
JSON
```

#### Streaming results

After submitting any ASK job, stream events to observe discovery and results:

```bash
curl -sS "http://localhost:8080/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer my-secret-token" | tail -f
```

## SEARCH Mode: Read-Only Repository Scan (explicit scan model)

SEARCH mode is a new read-only mode focused on repository scanning and discovery. It is operationally similar to ASK in that it performs read-only exploration and returns findings without producing code changes or commits, but it provides explicit control over which LLM model performs the repository scan.

Key points:
- Read-only: SEARCH will not write, commit, or modify repository files. No code diffs or git commits are produced.
- Uses a scan model: When creating a SEARCH job you may optionally supply a `scanModel` in the job payload. If provided, the executor will use that model for scanning and searching the repository. If `scanModel` is not provided, the executor falls back to the project's default scan model (via the Service's `getScanModel()`).
- `plannerModel` is still required by the API for validation (this is kept for API uniformity), but SEARCH prefers `scanModel` to select the actual scanning LLM. `codeModel` is ignored in SEARCH mode.
- Behavior vs ASK: ASK also performs read-only searches using the SearchAgent; SEARCH exposes an explicit `scanModel` override and is intended as the canonical "scan-only" mode when callers want to pick the scanning LLM. Functionally the streamed output and read-only guarantees are the same.
- Behavior vs LUTZ: LUTZ is a two-phase planning+execution workflow (SearchAgent generates a task list, then Architect executes tasks possibly producing code). SEARCH does not plan or execute — it only discovers and returns information.

### Example: SEARCH with explicit scan model

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs" \
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

## LUTZ Mode: Two-Phase Planning & Execution

LUTZ mode enables **intelligent task decomposition followed by sequential execution**. It's ideal for complex objectives that benefit from structured planning before implementation.

### How LUTZ Works

When you submit a LUTZ job, the system executes in two phases:

1. **Phase 1: Task Planning** — SearchAgent analyzes your objective and generates a structured task list
   - Uses your `plannerModel` to understand the goal
   - Generates an ordered set of subtasks
   - Persists the task list in the session context

2. **Phase 2: Task Execution** — ArchitectAgent executes each generated subtask sequentially
   - Iterates through incomplete tasks from the generated list
   - Uses `plannerModel` for reasoning and `codeModel` for implementation
   - Honors `autoCommit` and `autoCompress` settings per task
   - Streams events and updates progress after each task

### Configuration

LUTZ mode requires:
- `plannerModel`: The LLM model for planning (task decomposition) and reasoning during execution
- `codeModel` (optional): The LLM model for code generation; defaults to project default if not specified
- `autoCommit` (optional): Whether to auto-commit changes after each task (default: false)
- `autoCompress` (optional): Whether to auto-compress context history after each task (default: false)

### Key Differences from ARCHITECT

| Aspect | ARCHITECT | LUTZ |
|--------|-----------|------|
| **Planning** | Implicit per-task reasoning | Explicit upfront planning phase |
| **Task List** | User provides via `taskInput` (one per request) | Auto-generated from objective |
| **Workflow** | Direct execution of user tasks | SearchAgent → task gen → Architect execution |
| **Best For** | Single-step objectives, quick iterations | Complex multi-step goals, structured decomposition |

### Example Workflow

```bash
# 1. Create a session
curl -sS -X POST "http://localhost:8080/v1/sessions" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "name": "LUTZ Planning Session"
}
JSON
# Returns: { "sessionId": "<session-id>" }

# 2. Submit a LUTZ job with a complex objective
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: lutz-job-001" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Add comprehensive error handling to the UserService class and ensure all exceptions are properly logged.",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "tags": {
    "mode": "LUTZ"
  }
}
JSON
# Returns: { "jobId": "<job-id>", "state": "RUNNING", ... }

# 3. Stream events to see planning and execution
curl -sS "http://localhost:8080/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer my-secret-token" | tail -f
# Events will show:
# - Planning phase: SearchAgent generating subtasks
# - Execution phase: ArchitectAgent executing each task, progress updates
```

### Event Stream Semantics

LUTZ jobs emit events following this pattern:

1. **Planning Events**: Task list generation and context updates from SearchAgent
2. **Execution Events**: Per-task progress, code modifications, and completions
3. **Final Events**: Job completion with diff and summary

**Progress is updated after each top-level task completes (not per subtask).**

## API Endpoints

Once running, the executor exposes the following endpoints:

### Health & Info (Unauthenticated)

- **`GET /health/live`** - Liveness check, returns `200` if server is running
- **`GET /health/ready`** - Readiness check, returns `200` if session loaded, `503` otherwise
- **`GET /v1/executor`** - Returns executor info (ID, version, protocol version)

### Session Management (Authenticated)

- **`POST /v1/sessions`** - Create a new session
  - Body: `{ "name": "<session name>" }`
  - Returns: `{ "sessionId": "<uuid>", "name": "<session name>" }`

- **`PUT /v1/sessions`** - Upload an existing session zip file
  - Content-Type: `application/zip`
  - Returns: `{ "sessionId": "<uuid>" }`

### Job Management (Authenticated)

- **`POST /v1/jobs`** - Create and execute a job
  - Requires `Idempotency-Key` header for safe retries
  - Body: `JobSpec` JSON with task input and execution mode (ARCHITECT, LUTZ, ASK, SEARCH, or CODE)
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`
  - **SEARCH mode**: Set `"tags": { "mode": "SEARCH" }` to run a read-only repository scan. Optionally include `"scanModel": "<model>"` to override the default scan model used for repository scanning. `plannerModel` is still required by the API for validation.
  - **ASK mode**: Set `"tags": { "mode": "ASK" }` for ad-hoc read-only searches (uses service default scan model unless otherwise configured).
  - **CODE mode**: Set `"tags": { "mode": "CODE" }` for single-shot code generation
  - **LUTZ mode**: Set `"tags": { "mode": "LUTZ" }` to enable two-phase planning and execution (SearchAgent generates a task list, then ArchitectAgent executes tasks sequentially), honoring autoCommit and autoCompress
  - **ARCHITECT mode** (default): Orchestrates multi-step planning and implementation

- **`GET /v1/jobs/{jobId}`** - Get job status
  - Returns: `JobStatus` JSON with current state and metadata

- **`GET /v1/jobs/{jobId}/events`** - Get job events (supports polling)
  - Query params: `?after={seq}&limit={n}`
  - Returns: Array of `JobEvent` objects

- **`POST /v1/jobs/{jobId}/cancel`** - Cancel a running job
  - Returns: Updated `JobStatus`

- **`GET /v1/jobs/{jobId}/diff`** - Get git diff of job changes
  - Returns: Plain text diff

### Authentication

Authenticated endpoints require the `Authorization` header:

```
Authorization: Bearer <AUTH_TOKEN>
```

Requests without a valid token receive `401 Unauthorized`.

## Production Deployment

Build the shadow JAR:

```bash
./gradlew shadowJar
```

Run the JAR:

```bash
java -co app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.HeadlessExecutorMain \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr 0.0.0.0:8080 \
  --auth-token my-secret-token \
  --workspace-dir /path/to/workspace
```

**Note:** The JAR requires the fully-qualified main class (`ai.brokk.executor.HeadlessExecutorMain`) as the first argument.
