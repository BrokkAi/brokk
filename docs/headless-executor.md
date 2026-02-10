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
| Brokk API Key | `BROKK_API_KEY` | `--brokk-api-key` | No | Per-executor Brokk API key; overrides global config. If not provided, falls back to the globally configured key |
| LLM Proxy Setting | `PROXY_SETTING` | `--proxy-setting` | No | LLM proxy target: `BROKK` (https://proxy.brokk.ai), `LOCALHOST` (http://localhost:4000), or `STAGING` (https://staging.brokk.ai). If not provided, uses global config |

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

## Download a Session Zip

To download a previously stored session zip, issue a GET request to the session sub-path.

```bash
curl -sS -X GET "http://localhost:8080/v1/sessions/<session-id>" \
  -H "Authorization: Bearer my-secret-token" \
  -o "<session-id>.zip"
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
  "scanModel": "gpt-5-mini",
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

## REVIEW Mode: GitHub Pull Request Review

REVIEW mode enables **automated code review of GitHub Pull Requests**. It fetches the PR, computes the diff against the base branch, generates an LLM-powered review, and posts comments directly to GitHub.

### How REVIEW Works

When you submit a REVIEW job, the system:
1. Authenticates with GitHub using the provided token
2. Fetches the PR details (base branch, head SHA)
3. Computes the merge-base diff between the PR branch and base branch
4. Generates an LLM-powered code review using the planner model
5. Posts a summary comment to the PR
6. Posts inline line comments for specific issues found in the code

### Configuration

REVIEW mode requires:
- `plannerModel`: The LLM model for generating the review
- GitHub PR metadata (passed via tags or the convenience endpoint):
  - `github_token`: GitHub personal access token with repo access
  - `repo_owner`: Repository owner (user or organization)
  - `repo_name`: Repository name
  - `pr_number`: Pull request number

REVIEW mode **ignores** `codeModel` and `scanModel` since it performs review, not code generation.

### Example: Using the Convenience Endpoint

The easiest way to create a PR review job is via the dedicated endpoint:

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs/pr-review" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: review-001" \
  --data @- <<'JSON'
{
  "owner": "myorg",
  "repo": "myrepo",
  "prNumber": 123,
  "githubToken": "ghp_xxxxxxxxxxxx",
  "plannerModel": "gpt-4"
}
JSON
```

### Example: Using Tags with Standard Job Endpoint

Alternatively, use the standard `/v1/jobs` endpoint with mode tags:

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: review-002" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "",
  "autoCommit": false,
  "autoCompress": false,
  "plannerModel": "gpt-4",
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

### Key Characteristics of REVIEW Mode

- **Full GitHub integration**: Fetches PR, computes diff, posts comments
- **Intelligent diff analysis**: Uses merge-base for accurate diff computation
- **Inline comments**: Posts specific line comments for issues found
- **Duplicate detection**: Skips posting duplicate comments on the same line
- **Fallback behavior**: Falls back to regular PR comment if inline comment fails (HTTP 422)
- **Read-only to local repo**: Does not modify local files or make commits

## ISSUE_WRITER Mode: Create a GitHub Issue

ISSUE_WRITER mode is a headless workflow that uses repository discovery to draft a high-quality GitHub issue and then creates it via the GitHub API.

### Configuration

ISSUE_WRITER requires:
- `plannerModel`: The LLM model to use for repository discovery and issue drafting.
- Tags:
  - `mode=ISSUE_WRITER`
  - `github_token`
  - `repo_owner`
  - `repo_name`

ISSUE_WRITER is read-only to the local repo (no file modifications or commits), but it creates a GitHub issue.

### Example: Create an issue via POST /v1/jobs

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: issue-writer-001" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Create an issue describing the NPE when AuthenticationProvider receives a null user, including evidence from the codebase.",
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

## ISSUE Mode: Automated Issue Resolution with Build Verification (and optional quick mode)

ISSUE mode enables end-to-end resolution of GitHub Issues. The executor fetches the issue content, creates a branch, decomposes the work into tasks, applies changes, and—by default—verifies changes with build/test/lint checks before delivering them (e.g., creating a Pull Request). ISSUE mode also supports a "quick" or skip-verification variant that omits verification and review loops for faster, lower-cost runs.

### How ISSUE Works (default — full verification)

When you submit an ISSUE job using the default behavior (omitting `skipVerification` or setting it to `false`), the system follows these steps:

1. Fetch Issue: Retrieves the title and body of the GitHub issue.
2. Branch Creation: Creates and checks out a new branch named like `brokk/issue-{number}`, guaranteeing uniqueness by appending a suffix if needed.
3. Task Planning: Uses the `plannerModel` to decompose the issue into an ordered set of actionable tasks.
4. Execution & Per-Task Verification:
   - Implementation: ArchitectAgent implements each task using `plannerModel` and `codeModel`.
   - Build Verification: After implementing a task, the executor runs the configured build/lint/test verification for that task.
   - Per-task self-correction: If verification fails, the executor performs one (single) automated fix attempt for the task and then re-runs verification. If verification still fails, the job reports failure and halts.
   - Note: Per-task verification is governed by `buildSettings.maxBuildAttempts` (per-task retry budget).
5. Final Gate & Delivery:
   - After all tasks pass per-task verification, the executor runs final checks (full tests + lint).
   - If delivery is enabled (default), the executor commits, pushes, and opens a Pull Request on GitHub. You can disable PR creation by setting the `issue_delivery` tag to `none` in the job payload.
6. Cleanup: Restore original branch and remove temporary issue branches as appropriate (best-effort).

### Quick Mode: skipVerification (optional for ISSUE jobs)

ISSUE jobs support an optional boolean field `skipVerification` on JobSpec. When `skipVerification` is set to `true`, the executor runs a "quick" ISSUE flow:

- The executor still:
  - Fetches the issue from GitHub.
  - Creates and checks out the issue branch (e.g., `brokk/issue-{number}`).
  - Plans tasks and applies code changes produced by the agents.
  - Performs branch cleanup and can create a Pull Request if delivery is enabled.
- The executor does NOT run the usual verification and review steps:
  - Per-task verification gate (the implement → verify → single-fix → re-verify sequence) is skipped.
  - The per-task test/lint retry loop governed by `buildSettings.maxBuildAttempts` is not executed.
  - The final tests/lint + review-bot inline comment fix sequence (the final gate) is skipped.
- Important: `skipVerification` does not change delivery or cleanup semantics — PR creation and branch cleanup still occur when configured.

When to use skipVerification:
- Faster, lower-cost runs that produce candidate fixes for human review.
- Situations where running repository tests or lint is undesirable or infeasible in the execution environment.

When to avoid skipVerification:
- If you rely on automated verification to ensure code correctness before opening a PR.
- When you want the executor to attempt automatic fixes for failing builds (the full verification flow uses per-task and final verification loops and interacts with the review-bot fix sequences).

### How skipVerification interacts with existing retry settings

- buildSettings.maxBuildAttempts controls the per-task verification retry loop in the full verification flow. It is ignored when `skipVerification == true` because the per-task verification loop is not run.
- maxIssueFixAttempts is a job-level cap on how many overall ISSUE remediation attempts the job may perform during the full verification final gate. It is also not applicable in skip-verification runs because the final verification gate is skipped.
- In short: when `skipVerification` is true, per-task and final verification/retry budgets (both `buildSettings.maxBuildAttempts` and `maxIssueFixAttempts`) are not exercised; branch creation/cleanup and optional PR creation still proceed.

### Configuration

ISSUE mode requires:
- `plannerModel`: LLM model for planning/reasoning.
- `codeModel` (optional): LLM model for code generation (defaults to project default if omitted).
- GitHub issue metadata: `github_token`, `repo_owner`, `repo_name`, `issue_number`.
- `buildSettings` (optional): JSON object to configure verification commands and per-task `maxBuildAttempts` (used only in full verification mode).
- `maxIssueFixAttempts` (optional): Overall ISSUE workflow attempt budget (job-level cap) — used only in full verification mode. Default: 20.
- `skipVerification` (optional boolean): When present and `true` (only honored for ISSUE jobs), runs the quick/skip-verification flow described above. Default: `false`.

### Example: Convenience Endpoint with skipVerification (quick mode)

This example shows `/v1/jobs/issue` with `skipVerification=true`. Compared to the default, this tells the executor to skip per-task and final verification and review loops — it will still create the branch, apply changes, and may open a PR if delivery is enabled.

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs/issue" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: issue-quick-001" \
  --data @- <<'JSON'
{
  "owner": "myorg",
  "repo": "myrepo",
  "issueNumber": 42,
  "githubToken": "ghp_xxxxxxxxxxxx",
  "plannerModel": "gpt-4",
  "codeModel": "gpt-4",
  "maxIssueFixAttempts": 20,
  "buildSettings": {
    "buildLintCommand": "./gradlew classes",
    "testAllCommand": "./gradlew test",
    "environmentVariables": {
      "JAVA_HOME": "/usr/lib/jvm/java-21"
    },
    "maxBuildAttempts": 5
  },
  "skipVerification": true
}
JSON
```

### Example: Generic POST /v1/jobs with tags and skipVerification

You may also submit ISSUE jobs via the generic `/v1/jobs` endpoint. For ISSUE-mode jobs, include `tags.mode = "ISSUE"`. The optional top-level `skipVerification` boolean is accepted in the job payload but is only honored when `tags.mode == "ISSUE"`.

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: issue-quick-002" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Ignored for ISSUE mode; issue body drives the work.",
  "plannerModel": "gpt-4",
  "codeModel": "gpt-4",
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

### Notes on behavior and observability

- Event streams for ISSUE jobs will still reflect planning and code changes even in skip-verification mode; however, verification-related events (build runs, test results, review-bot fix attempts) will not be emitted when verification is skipped.
- If you need automated verification before PR creation, do not set `skipVerification` (or set it to `false`) and tune `buildSettings.maxBuildAttempts` / `maxIssueFixAttempts` as needed.
- Other modes (ASK, SEARCH, LUTZ, REVIEW, ISSUE_WRITER, CODE, ARCHITECT) are unaffected by `skipVerification` — it is honored only for ISSUE-mode jobs.

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

- **`GET /v1/tasklist`** (Authenticated) - Get current task list content
  - Returns the structured content of the current active task list.
  - Recommended polling interval: ~15 seconds (suggestion, not a protocol requirement).
  - Empty state: If no task list is active, returns HTTP 200 with `bigPicture: null` and `tasks: []`.
  - Schema:
    ```json
    {
      "bigPicture": "Goal or overview of the current task sequence",
      "tasks": [
        {
          "id": "uuid-or-string",
          "title": "Short task name",
          "text": "Detailed task instructions",
          "done": false
        }
      ]
    }
    ```

### Job Management (Authenticated)

- **`POST /v1/jobs`** - Create and execute a job
  - Requires `Idempotency-Key` header for safe retries
  - Body: Job JSON accepted by the headless API. Most fields correspond to persisted `JobSpec`, but:
    - `sessionId` exists in the request body and is used to select the active session; it is not persisted in `JobSpec` (it may be copied into tags as `session_id`).
    - `sourceBranch` / `targetBranch` are persisted/reserved `JobSpec` fields but are not currently accepted by `POST /v1/jobs` (they will always be null when jobs are created via this endpoint).
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`
  - **SEARCH mode**: Set `"tags": { "mode": "SEARCH" }` to run a read-only repository scan. Optionally include `"scanModel": "<model>"` to override the default scan model used for repository scanning. `plannerModel` is still required by the API for validation.
  - **ASK mode**: Set `"tags": { "mode": "ASK" }` for ad-hoc read-only searches (uses service default scan model unless otherwise configured).
  - **CODE mode**: Set `"tags": { "mode": "CODE" }` for single-shot code generation
  - **LUTZ mode**: Set `"tags": { "mode": "LUTZ" }` to enable two-phase planning and execution (SearchAgent generates a task list, then ArchitectAgent executes tasks sequentially), honoring autoCommit and autoCompress
  - **REVIEW mode**: Set `"tags": { "mode": "REVIEW" }` to review a GitHub PR (requires github_token, repo_owner, repo_name, pr_number in tags)
  - **ISSUE mode**: Set `"tags": { "mode": "ISSUE" }` to resolve a GitHub Issue. Requires `github_token`, `repo_owner`, `repo_name`, and `issue_number` in tags. ISSUE-mode jobs may include an optional top-level boolean field `skipVerification` in the job JSON; when present and `true` the executor will run the ISSUE quick (skip-verification) flow (see ISSUE Mode section for details). This field is only honored for jobs whose `tags.mode == "ISSUE"`; other modes ignore it.
  - **ISSUE_WRITER mode**: Set `"tags": { "mode": "ISSUE_WRITER" }` to discover evidence in the repo and create a GitHub issue (requires github_token, repo_owner, repo_name in tags).
  - **ARCHITECT mode** (default): Orchestrates multi-step planning and implementation

#### Job-level model overrides (optional)

You can optionally override model behaviors per job:

- `reasoningLevel` (string, optional): Controls how much explicit reasoning effort the planner model should use.
- `reasoningLevelCode` (string, optional): Controls how much explicit reasoning effort the code model should use. Applies to CODE and ARCHITECT modes.
- `temperature` (number, optional): Controls sampling randomness for the planner model.
- `temperatureCode` (number, optional): Controls sampling randomness for the code model. Applies to CODE and ARCHITECT modes.

These fields are accepted in the top-level job payload alongside `plannerModel` / `codeModel` / `scanModel`.

##### Validation rules

- `reasoningLevel`:
  - If provided, must be a string.
  - Accepted values: `"DEFAULT"`, `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"DISABLE"`.
  - If omitted or null, the executor uses the model/service default reasoning configuration for the planner model.

- `reasoningLevelCode`:
  - If provided, must be a string.
  - Accepted values: `"DEFAULT"`, `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"DISABLE"`.
  - If omitted or null, the executor uses the model/service default reasoning configuration for the code model.

- `temperature`:
  - If provided, must be a JSON number.
  - Must be between `0.0` and `2.0` (inclusive).
  - If omitted or null, the executor uses the model/service default temperature for the planner model.

- `temperatureCode`:
  - If provided, must be a JSON number.
  - Must be between `0.0` and `2.0` (inclusive).
  - If omitted or null, the executor uses the model/service default temperature for the code model.

##### Example: ARCHITECT with reasoningLevel + temperature

```bash
curl -sS -X POST "http://localhost:8080/v1/jobs" \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: architect-overrides-001" \
  --data @- <<'JSON'
{
  "sessionId": "<session-id>",
  "taskInput": "Refactor the auth module to improve logging and error messages.",
  "autoCommit": true,
  "autoCompress": true,
  "plannerModel": "gpt-5",
  "codeModel": "gpt-5-mini",
  "reasoningLevel": "HIGH",
  "reasoningLevelCode": "MEDIUM",
  "temperature": 0.2,
  "temperatureCode": 0.0,
  "tags": {
    "mode": "ARCHITECT"
  }
}
JSON
```

- **`POST /v1/jobs/issue`** - Create an issue resolution job (convenience endpoint)
  - Requires `Idempotency-Key` header
  - Body: `{ "owner": "<string>", "repo": "<string>", "issueNumber": <int>, "githubToken": "<string>", "plannerModel": "<string>", "codeModel": "<string>", "buildSettings": <object> }`
  - Returns: Same response as `POST /v1/jobs`

- **`POST /v1/jobs/pr-review`** - Create a PR review job (convenience endpoint)
  - Requires `Idempotency-Key` header
  - Body: `{ "owner": "<string>", "repo": "<string>", "prNumber": <int>, "githubToken": "<string>", "plannerModel": "<string>" }`
  - Returns: Same response as `POST /v1/jobs`
  - Internally creates a job with `mode: REVIEW` and the appropriate tags

- **`GET /v1/jobs/{jobId}`** - Get job status
  - Returns: `JobStatus` JSON with current state and metadata
  - Example response:
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
  - Possible `state` values: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`

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
java -cp app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.HeadlessExecutorMain \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr 0.0.0.0:8080 \
  --auth-token my-secret-token \
  --workspace-dir /path/to/workspace
```

**Note:** The JAR requires the fully-qualified main class (`ai.brokk.executor.HeadlessExecutorMain`) as the first argument.
