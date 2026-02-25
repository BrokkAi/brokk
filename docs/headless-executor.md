# Running the Headless Executor

The Headless Executor runs Brokk sessions in a server mode, controllable via HTTP+JSON API. It's designed for remote execution, CI/CD pipelines, and programmatic task automation.

For end-to-end request examples (sessions, jobs, events, and mode-specific payloads), see [headless-executor-testing-with-curl.md](headless-executor-testing-with-curl.md).

## Configuration

The executor requires the following configuration, provided via **environment variables** or **command-line arguments** (arguments take precedence):

| Configuration | Env Var | Argument | Required | Description |
|--------------|---------|----------|----------|-------------|
| Executor ID | `EXEC_ID` | `--exec-id` | Yes | UUID identifying this executor instance |
| Listen Address | `LISTEN_ADDR` | `--listen-addr` | Yes | Host:port to bind (e.g., `0.0.0.0:8080`) |
| Auth Token | `AUTH_TOKEN` | `--auth-token` | Yes | Bearer token for API authentication |
| Workspace Dir | `WORKSPACE_DIR` | `--workspace-dir` | Yes | Path to the project workspace |
| Brokk API Key | `BROKK_API_KEY` | `--brokk-api-key` | No | Per-executor Brokk API key; overrides global config. If not provided, falls back to the globally configured key |
| LLM Proxy Setting | `PROXY_SETTING` | `--proxy-setting` | No | LLM proxy target: `BROKK` (https://proxy.brokk.ai), `LOCALHOST` (http://localhost:4000), or `STAGING` (https://staging.brokk.ai). If not provided, uses global config |
| Other-models Vendor | (n/a) | `--vendor` | No | Sets the "Other Models" vendor preference used for internal roles (scan/summarize/commit, etc.). Use `Default` to clear overrides. Applies to non-code roles; does not change `CODE`/`ARCHITECT` role selection |
| TLS Enabled | `TLS_ENABLED` | `--tls-enabled` | No | Enable HTTPS (default: false) |
| TLS Keystore Path | `TLS_KEYSTORE_PATH` | `--tls-keystore-path` | No* | Path to JKS keystore (required if TLS enabled) |
| TLS Keystore Pass | `TLS_KEYSTORE_PASSWORD`| `--tls-keystore-password`| No* | Password for the keystore (required if TLS enabled) |
| TLS Client CA | `TLS_CLIENT_CA_PATH` | `--tls-client-ca-path` | No | Path to PEM-encoded CA for client cert validation |
| mTLS Required | `MTLS_REQUIRED` | `--mtls-required` | No | Require client certificate (default: false; requires CA path) |

Notes:
- Sessions are stored under `<workspace>/.brokk/sessions` and jobs under `<workspace>/.brokk/jobs`.
- There is currently no `SESSIONS_DIR` / `--sessions-dir` override; the sessions directory is derived from the workspace.

## Running from Source

Run the headless executor with Gradle:

```bash
./gradlew :app:runHeadlessExecutor
```

This task is primarily configured via environment variables. For optional CLI-only flags (like `--vendor`), see the Production Deployment example below.

### Examples

**Using environment variables:**
```bash
export EXEC_ID="550e8400-e29b-41d4-a716-446655440000"
export LISTEN_ADDR="localhost:8080"
export AUTH_TOKEN="my-secret-token"
export WORKSPACE_DIR="/path/to/workspace"
./gradlew :app:runHeadlessExecutor
```

## ASK Mode: Read-Only Answer From Current Workspace Context

ASK mode returns a single written answer using the current session's Workspace context. It does not modify files, commit changes, or run repository-wide discovery by default.

### How ASK Works

When you submit an ASK job, the system:
1. Receives your natural language query
2. Uses the `plannerModel` to produce an answer based on whatever is already in the Workspace context
3. Streams the answer back as events (no UI prompts needed in headless mode)
4. Produces no code changes and no commits

### Optional pre-scan (seed workspace)

ASK supports an optional repository pre-scan that seeds the Workspace with additional context before the ASK answer is generated. To enable this behavior, include the boolean flag `"preScan": true` in the job payload. When `preScan` is true the executor runs the Context Engine (a lightweight repository scan) prior to answering; this can improve recall for large repos or vague queries.

Model selection semantics for the pre-scan:
- The ASK reasoning always uses `plannerModel` (this remains required).
- The pre-scan currently uses the project's default scan model (`Service.getScanModel()`).
- The `scanModel` field is accepted in the job payload, but it is currently only used by SEARCH mode (it is not applied to the ASK pre-scan in the current implementation).
- `codeModel` is ignored for ASK (ASK is read-only).

Example job fields for pre-scan behavior:
- `"plannerModel": "gpt-5"`  — required for ASK reasoning
- `"preScan": true`         — enable repository pre-scan before reasoning
- `"scanModel": "gpt-5-mini"` — optional override used by SEARCH mode (not applied to ASK pre-scan currently)

ASK does not perform tool-driven repository discovery by itself. For read-only repository discovery (symbol search, usages, file search, etc.), use SEARCH mode.

### Configuration

ASK mode requires:
- `plannerModel`: The LLM model to use for generating the answer
- `preScan` (optional): When `true`, run a repository pre-scan that seeds the Workspace before reasoning
- `scanModel` (optional): Used by SEARCH mode; not applied to ASK pre-scan in the current implementation
- `autoCompress` (optional): Enable automatic context compression (recommended for large codebases)

ASK mode **ignores** `codeModel` since it does not perform code generation.

### ASK execution notes

Note: In the current implementation, ASK pre-scan always uses the project's default scan model, regardless of `scanModel`.

For concrete ASK request/streaming examples, use the curl examples document linked at the top of this page.

## SEARCH Mode: Read-Only Repository Scan (explicit scan model)

SEARCH mode is a new read-only mode focused on repository scanning and discovery. It is operationally similar to ASK in that it performs read-only exploration and returns findings without producing code changes or commits, but it provides explicit control over which LLM model performs the repository scan.

Key points:
- Read-only: SEARCH will not write, commit, or modify repository files. No code diffs or git commits are produced.
- Uses a scan model: When creating a SEARCH job you may optionally supply a `scanModel` in the job payload. If provided, the executor will use that model for scanning and searching the repository. If `scanModel` is not provided, the executor falls back to the project's default scan model (via the Service's `getScanModel()`).
- `plannerModel` is still required by the API for validation (this is kept for API uniformity), but SEARCH prefers `scanModel` to select the actual scanning LLM. `codeModel` is ignored in SEARCH mode.
- Behavior vs ASK: ASK produces a single answer from the current Workspace context. SEARCH runs repository discovery using SearchAgent and returns findings (still read-only).
- Behavior vs LUTZ: LUTZ is a two-phase planning+execution workflow (SearchAgent generates a task list, then Architect executes tasks possibly producing code). SEARCH does not plan or execute — it only discovers and returns information.

