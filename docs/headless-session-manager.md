# Running the Headless Session Manager

The Headless Session Manager (HSM) orchestrates a pool of Headless Executor processes, each attached to its own isolated Git worktree. It exposes a small HTTP API for creating/tearing down sessions and for proxying all job APIs to the correct executor.

Unlike the single-process Headless Executor, the manager scales to multiple concurrent sessions, enforces capacity, and evicts idle executors automatically.

## Configuration

Provide configuration via environment variables or command-line arguments (arguments take precedence).

| Configuration | Env Var | Argument | Required | Description |
|--------------|---------|----------|----------|-------------|
| Manager ID | `MANAGER_ID` | `--manager-id` | No | UUID identifying this manager instance (auto-generated if omitted) |
| Listen Address | `LISTEN_ADDR` | `--listen-addr` | Yes | `host:port` to bind (e.g., `0.0.0.0:8080`) |
| Auth Token | `AUTH_TOKEN` | `--auth-token` | Yes | Master Bearer token for API authentication |
| Pool Size | `POOL_SIZE` | `--pool-size` | Yes | Max concurrent executor processes |
| Worktrees Dir | `WORKTREE_BASE_DIR` | `--worktree-base-dir` | Yes | Directory to store per-session Git worktrees |
| Executor Classpath | `EXECUTOR_CLASSPATH` | `--executor-classpath` | No | Classpath used to launch executors (defaults to current `java.class.path`) |
| Idle Timeout (s) | `IDLE_TIMEOUT_SECONDS` | `--idle-timeout-seconds` | No | Evict executors idle longer than this (default 900) |
| Eviction Interval (s) | `EVICTION_INTERVAL_SECONDS` | `--eviction-interval-seconds` | No | How often to run idle eviction (default 60) |

## Running

Build a runnable JAR (shadow JAR recommended):

```bash
./gradlew shadowJar
```

Run the manager (using the fully-qualified main class):

```bash
java -cp app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.manager.HeadlessSessionManager \
  --listen-addr 0.0.0.0:8080 \
  --auth-token "super-secret-master-token" \
  --pool-size 2 \
  --worktree-base-dir /var/lib/brokk/worktrees
```

Using environment variables:

```bash
export LISTEN_ADDR="0.0.0.0:8080"
export AUTH_TOKEN="super-secret-master-token"
export POOL_SIZE="2"
export WORKTREE_BASE_DIR="/var/lib/brokk/worktrees"

java -cp app/build/libs/brokk-<version>.jar ai.brokk.executor.manager.HeadlessSessionManager
```

Notes:
- The manager launches per-session Headless Executors using the provided or current classpath.
- Idle executors are evicted automatically based on the configured timeout.

## API Endpoints

### Health

- GET `/health/live` (Unauthenticated)
  - Liveness probe; returns manager metadata (ID, version, pool stats).
- GET `/health/ready` (Authenticated)
  - Returns 200 when there is capacity and the worktree base directory is healthy, 503 otherwise.
  - Accepts either the master token or a session token.

### Sessions

- POST `/v1/sessions` (Authenticated)
  - Body: `{ "name": "<session name>", "repoPath": "/abs/path/to/repo", "ref": "optional-ref" }`
  - On success returns 201 with:
    - `sessionId`: UUID of the session
    - `state`: "ready"
    - `token`: a session-scoped Bearer token (valid ~1h) for job APIs

- DELETE `/v1/sessions/{sessionId}` (Master token required)
  - Tears down the corresponding executor and removes its worktree.
  - Returns 204 on success, 404 if not found.

### Jobs (Proxied)

All `/v1/jobs...` endpoints are proxied to the correct executor for the session. They require the session-scoped token returned from POST `/v1/sessions`.

- The manager forwards Content-Type and Idempotency-Key for POST requests.
- For available job endpoints and payloads, see:
  - docs/headless-executor.md
  - docs/headless-executor-testing-with-curl.md
  - docs/headless-executor-events.md

Important: Even in CODE mode, job payloads must include `plannerModel` (validation requirement in the executor). See the executor docs for examples.

## Authentication

- Master token (from `AUTH_TOKEN`):
  - Required for administrative operations (e.g., DELETE session).
  - Also accepted for readiness checks.
  - Explicitly rejected for job APIs.

- Session token:
  - Minted by the manager when creating a session.
  - Scoped to a single session ID.
  - Required for all `/v1/jobs...` APIs.

## Capacity and Eviction

- If the pool is at capacity, POST `/v1/sessions` returns `429` with a `Retry-After` header.
- Idle eviction runs periodically and tears down executors with no recent activity beyond the configured idle timeout.

## Production Deployment

Build the shadow JAR:

```bash
./gradlew shadowJar
```

Run:

```bash
java -cp app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.manager.HeadlessSessionManager \
  --listen-addr 0.0.0.0:8080 \
  --auth-token my-secret-token \
  --pool-size 4 \
  --worktree-base-dir /srv/brokk/worktrees
```

Deploy behind your preferred reverse proxy/load balancer. Keep the master token secret; rotate periodically.
