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

Before running any mode, you can optionally inject specific files, classes, and methods into the context.
This is useful for precise, caller-driven context control. If you want automatic read-only repository discovery,
use SEARCH mode (SearchAgent); these endpoints are optional but provide finer-grained control.

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

### Get Current Task List (Authenticated)

Retrieve the structured content of the current active task list. This endpoint is useful for clients (like TUIs) to provide real-time visibility into the executor's plan. It requires a valid `Authorization: Bearer <AUTH_TOKEN>` header.

```bash
curl -sS -X GET "${BASE}/v1/tasklist" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

**Response (200 OK):**

```json
{
  "bigPicture": "Implement error handling in UserService",
  "tasks": [
    {
      "id": "6f0de4ca-3a41-45ce-8fd4-57fe9dc3e6f1",
      "title": "Add try-catch blocks",
      "text": "Wrap all database calls...",
      "done": true
    },
    {
      "id": "b5cf4f05-52f9-4919-93c8-16dbd2ced1c5",
      "title": "Configure logger",
      "text": "Set up log4j configuration",
      "done": false
    }
  ]
}
```

**Empty Response (200 OK):**
If no task list is active, the executor returns:
```json
{
  "bigPicture": null,
  "tasks": []
}
```

Behavior notes:
- **Polling**: Clients should poll this endpoint periodically (e.g., approximately every 15 seconds) to reflect updates from autonomous agents (like LUTZ or ISSUE modes). This is a suggestion for UI responsiveness, not a protocol requirement.
- **IDs are opaque**: Treat `tasks[].id` as an opaque string identifier. In practice it is typically UUID-like, but clients should not assume a specific format.
- **Auth and errors**: Missing/invalid bearer token returns `401 Unauthorized`; unexpected server failures return `500` with a structured error payload.

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

Notes on request vs persisted fields:
- `sessionId` exists in the request body for compatibility, but it is not currently used to select the active session. Jobs execute against the server's currently active session (set by `POST /v1/sessions` or `PUT /v1/sessions`).
- `sourceBranch` / `targetBranch` are persisted/reserved `JobSpec` fields but are not currently accepted by `POST /v1/jobs`.

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
{
  "events": [
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
  ],
  "nextAfter": 12
}
```

**Key characteristics of LUTZ mode:**

- **Two-phase execution**: Planning phase generates tasks, execution phase runs them sequentially
- **Intelligent decomposition**: SearchAgent breaks down complex objectives into manageable subtasks
- **Progress tracking**: Progress updates after each subtask completes (not per-subtask granularity)
- **Full implementation**: Uses both `plannerModel` (for reasoning) and `codeModel` (for code generation)
- **Atomic commits**: Can auto-commit and auto-compress per task based on settings
- **Event streaming**: All phases emit events to `/v1/jobs/{jobId}/events` in real-time

### ASK Mode

ASK mode returns a single written answer using the current session's Workspace context. It does not modify files, commit changes, or run repository-wide discovery by default.

If you want automatic read-only repository discovery (symbols, usages, file search, etc.), use SEARCH mode.

#### Example: Ask about code structure

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
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

#### Example: Search for symbols (SEARCH mode)

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "Find all classes and methods related to authentication. Show me where AuthenticationManager and LoginController are defined.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "SEARCH"
  }
}
JSON
```

SEARCH will discover these symbols and return their locations and signatures without modifying any code.

#### Example: Inspect file summaries (SEARCH mode)

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "Show me the structure of all classes in the 'src/main/java/com/example/service' directory. List their fields and method signatures.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "SEARCH"
  }
}
JSON
```

SEARCH will retrieve class skeletons with method signatures and fields, providing a high-level overview without retrieving full source code.

#### Key characteristics of ASK mode

- Read-only: No code modifications, commits, or builds
- Single-shot answer: Produces one written answer from the current Workspace context
- Requires `plannerModel`: Specify which LLM to use for generating the answer
- Ignores `codeModel`: Code model is not used in ASK mode
- Optional `preScan`: If `preScan: true`, the executor seeds the Workspace with Context Engine findings before answering

