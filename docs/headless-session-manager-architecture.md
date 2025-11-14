# Headless Session Manager Architecture

The Headless Session Manager (HSM) runs as a lightweight control plane that provisions per-session Git worktrees and launches one Headless Executor per session. It exposes a minimal HTTP API and proxies job calls to the correct executor.

## Components

- HeadlessSessionManager
  - HTTP server exposing `/health/*`, `/v1/sessions`, and proxying `/v1/jobs...`.
  - Maintains capacity limits and idle eviction.
- WorktreeProvisioner
  - Creates and manages isolated Git worktrees under `WORKTREE_BASE_DIR` for each session.
- ExecutorPool
  - Spawns executor processes (one per session), tracks their host/port, internal auth token, and last activity time.
  - Can evict idle executors and perform targeted shutdowns.
- TokenService
  - Mints and validates session-scoped tokens (default validity ~1 hour).
  - Enforces that job APIs are accessed with a session token; master token is rejected for job APIs.
- SimpleHttpServer
  - Embedded HTTP server used by the manager for request handling.

## Request Flow

1. Client calls `POST /v1/sessions` with Authorization: Bearer `<master or session token>` and a body containing:
   - `name`: Session name
   - `repoPath`: Absolute path to an existing local Git repo
   - `ref` (optional): Branch, tag, or commit-ish
2. Manager provisions a worktree and spawns a Headless Executor process for the session.
3. Manager calls the executor's `/v1/sessions` to initialize the session and retrieves the `sessionId`.
4. Manager mints a session-scoped token and returns it with `sessionId` to the client.
5. Client uses the session token for all `/v1/jobs...` calls. The manager proxies requests to the executor, forwarding:
   - Method, path, query string
   - Content-Type and Idempotency-Key headers (for POST)
   - Request body (for POST)
6. Responses are streamed back to the client with the executor's status code and content type.

## Authorization

- Master token (AUTH_TOKEN):
  - Required for `DELETE /v1/sessions/{id}`.
  - Accepted for readiness checks.
  - Not accepted for job APIs.
- Session token:
  - Encodes the session ID.
  - Required for `/v1/jobs...`.
  - Extracted from the Authorization header and used by the manager to route to the correct executor.

## Capacity and Eviction

- Pool size limits concurrent executors. If full, session creation returns `429` and sets `Retry-After`.
- Idle eviction runs every `EVICTION_INTERVAL_SECONDS`; executors without recent activity for longer than `IDLE_TIMEOUT_SECONDS` are shut down.

## Error Semantics

- JSON error payloads include a stable `code` string and message when applicable.
- Common cases:
  - `UNAUTHORIZED` or `FORBIDDEN`: Bad/missing token or token used in the wrong context.
  - `CAPACITY_EXCEEDED` or `SPAWN_FAILED`: Session creation failures.
  - `SESSION_NOT_FOUND`: No active executor for the token's session.
  - `PROVISIONER_UNHEALTHY`: Base worktree directory is missing or not writable.
- Unexpected exceptions are logged server-side.

## Operational Guidance

- Secure the master token; rotate regularly.
- Provision a fast `WORKTREE_BASE_DIR` with sufficient storage.
- Monitor logs for eviction cycles and spawn failures.
- For job semantics (modes, events, and required fields like `plannerModel`), see:
  - docs/headless-executor.md
  - docs/headless-executor-events.md
  - docs/headless-executor-testing-with-curl.md