### Supported Search & Inspection Capabilities (SEARCH)

- **Symbol search**: Find class, method, and field definitions by name or pattern
- **Class inspection**: Get full source code or skeleton views of classes
- **Method lookup**: Retrieve specific method implementations
- **File summaries**: Get a quick overview of class structures in files (fields, method signatures)
- **Usages**: Discover where symbols are used throughout the codebase
- **File search**: Search for files by name or content patterns
- **Git history**: Search commit messages for context about changes
- **Related code**: Automatically find related classes and dependencies

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

### Key Characteristics of REVIEW Mode

- **Full GitHub integration**: Fetches PR, computes diff, posts comments
- **Intelligent diff analysis**: Uses merge-base for accurate diff computation
- **Inline comments**: Posts specific line comments for issues found
- **Duplicate detection**: Skips posting duplicate comments on the same line
- **Fallback behavior**: Falls back to regular PR comment if inline comment fails (HTTP 422)
- **Read-only to local repo**: Does not modify local files or make commits

## ISSUE_DIAGNOSE Mode: Analyze a GitHub Issue

ISSUE_DIAGNOSE mode analyzes a GitHub issue (including all comments and images) and posts a diagnosis comment without making code changes. This is designed for a two-phase workflow where the bot first analyzes and posts its understanding, then waits for user approval before proceeding to fix.

### How ISSUE_DIAGNOSE Works

When you submit an ISSUE_DIAGNOSE job, the system:
1. Fetches the issue details (title, body, all comments, attached images) via the GitHub API
2. Captures any images from the issue into the LLM context
3. Runs an analysis using the planner model to understand the issue
4. Posts a diagnosis comment to the issue with a hidden marker (`<!-- brokk:diagnosis:v1 timestamp="..." -->`)
5. Completes without creating branches, making code changes, or opening PRs

The diagnosis comment includes:
- A structured analysis of the issue
- Next steps prompting the user to reply with `@BrokkBot solve` to proceed

### Configuration