### SEARCH Mode (read-only repository scan)

SEARCH mode is a read-only mode focused on repository scanning and discovery. It does not modify files or create commits, and it lets the caller explicitly choose the scanning LLM via the optional `scanModel` field in the job payload.

Key points:
- Read-only: SEARCH will not write, commit, or modify repository files. No code diffs or git commits are produced.
- Uses a scan model: When creating a SEARCH job you may optionally supply a `scanModel` in the job payload. If provided, the executor will use that model for scanning and searching the repository. If `scanModel` is not provided, the executor falls back to the project's default scan model (via the Service's `getScanModel()`).
- `plannerModel` is still required by the API for validation (kept for API uniformity), but SEARCH prefers `scanModel` to select the actual scanning LLM. `codeModel` is ignored in SEARCH mode.
- Behavior vs ASK: ASK produces a single answer from the current Workspace context. SEARCH runs repository discovery using SearchAgent and returns findings (still read-only).
- Behavior vs LUTZ: LUTZ is a two-phase planning+execution workflow (SearchAgent generates a task list, then Architect executes tasks possibly producing code). SEARCH does not plan or execute — it only discovers and returns information.

#### Example: SEARCH with explicit scan model

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
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

### REVIEW Mode (GitHub PR Review)

REVIEW mode automates code review for GitHub Pull Requests.

#### Option 1: Convenience Endpoint (Recommended)

```bash
curl -sS -X POST "${BASE}/v1/jobs/pr-review" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "owner": "myorg",
  "repo": "myrepo",
  "prNumber": 123,
  "githubToken": "ghp_xxxxxxxxxxxx",
  "plannerModel": "gpt-5"
}
JSON
```

#### Option 2: Standard Endpoint with Tags

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "REVIEW",
    "github_token": "ghp_xxxxxxxxxxxx",
    "repo_owner": "myorg",
    "repo_name": "myrepo",
    "pr_number": "123"
  }
}
JSON
```

**Key characteristics:**
- Fetches PR from GitHub and computes merge-base diff
- Generates LLM-powered code review
- Posts summary comment and inline line comments to the PR
- Skips duplicate comments; falls back to PR comment if inline fails
- `codeModel` is ignored (no code generation)

### ISSUE_WRITER Mode (Create GitHub Issue)

ISSUE_WRITER mode performs read-only repository discovery to draft a high-quality issue report, then creates a new GitHub issue via the GitHub API.

#### Standard Endpoint with Tags

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "Create a GitHub issue describing the NPE when AuthenticationProvider receives a null user, including code evidence.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "tags": {
    "mode": "ISSUE_WRITER",
    "github_token": "ghp_xxxxxxxxxxxx",
    "repo_owner": "myorg",
    "repo_name": "myrepo"
  }
}
JSON
```

**Key characteristics:**
- Read-only to the local repo (no edits, commits, or branch operations)
- Uses repository discovery to gather evidence and draft issue title/body
- Creates a new GitHub issue in the target repository
- Requires `plannerModel` and GitHub tags: `github_token`, `repo_owner`, `repo_name`

### ISSUE Mode (Automated Issue Resolution)

ISSUE mode automates the resolution of GitHub Issues by combining intelligent planning with an iterative solve-and-verify build loop. It fetches the issue, creates a dedicated branch, generates a task list, executes changes, and automatically retries on build failures (controlled by `buildSettings.maxBuildAttempts`, per task).

Additionally, you can cap the overall issue remediation workflow using `maxIssueFixAttempts`: this is the maximum number of ISSUE attempts the job is allowed before stopping (no PR is created after this is exhausted). Default: 20.

