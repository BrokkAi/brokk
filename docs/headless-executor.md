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
  - Body: `JobSpec` JSON with task input
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`

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