ISSUE_DIAGNOSE mode requires:
- `plannerModel`: The LLM model for analyzing the issue
- GitHub issue metadata (passed via tags):
  - `github_token`: GitHub personal access token with repo access
  - `repo_owner`: Repository owner (user or organization)
  - `repo_name`: Repository name
  - `issue_number`: Issue number to analyze

ISSUE_DIAGNOSE mode **ignores** `codeModel` since it performs analysis only, not code generation.

### Two-Phase Workflow

ISSUE_DIAGNOSE is designed to work with ISSUE (solve) mode in a two-phase workflow:

1. **Phase 1: Diagnose** — Run ISSUE_DIAGNOSE to analyze the issue and post a diagnosis comment
2. **User Review** — User reviews the diagnosis, optionally adds steering comments
3. **Phase 2: Solve** — Run ISSUE mode to implement the fix (detects existing diagnosis and skips re-posting)

This workflow allows human oversight before code changes are made.

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

## ISSUE Mode: Automated Issue Resolution with Build Verification (and optional quick mode)

ISSUE mode enables end-to-end resolution of GitHub Issues. The executor fetches the issue content (including all comments and images), creates a branch, decomposes the work into tasks, applies changes, and—by default—verifies changes with build/test/lint checks before delivering them (e.g., creating a Pull Request). ISSUE mode also supports a "quick" or skip-verification variant that omits verification and review loops for faster, lower-cost runs.

### How ISSUE Works (default — full verification)

When you submit an ISSUE job using the default behavior (omitting `skipVerification` or setting it to `false`), the system follows these steps:

1. Fetch Issue: Retrieves the full issue details including title, body, all comments, and attached images via the GitHub API. Images are captured into the LLM context for analysis.
2. Analysis & Diagnosis: Runs a fresh analysis of the issue using the planner model. If no prior diagnosis comment exists (detected by the `<!-- brokk:diagnosis:v1` marker), posts a diagnosis comment for transparency. If a diagnosis already exists (e.g., from a prior ISSUE_DIAGNOSE run), skips posting to avoid duplicates.
3. Branch Creation: Creates and checks out a new branch named like `brokk/issue-{number}`, guaranteeing uniqueness by appending a suffix if needed.
4. Task Planning: Uses the `plannerModel` to decompose the issue into an ordered set of actionable tasks.
5. Execution & Per-Task Verification:
   - Implementation: ArchitectAgent implements each task using `plannerModel` and `codeModel`.
   - Build Verification: After implementing a task, the executor runs the configured build/lint/test verification for that task.
   - Per-task self-correction: If verification fails, the executor performs one (single) automated fix attempt for the task and then re-runs verification. If verification still fails, the job reports failure and halts.
   - Note: Per-task verification is governed by `buildSettings.maxBuildAttempts` (per-task retry budget).
6. Final Gate & Delivery:
   - After all tasks pass per-task verification, the executor runs final checks (full tests + lint).
   - If delivery is enabled (default), the executor commits, pushes, and opens a Pull Request on GitHub. You can disable PR creation by setting the `issue_delivery` tag to `none` in the job payload.
7. Cleanup: Restore original branch and remove temporary issue branches as appropriate (best-effort).

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
  - Returns `201`: `{ "sessionId": "<uuid>", "name": "<session name>" }`

- **`POST /v1/sessions/switch`** - Switch the active session
  - Body: `{ "sessionId": "<uuid>" }`
  - Returns: `{ "status": "ok", "sessionId": "<uuid>" }`

- **`PUT /v1/sessions`** - Upload an existing session zip file
  - Content-Type: `application/zip`
  - Optional header: `X-Session-Id: <uuid>` (if omitted, server generates one)
  - Returns `201`: `{ "sessionId": "<uuid>" }`

- **`GET /v1/sessions`** - List sessions
  - Returns: `{ "sessions": [...], "currentSessionId": "<uuid>" }`

- **`GET /v1/sessions/current`** - Get current session metadata
  - Returns: `{ "id": "<uuid>", "name": "...", "created": <epochMs>, "modified": <epochMs> }`

- **`GET /v1/sessions/{sessionId}`** - Download a session zip
  - Returns: `application/zip`

### Context & Task List (Authenticated)

- **`GET /v1/context`** - Get live context summary
  - Optional query: `tokens=true|1` to include token estimates

- **`GET /v1/context/fragments/{fragmentId}`** - Get one context fragment's content

- **`GET /v1/context/conversation`** - Get conversation/task-history entries

- **`POST /v1/context/drop`** - Drop fragments by ID
  - Body: `{ "fragmentIds": ["..."] }`

- **`POST /v1/context/pin`** - Set pin state on a fragment
  - Body: `{ "fragmentId": "...", "pinned": true|false }`

