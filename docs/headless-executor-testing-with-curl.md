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

### ASK Mode (read-only)

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
to fall back to the project’s default code model.

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

## Fix GitHub Issue

```bash
curl -sS -X POST "${BASE}/v1/issues/42/fix" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "owner": "torvalds",
  "repo": "linux",
  "issueNumber": 42,
  "githubToken": "ghp_...",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "tags": {
    "mode": "ARCHITECT"
  }
}
JSON
```

**Response (201 Created):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "queued",
  "issue": {
    "number": 42,
    "title": "Fix broken feature",
    "url": "https://github.com/torvalds/linux/issues/42"
  },
  "worktreeBranch": "fix/42-fix-broken-feature",
  "prUrl": null
}
```

**Important Notes:**
- The `githubToken` must have `repo` and `pull_request` scopes to create pull requests
- The endpoint creates a Git worktree automatically with a branch name derived from the issue title
- The executor runs asynchronously in ARCHITECT mode to generate fixes
- Upon successful completion, a pull request is automatically created against the repository's default branch
- The response includes the pull request URL (`prUrl`) once the PR is created; initially it will be `null`

**Example with GitHub Personal Access Token:**
```bash
# First, create a Personal Access Token (PAT) at https://github.com/settings/tokens
# Scopes required: repo, pull_request

export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

curl -sS -X POST "${BASE}/v1/issues/42/fix" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "owner": "my-org",
  "repo": "my-project",
  "issueNumber": 42,
  "githubToken": "${GITHUB_TOKEN}",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5"
}
JSON
```

## Troubleshooting

- Missing `plannerModel` triggers `HTTP 400` with a validation error (`plannerModel is required`).
- Providing an unknown `plannerModel` yields a job that transitions to `FAILED` with an error containing `MODEL_UNAVAILABLE`.
- In CODE mode, changing `plannerModel` does not alter execution, but it must still be supplied; `codeModel` selects the LLM used for code actions.
- For the issue fix endpoint, invalid GitHub tokens return `HTTP 400` with a validation error (`Invalid GitHub token: authentication failed`).
- Issue numbers must be positive integers; invalid issue numbers in the path return `HTTP 400`.
- If another job is currently executing, attempting to create an issue fix returns `HTTP 409 Conflict`.
