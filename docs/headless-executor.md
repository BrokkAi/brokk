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
- `autoCompress` (optional): Enable automatic context compression (recommended for large codebases)

ASK mode **ignores** `codeModel` since it does not perform code generation.

### Example Workflow

```bash
# 1. Create a session
curl -sS -X POST "http://localhost:8080/v1/sessions" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "name": "Code Investigation Session"
}
JSON
# Returns: { "sessionId": "<session-id>" }

# 2. Submit an ASK query
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
# Returns: { "jobId": "<job-id>", "state": "RUNNING", ... }

# 3. Stream events to see the discovery process
curl -sS "http://localhost:8080/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer my-secret-token" | tail -f
```

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
  - Body: `JobSpec` JSON with task input and execution mode (ARCHITECT, ASK, or CODE)
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`
  - **ASK mode**: Set `"tags": { "mode": "ASK" }` for read-only codebase search
  - **CODE mode**: Set `"tags": { "mode": "CODE" }` for single-shot code generation
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