- **`POST /v1/context/readonly`** - Set readonly state on an editable fragment
  - Body: `{ "fragmentId": "...", "readonly": true|false }`

- **`POST /v1/context/compress-history`** - Trigger async history compression

- **`POST /v1/context/clear-history`** - Clear context history

- **`POST /v1/context/drop-all`** - Drop all context fragments

- **`POST /v1/context/files`** - Add files to context
  - Body: `{ "relativePaths": ["path/from/workspace/root"] }`

- **`POST /v1/context/classes`** - Add class summaries to context
  - Body: `{ "classNames": ["com.example.MyClass"] }`

- **`POST /v1/context/methods`** - Add method sources to context
  - Body: `{ "methodNames": ["com.example.MyClass.myMethod"] }`

- **`POST /v1/context/text`** - Add free-form text to context
  - Body: `{ "text": "..." }` (max 1 MiB UTF-8)

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
          "id": "opaque-task-id",
          "title": "Short task name",
          "text": "Detailed task instructions",
          "done": false
        }
      ]
    }
    ```

- **`POST /v1/tasklist`** - Replace current task list content
  - Body: `{ "bigPicture": "<optional>", "tasks": [ ... ] }`

### Activity (Authenticated)

- **`GET /v1/activity`** - Get grouped activity/history timeline

- **`GET /v1/activity/diff?contextId=<uuid>`** - Get per-fragment diff for a context snapshot

- **`POST /v1/activity/undo`** - Undo to a specific context
  - Body: `{ "contextId": "<uuid>" }`

- **`POST /v1/activity/undo-step`** - Undo one step

- **`POST /v1/activity/redo`** - Redo one step

- **`POST /v1/activity/copy-context`** - Reset current context to a snapshot (without history)
  - Body: `{ "contextId": "<uuid>" }`

- **`POST /v1/activity/copy-context-history`** - Reset current context to a snapshot including history
  - Body: `{ "contextId": "<uuid>" }`

- **`POST /v1/activity/new-session`** - Create a new session from a context snapshot
  - Body: `{ "contextId": "<uuid>", "name": "<optional>" }`

### Models & Completions (Authenticated)

- **`GET /v1/models`** - List available models and capabilities

- **`GET /v1/completions`** - File/symbol completions for mention UX
  - Query params:
    - `query` (required for non-empty results)
    - `limit` (optional, clamped to 1..50; default 20)

- **`GET /v1/favorites`** - List favorite model configs

### Job Management (Authenticated)

- **`POST /v1/jobs`** - Create and execute a job
  - Requires `Idempotency-Key` header for safe retries
  - Body: Job JSON accepted by the headless API. Most fields correspond to persisted `JobSpec`, but:
    - `sessionId` exists in the request body for compatibility, but it is not currently used to select the active session. Jobs execute against the server's currently active session (set by `POST /v1/sessions` or `PUT /v1/sessions`).
    - `sourceBranch` / `targetBranch` are persisted/reserved `JobSpec` fields but are not currently accepted by `POST /v1/jobs` (they will always be null when jobs are created via this endpoint).
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`
  - **SEARCH mode**: Set `"tags": { "mode": "SEARCH" }` to run a read-only repository scan. Optionally include `"scanModel": "<model>"` to override the default scan model used for repository scanning. `plannerModel` is still required by the API for validation.
  - **ASK mode**: Set `"tags": { "mode": "ASK" }` for a single read-only answer from the current Workspace context (optionally with `preScan: true` to seed context first).
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

#### Convenience endpoints and job inspection

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
  - Returns: `{ "events": [ ... ], "nextAfter": <seq> }`

- **`POST /v1/jobs/{jobId}/cancel`** - Cancel a running job
  - Returns: `202 Accepted` with empty body

- **`GET /v1/jobs/{jobId}/diff`** - Get git diff of job changes
  - Returns: Plain text diff (`text/plain; charset=UTF-8`)
  - If git is unavailable, returns `409` with `NO_GIT`

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
java -Djava.awt.headless=true -Dapple.awt.UIElement=true \
  -cp app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.HeadlessExecutorMain \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr 0.0.0.0:8080 \
  --auth-token my-secret-token \
  --workspace-dir /path/to/workspace \
  --vendor Anthropic