Quick mode (skip-verification)
- ISSUE jobs support an optional boolean field `skipVerification` on the job JSON. When `skipVerification` is set to `true`, the executor runs a "quick" or skip-verification flow that skips the usual per-task and final verification/review loops but still performs branch creation, applies code changes, and (when configured) pushes and opens a Pull Request. Use quick mode for faster, lower-cost runs that produce candidate fixes without running tests/lint or the review-bot fix loop.
- Specifically, quick mode behavior:
  - Skips per-task verification gates (the implement → verify → single-fix → re-verify sequence).
  - Skips the per-task test/lint retry loop governed by `buildSettings.maxBuildAttempts`.
  - Skips the final tests/lint + review-bot inline fix sequence and the `maxIssueFixAttempts` final-gate budget.
  - Still performs branch creation/checkout, applies code changes, and (unless delivery is disabled) will push and create a PR. Branch cleanup is still attempted.

> Note: The top-level `skipVerification` field is only honored when `tags.mode == "ISSUE"`. Other modes ignore this field.

#### Verification and fix contract (ISSUE mode — full verification)

For ISSUE mode the executor follows this verification contract when `skipVerification` is omitted or `false`:

- Per-task verification: verify once; if it fails, do one fix attempt; verify once; fail if it is still failing.
- Final verification (tests/lint final gate): retries up to `maxIssueFixAttempts` (default: 20) using the test-then-lint loop; each failing attempt triggers exactly one fix task.

#### Option 1: Convenience Endpoint (Recommended, with skipVerification example)

The convenience endpoint for ISSUE accepts a top-level boolean `skipVerification`. The example below demonstrates quick mode by setting `"skipVerification": true`. This switches the job into the quick/skip-verification flow described above.

```bash
curl -sS -X POST "${BASE}/v1/jobs/issue" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "owner": "myorg",
  "repo": "myrepo",
  "issueNumber": 42,
  "githubToken": "ghp_xxxxxxxxxxxx",
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "maxIssueFixAttempts": 20,
  "buildSettings": {
    "buildLintCommand": "./gradlew classes",
    "testAllCommand": "./gradlew test",
    "testSomeCommand": "./gradlew test --tests",
    "environmentVariables": {
      "JAVA_HOME": "/usr/lib/jvm/java-21"
    },
    "maxBuildAttempts": 5
  },
  "skipVerification": true
}
JSON
```

Use the same endpoint without `skipVerification` (or with it set to `false`) to run the full verification pipeline.

#### Option 2: Standard Endpoint with Tags (ISSUE mode + skipVerification)

When creating ISSUE jobs via the generic `/v1/jobs` endpoint, include `tags.mode = "ISSUE"`. You may also include the top-level `"skipVerification": true` field — it will be honored only because `tags.mode == "ISSUE"`.

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "Ignored for ISSUE mode; issue body drives the work.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "buildSettings": {
    "buildLintCommand": "./gradlew classes",
    "testAllCommand": "./gradlew test",
    "maxBuildAttempts": 3
  },
  "maxIssueFixAttempts": 20,
  "skipVerification": true,
  "tags": {
    "mode": "ISSUE",
    "github_token": "ghp_xxxxxxxxxxxx",
    "repo_owner": "myorg",
    "repo_name": "myrepo",
    "issue_number": "42"
  }
}
JSON
```

If you omit `skipVerification` or set it to `false`, the executor will run the full verification and review pipeline described above.

#### Streaming Events

Observe the iterative workflow by streaming job events:

```bash
curl -sS "${BASE}/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

**Sample Events (full verification flow):**

