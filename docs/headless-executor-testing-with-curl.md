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
