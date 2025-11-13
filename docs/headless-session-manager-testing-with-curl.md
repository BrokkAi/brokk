# Headless Session Manager: curl Examples

This guide demonstrates end-to-end usage of the manager with curl. The manager orchestrates executors and proxies job APIs. Job payload semantics are the same as the Headless Executor; remember that `plannerModel` is required for all jobs.

## Environment

| Variable       | Example Value                 | Description                                     |
|----------------|-------------------------------|-------------------------------------------------|
| `AUTH_TOKEN`   | `test-master-token`           | Master Bearer token for the manager             |
| `BASE`         | `http://127.0.0.1:8080`       | Base URL of the manager                         |
| `REPO`         | `/home/me/src/my-repo`        | Absolute path to a local Git repo               |
| `IDEMP_KEY`    | `job-$(date +%s)`             | Idempotency key per `POST /v1/jobs`             |

```bash
export AUTH_TOKEN="test-master-token"
export BASE="http://127.0.0.1:8080"
export REPO="/home/me/src/my-repo"
export IDEMP_KEY="job-$(date +%s)"
```

If you use `jq`, you can conveniently extract JSON fields.

## Health Checks

```bash
curl -sS "${BASE}/health/live"

curl -sS "${BASE}/health/ready" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

## Create a Session

```bash
CREATE_RESP=$(curl -sS -X POST "${BASE}/v1/sessions" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  --data @- <<'JSON'
{
  "name": "My Session",
  "repoPath": "'"${REPO}"'",
  "ref": "main"
}
JSON
)

echo "${CREATE_RESP}"
```

Export the session-scoped token and session ID:

```bash
SESSION_ID=$(echo "${CREATE_RESP}" | jq -r '.sessionId')
SESSION_TOKEN=$(echo "${CREATE_RESP}" | jq -r '.token')

echo "SESSION_ID=${SESSION_ID}"
echo "SESSION_TOKEN=${SESSION_TOKEN}"
```

## Create a Job

All job payloads must include `plannerModel`. The manager proxies to the per-session executor.

Minimal job:

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${SESSION_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "'"${SESSION_ID}"'",
  "taskInput": "echo from HSM",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5"
}
JSON
```

ASK mode:

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${SESSION_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "'"${SESSION_ID}"'",
  "taskInput": "Summarize recent changes.",
  "autoCommit": false,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "tags": { "mode": "ASK" }
}
JSON
```

CODE mode:

```bash
curl -sS -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${SESSION_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  --data @- <<'JSON'
{
  "sessionId": "'"${SESSION_ID}"'",
  "taskInput": "Implement a small utility.",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "tags": { "mode": "CODE" }
}
JSON
```

## Job Status and Events

```bash
curl -sS "${BASE}/v1/jobs/<job-id>" \
  -H "Authorization: Bearer ${SESSION_TOKEN}"

curl -sS "${BASE}/v1/jobs/<job-id>/events?after=0" \
  -H "Authorization: Bearer ${SESSION_TOKEN}"
```

Cancel a job:

```bash
curl -sS -X POST "${BASE}/v1/jobs/<job-id>/cancel" \
  -H "Authorization: Bearer ${SESSION_TOKEN}"
```

Diff:

```bash
curl -sS "${BASE}/v1/jobs/<job-id>/diff" \
  -H "Authorization: Bearer ${SESSION_TOKEN}"
```

## Teardown the Session

Use the master token:

```bash
curl -i -X DELETE "${BASE}/v1/sessions/${SESSION_ID}" \
  -H "Authorization: Bearer ${AUTH_TOKEN}"
```

## Troubleshooting

- `401 Unauthorized`: Missing/invalid token. Use the session token for jobs; master token for admin.
- `403 Forbidden`: Using a master token on job APIs; those require the session token.
- `429` with `Retry-After`: Pool is at capacity; retry later.
- `503` from `/health/ready`: No capacity or worktree directory not healthy.
- Job payload errors: See Headless Executor docs; `plannerModel` is required and validated there.