```json
{
  "events": [
    {
      "seq": 1,
      "type": "NOTIFICATION",
      "data": "Job started: <job-id>"
    },
    {
      "seq": 2,
      "type": "LLM_TOKEN",
      "data": "Planning phase: Analyzing issue #42..."
    },
    {
      "seq": 3,
      "type": "NOTIFICATION",
      "data": "Task list generated with 2 subtasks"
    },
    {
      "seq": 4,
      "type": "LLM_TOKEN",
      "data": "Executing task 1/2: Implement fix..."
    },
    {
      "seq": 5,
      "type": "NOTIFICATION",
      "data": "Running build verification..."
    },
    {
      "seq": 6,
      "type": "NOTIFICATION",
      "data": "Build verification passed"
    },
    {
      "seq": 7,
      "type": "LLM_TOKEN",
      "data": "Executing task 2/2: Add tests..."
    },
    {
      "seq": 8,
      "type": "NOTIFICATION",
      "data": "Running build verification..."
    },
    {
      "seq": 9,
      "type": "NOTIFICATION",
      "data": "Build failed, attempting fix (1/3)..."
    },
    {
      "seq": 10,
      "type": "LLM_TOKEN",
      "data": "Fixing build error..."
    },
    {
      "seq": 11,
      "type": "NOTIFICATION",
      "data": "Build verification passed"
    },
    {
      "seq": 12,
      "type": "NOTIFICATION",
      "data": "Task 2 completed, progress: 100%"
    }
  ],
  "nextAfter": 12
}
```

**Key characteristics (summary):**
- Fetches issue title and body from GitHub.
- Branching: Automatically creates a branch named `brokk/issue-{number}`.
- Uses LUTZ-style planning to decompose the issue into tasks.
- Executes each task with ArchitectAgent (uses `plannerModel` + `codeModel`).
- In full verification mode: runs per-task build verification and limited automatic fixes, and capping overall attempts via `maxIssueFixAttempts`.
- In quick/skip-verification mode (`skipVerification=true`): verification and review loops are skipped; branch creation, applying changes, and optional PR creation still occur.
- `buildSettings` overrides project defaults for the job duration (used by full verification).
- `codeModel` is optional; defaults to project default if omitted.

## Job Status

```bash
curl -sS "${BASE}/v1/jobs/<job-id>" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

**Response (200 OK):**

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "COMPLETED",
  "startTime": 1734567890123,
  "endTime": 1734567890456,
  "progressPercent": 100,
  "result": null,
  "error": null,
  "metadata": {}
}
```

Possible `state` values: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.

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

## Job-level model overrides (optional): reasoningLevel and temperature

You can pass these optional top-level fields in any `POST /v1/jobs` payload:

- `reasoningLevel` (string): `"DEFAULT"`, `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"DISABLE"` — applies to the planner model.
- `reasoningLevelCode` (string): `"DEFAULT"`, `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"DISABLE"` — applies to the code model (CODE and ARCHITECT modes).
- `temperature` (number): `0.0` to `2.0` inclusive — applies to the planner model.
- `temperatureCode` (number): `0.0` to `2.0` inclusive — applies to the code model (CODE and ARCHITECT modes).

If omitted, the executor uses the model/service defaults.

### Example: CODE mode with overrides

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "taskInput": "Implement a utility to sanitize filenames.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "reasoningLevel": "MEDIUM",
  "reasoningLevelCode": "LOW",
  "temperature": 0.0,
  "temperatureCode": 0.2,
  "tags": {
    "mode": "CODE"
  }
}
JSON
```

## Troubleshooting

- Missing `plannerModel` triggers `HTTP 400` with a validation error (`plannerModel is required`).
- Providing an unknown `plannerModel` yields a job that transitions to `FAILED` with an error containing `MODEL_UNAVAILABLE`.
- In CODE mode, changing `plannerModel` does not alter execution, but it must still be supplied; `codeModel` selects the LLM used for code actions.
- `reasoningLevel` must be one of `"DEFAULT"`, `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"DISABLE"`; invalid values trigger `HTTP 400`.
- `temperature` (planner model) must be a number between `0.0` and `2.0` inclusive; invalid values trigger `HTTP 400`.
- `temperatureCode` (code model) must be a number between `0.0` and `2.0` inclusive; invalid values trigger `HTTP 400`.
- Free-form text that exceeds 1 MiB (UTF-8 bytes) is rejected with `HTTP 400`.
- Blank free-form text is rejected with `HTTP 400`.
