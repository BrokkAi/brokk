# Brokk Session Management API

This package provides REST endpoints for managing Brokk sessions. Each session creates a Git worktree, spawns a headless executor process, and provides isolated workspace for AI-assisted development.

## Architecture

- **SessionController**: HTTP endpoints for session lifecycle and operations
- **SessionRegistry**: Thread-safe registry tracking active sessions
- **HeadlessSessionManager**: Manages executor process lifecycle, port assignment, and health checks
- **DTOs**: Request/response objects (CreateSessionRequest, SessionSummary, PromptRequest, etc.)

## Session API Endpoints

### Create Session
```
POST /api/sessions
Content-Type: application/json

{
  "name": "Feature Implementation"  // optional
}

Response 201:
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Feature Implementation",
  "branch": "session/550e8400",
  "worktreePath": "/path/to/repo/.brokk/worktrees/wt1",
  "port": 45123,
  "authToken": "xyz123abc..."
}
```

### List Sessions
```
GET /api/sessions

Response 200:
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Feature Implementation",
    "branch": "session/550e8400",
    "worktreePath": "/path/to/repo/.brokk/worktrees/wt1",
    "port": 45123
  }
]
```

### Get Session Details
```
GET /api/sessions/{sessionId}

Response 200: (same as create response)
```

### Delete Session
```
DELETE /api/sessions/{sessionId}

Response 204: No Content
```

### Send Prompt
```
POST /api/sessions/{sessionId}/prompt
Content-Type: application/json

{
  "prompt": "Add unit tests for the UserService class"
}

Response 200:
{
  "jobId": "job-abc123",
  "status": "queued"
}
```

### Stream Output (SSE)
```
GET /api/sessions/{sessionId}/stream

Response 200:
Content-Type: text/event-stream

data: {"seq":1,"type":"output","content":"Starting task..."}

data: {"seq":2,"type":"output","content":"Analyzing code..."}
```

### Merge Session
```
POST /api/sessions/{sessionId}/merge
Content-Type: application/json

{
  "mode": "merge",     // "merge", "squash", or "rebase"
  "close": true        // whether to close session after successful merge
}

Response 200 (success):
{
  "status": "merged",
  "mode": "MERGE_COMMIT",
  "defaultBranch": "main",
  "sessionBranch": "session/550e8400",
  "fastForward": false,
  "conflicts": false,
  "message": "Merge completed successfully"
}

Response 409 (conflicts):
{
  "status": "conflicts",
  "mode": "MERGE_COMMIT",
  "defaultBranch": "main",
  "sessionBranch": "session/550e8400",
  "fastForward": false,
  "conflicts": true,
  "message": "Merge conflicts detected in: file1.txt, file2.java"
}
```

## Executor Endpoints (Internal)

Each session spawns a headless executor on a dynamic port. The executor provides:

### Health Checks
- `GET /health/live` - Always returns 200 if process is running
- `GET /health/ready` - Returns 200 only when session is loaded and ready

### Executor Info
- `GET /v1/executor` - Returns executor metadata (execId, version, protocolVersion)

### Job Management
- `POST /v1/jobs` - Create new job (requires Idempotency-Key header)
- `GET /v1/jobs/{jobId}` - Get job status
- `GET /v1/jobs/{jobId}/events` - Get job events (paginated)
- `GET /v1/jobs/{jobId}/events/stream` - Stream job events (SSE)
- `POST /v1/jobs/{jobId}/cancel` - Cancel job
- `GET /v1/jobs/{jobId}/diff` - Get git diff for job

### Session Stream
- `GET /v1/stream` - Stream events from currently active job (SSE)

## CORS Configuration

CORS is configured via the `CORS_ALLOWED_ORIGIN` environment variable:

```bash
export CORS_ALLOWED_ORIGIN=http://localhost:5174
```

Default: `http://localhost:5174`

Both the main session API and executor processes use this configuration.

Allowed methods: `GET, POST, DELETE, OPTIONS`  
Allowed headers: `Content-Type, Authorization, Idempotency-Key`

## Authentication

- **Session API → Executor**: SessionController automatically includes the executor's auth token when forwarding requests
- **Frontend → Session API**: No authentication required (trusted localhost environment)
- **Frontend → Executor**: Can connect directly using the executor's port and auth token from session details

## Example Usage Flow

1. **Create Session**: `POST /api/sessions` → Get session details with port and auth token
2. **Send Prompt**: `POST /api/sessions/{id}/prompt` → Get job ID
3. **Stream Output**: `GET /api/sessions/{id}/stream` → Receive live updates via SSE
4. **Merge Session**: `POST /api/sessions/{id}/merge` → Integrate changes to default branch
5. **Delete Session**: `DELETE /api/sessions/{id}` → Clean up worktree and terminate executor

## Testing

Unit tests are provided for:
- `HeadlessSessionManagerTest`: Process spawning, port assignment, shutdown behavior
- `SessionControllerMergeTest`: Merge endpoint with both successful and conflicting scenarios

Run tests with: `./gradlew test`