```

Headless JVM flags:

- `-Djava.awt.headless=true` — Recommended and safe on all platforms; forces the JVM into headless mode so no AWT native UI is initialized.
- `-Dapple.awt.UIElement=true` — Hides the Java process from the Dock and app switcher on macOS. This flag is effectively ignored on non-macOS platforms, so it is safe to include cross-platform.

## TLS and Mutual TLS (mTLS)

The Headless Executor supports secure communication via HTTPS and optional mutual TLS (mTLS). By default, the executor runs over plain HTTP and requires a Bearer token for authentication.

### Security Model

1.  **TLS (HTTPS)**: Encrypts the traffic between the client and the executor. Recommended for all non-localhost deployments.
2.  **Mutual TLS (mTLS)**: Enforces that the client must present a valid certificate signed by a trusted CA. When mTLS is enabled, the **Bearer token is still required**; mTLS adds an additional layer of identity verification at the transport level.

### Configuration Options

| Configuration | Env Var | CLI Argument | Description |
|---------------|---------|--------------|-------------|
| Enabled | `TLS_ENABLED` | `--tls-enabled` | Set to `true` to enable HTTPS |
| Keystore Path | `TLS_KEYSTORE_PATH` | `--tls-keystore-path` | Path to a Java KeyStore (`.jks`) containing the server certificate and private key |
| Keystore Pass | `TLS_KEYSTORE_PASSWORD` | `--tls-keystore-password` | Password for the keystore |
| Client CA Path | `TLS_CLIENT_CA_PATH` | `--tls-client-ca-path` | Path to a PEM-encoded CA certificate used to verify client certificates |
| mTLS Required | `MTLS_REQUIRED` | `--mtls-required` | Set to `true` to require and verify client certificates |

### Local Development with Self-Signed Certificates

You can use `openssl` to create a local certificate authority and sign certificates for testing.

#### 1. Create a local CA
```bash
openssl genrsa -out local-ca.key 2048
openssl req -x509 -new -nodes -key local-ca.key -sha256 -days 365 -out local-ca.pem -subj "/CN=Local-CA"
```

#### 2. Create and sign the Server Certificate
```bash
# Generate server key and CSR
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=localhost"

# Sign with local CA
openssl x509 -req -in server.csr -CA local-ca.pem -CAkey local-ca.key -CAcreateserial -out server.pem -days 365 -sha256

# Package into JKS for Java (HeadlessExecutorMain)
openssl pkcs12 -export -in server.pem -inkey server.key -out server.p12 -name "brokk-server" -passout pass:changeit
keytool -importkeystore -destkeystore server.jks -srckeystore server.p12 -srcstoretype PKCS12 -alias "brokk-server" -deststorepass changeit -srcstorepass changeit
```

#### 3. Create and sign the Client Certificate (for mTLS)
```bash
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=brokk-client"
openssl x509 -req -in client.csr -CA local-ca.pem -CAkey local-ca.key -CAcreateserial -out client.pem -days 365 -sha256
```

### Running the Executor with TLS

```bash
java -cp brokk.jar ai.brokk.executor.HeadlessExecutorMain \
  --exec-id $(uuidgen) \
  --listen-addr 0.0.0.0:8443 \
  --auth-token my-secret-token \
  --workspace-dir ./my-project \
  --tls-enabled true \
  --tls-keystore-path ./server.jks \
  --tls-keystore-password changeit \
  --tls-client-ca-path ./local-ca.pem \
  --mtls-required true
```

The executor will now log:
`Executor listening on https://0.0.0.0:8443`

### Connecting with `brokk-code` (Python TUI / ACP)

The Python client supports TLS via the `verify` (CA bundle) and `cert` (client certificate/key) options. When running in ACP mode:

```bash
# In your terminal or IDE run configuration
export BROKK_TLS_VERIFY="./local-ca.pem"
export BROKK_TLS_CERT="./client.pem"
export BROKK_TLS_KEY="./client.key"

# Launch ACP server (options are passed to ExecutorManager)
brokk-code acp --verify $BROKK_TLS_VERIFY --cert $BROKK_TLS_CERT $BROKK_TLS_KEY
```

When using `curl` to test an mTLS-enabled executor:
```bash
curl -v --cacert local-ca.pem --cert client.pem --key client.key \
     -H "Authorization: Bearer my-secret-token" \
     https://localhost:8443/health/live
```

When launching via **jbang**, pass these flags before the script/alias:

```bash
jbang -Djava.awt.headless=true -Dapple.awt.UIElement=true brokk-headless@brokkai/brokk-releases [args]
```

**Note:** The JAR requires the fully-qualified main class (`ai.brokk.executor.HeadlessExecutorMain`) as the first argument. Brokk clients (such as the Python TUI and VS Code extension) automatically include these JVM flags when managing the executor lifecycle.
