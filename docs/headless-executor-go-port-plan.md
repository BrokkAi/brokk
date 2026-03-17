# Headless Executor Go Port Plan

## Purpose

This document is the working plan for translating the Java headless executor to Go as a drop-in replacement.

The user goal is direct translation over elegance. The Go version may be ugly and non-idiomatic if that helps preserve behavior and accelerate the port. Compatibility is more important than design purity.

## Primary Goal

Produce a Go executable that can replace the current Java headless executor for `brokk-code` and any other current clients without requiring client-side protocol changes.

## Success Criteria

The Go executor is considered a successful drop-in replacement when all of the following are true:

- It accepts the same required configuration and equivalent optional configuration.
- It exposes the same HTTP endpoints with the same authentication model.
- It returns materially compatible JSON payloads, status codes, and event sequences.
- It persists jobs and sessions in compatible on-disk formats under the workspace `.brokk` directory.
- It supports the same execution modes with equivalent externally visible behavior.
- It can pass a black-box compatibility suite against the Java executor for the same requests and fixtures.

## Translation Strategy

Prefer a direct port over redesign:

- Keep the same nouns and responsibilities where practical.
- Preserve route names, payload names, event names, and storage layout.
- Favor copying control flow from Java into Go even when it feels clumsy.
- Avoid early abstraction unless it is needed to unblock translation.

This is intentionally not a "rewrite it the Go way" project.

## Source Of Truth In The Current Java Implementation

These files define most of the observable contract:

- `app/src/main/java/ai/brokk/executor/HeadlessExecutorMain.java`
- `app/src/main/java/ai/brokk/executor/http/SimpleHttpServer.java`
- `app/src/main/java/ai/brokk/executor/routers/SessionsRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/JobsRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/ContextRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/ModelsRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/ActivityRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/CompletionsRouter.java`
- `app/src/main/java/ai/brokk/executor/routers/FavoritesRouter.java`
- `app/src/main/java/ai/brokk/executor/jobs/JobRunner.java`
- `app/src/main/java/ai/brokk/executor/jobs/JobStore.java`
- `app/src/main/java/ai/brokk/executor/jobs/JobSpec.java`
- `app/src/main/java/ai/brokk/executor/jobs/JobStatus.java`
- `app/src/main/java/ai/brokk/executor/jobs/JobEvent.java`
- `app/src/main/java/ai/brokk/executor/io/HeadlessHttpConsole.java`
- `docs/headless-executor.md`
- `docs/headless-executor-events.md`
- `docs/headless-executor-testing-with-curl.md`

These tests are especially important as compatibility anchors:

- `app/src/test/java/ai/brokk/tools/HeadlessExecCliTest.java`
- `app/src/test/java/ai/brokk/executor/routers/JobsRouterValidationTest.java`
- `app/src/test/java/ai/brokk/executor/routers/ContextRouterTest.java`
- `app/src/test/java/ai/brokk/executor/routers/ModelsRouterTest.java`
- `app/src/test/java/ai/brokk/executor/io/HeadlessHttpConsoleTest.java`
- `app/src/test/java/ai/brokk/executor/jobs/JobRunnerTest.java`
- `app/src/test/java/ai/brokk/executor/jobs/JobStoreTest.java`
- `app/src/test/java/ai/brokk/executor/jobs/IssueExecutorTest.java`
- `app/src/test/java/ai/brokk/executor/jobs/PrReviewServiceTest.java`

## Scope

### In Scope

- Executor CLI and env var parsing
- Health endpoints
- Authenticated HTTP API
- Session creation, switching, listing, import, and export
- Job submission, cancellation, polling, events, and diff retrieval
- Context inspection and mutation endpoints
- Task list endpoints
- Models, activity, completions, and favorites endpoints
- Durable job persistence under `.brokk`
- Durable session compatibility
- Streaming and event emission semantics
- Execution modes implemented by the current Java executor:
  - `ARCHITECT`
  - `CODE`
  - `ASK`
  - `SEARCH`
  - `REVIEW`
  - `LUTZ`
  - `PLAN`
  - `ISSUE`
  - `ISSUE_DIAGNOSE`
  - `ISSUE_WRITER`

### Out Of Scope For Initial Port

- Making the implementation idiomatic Go
- Simplifying the protocol
- Improving route design
- Changing persistence formats
- Changing user-visible event names
- Refactoring client behavior first

## Compatibility Contract

### 1. CLI And Configuration Compatibility

The Go binary should support the same configuration inputs as the Java executor:

- `EXEC_ID` / `--exec-id`
- `LISTEN_ADDR` / `--listen-addr`
- `AUTH_TOKEN` / `--auth-token`
- `WORKSPACE_DIR` / `--workspace-dir`
- `BROKK_API_KEY` / `--brokk-api-key`
- `PROXY_SETTING` / `--proxy-setting`
- `--vendor`
- `EXIT_ON_STDIN_EOF` / `--exit-on-stdin-eof`

It should also preserve:

- Required vs optional behavior
- Argument-over-environment precedence
- Help text behavior
- Validation failures for malformed values
- Redaction of sensitive config in logs and persisted state where applicable

### 2. HTTP Surface Compatibility

The current Java executor registers at least these endpoints:

- `GET /health/live`
- `GET /health/ready`
- `GET /v1/executor`
- `POST /v1/sessions`
- `PUT /v1/sessions`
- `GET /v1/sessions`
- `GET /v1/sessions/current`
- `POST /v1/sessions/switch`
- `GET /v1/sessions/{sessionId}`
- `POST /v1/jobs`
- `POST /v1/jobs/issue`
- `POST /v1/jobs/pr-review`
- `GET /v1/jobs/{jobId}`
- `GET /v1/jobs/{jobId}/events`
- `POST /v1/jobs/{jobId}/cancel`
- `GET /v1/jobs/{jobId}/diff`
- `GET /v1/context`
- `GET /v1/context/fragments/{fragmentId}`
- `GET /v1/context/conversation`
- `POST /v1/context/drop`
- `POST /v1/context/pin`
- `POST /v1/context/readonly`
- `POST /v1/context/compress-history`
- `POST /v1/context/clear-history`
- `POST /v1/context/drop-all`
- `POST /v1/context/files`
- `POST /v1/context/classes`
- `POST /v1/context/methods`
- `POST /v1/context/text`
- `GET /v1/tasklist`
- `POST /v1/tasklist`
- `GET /v1/models`
- `GET /v1/activity`
- `GET /v1/completions`
- `GET /v1/favorites`

The Go version must preserve:

- Bearer token auth behavior
- Unauthenticated health and executor-info endpoints
- Method handling and 404/405 behavior
- Validation error shapes
- Status codes
- Header-based inputs such as `Idempotency-Key`, `X-Session-Id`, and `X-Github-Token`

### 3. Persistence Compatibility

The Java `JobStore` persists under `.brokk` using:

- `jobs/{jobId}/meta.json`
- `jobs/{jobId}/status.json`
- `jobs/{jobId}/events.jsonl`
- `jobs/{jobId}/artifacts/*`
- `idempotency/{hash}.json`

The Go port should preserve:

- Directory layout
- JSON field names
- Event sequencing semantics
- Atomic write behavior where the Java implementation relies on temp-file-and-rename
- Idempotency behavior

Session storage must remain compatible with what `SessionManager` and the current clients expect.

### 4. Execution Semantics Compatibility

The Go port should preserve externally visible job behavior:

- Single active job reservation semantics
- Durable status transitions
- Event ordering and event types
- Cancellation behavior
- Diff artifact behavior
- Model override handling
- Reasoning level and temperature override validation
- Pre-scan and auto-compress behavior where supported
- Skip-verification behavior for issue execution

### 5. LLM Behavior Compatibility

Exact text parity is not required.

Required:

- Same mode selection
- Same tool and workflow choices at a high level
- Same externally visible side effects
- Similar event stream and completion conditions

Not required:

- Byte-for-byte identical LLM outputs
- Identical token counts
- Identical wording in generated prose

## Major Technical Reality

The executor package is not self-contained. A true drop-in replacement requires porting or replacing a large headless slice of Brokk, not just the HTTP server.

Key dependency areas pulled in by the executor include:

- Context and session management
- Agents for planning, coding, search, build, issue rewriting, and review
- Model and service selection logic
- Git operations
- GitHub issue and PR integration
- Prompt construction
- Conversation and fragment management
- Analyzer-backed completions and context attachment

This means the practical port boundary is closer to "headless Brokk runtime in Go" than "translate `HeadlessExecutorMain` only."

## Recommended Repository Layout For The Port

Proposed new workspace:

- `go-runtime/`

Suggested internal package layout:

- `go-runtime/cmd/headless-executor/`
- `go-runtime/internal/config/`
- `go-runtime/internal/httpserver/`
- `go-runtime/internal/router/`
- `go-runtime/internal/jobs/`
- `go-runtime/internal/sessions/`
- `go-runtime/internal/context/`
- `go-runtime/internal/activity/`
- `go-runtime/internal/models/`
- `go-runtime/internal/completions/`
- `go-runtime/internal/favorites/`
- `go-runtime/internal/git/`
- `go-runtime/internal/github/`
- `go-runtime/internal/agents/`
- `go-runtime/internal/llm/`
- `go-runtime/internal/persistence/`
- `go-runtime/internal/compat/`

The package names do not need to be perfect. The goal is to keep the translation manageable.

## Work Phases

### Phase 0: Freeze The Contract

Deliverables:

- Inventory the current HTTP routes, request shapes, response shapes, and status codes.
- Inventory job event types and event payload shapes.
- Inventory all job modes and their inputs.
- Inventory job store and session storage formats.
- Decide which Java behaviors are required for "drop-in" and which can be approximated.

Exit criteria:

- A written compatibility checklist exists.
- Unknowns are captured explicitly.

### Phase 1: Scaffold The Go Runtime

Deliverables:

- Create `go-runtime/` module
- Add minimal build and test commands
- Add config parsing equivalent to Java CLI/env behavior
- Add HTTP server wrapper with auth middleware and JSON helpers

Exit criteria:

- Go binary starts
- Health endpoints respond
- Executor info endpoint responds

### Phase 2: Port Durable Storage And Session Skeleton

Deliverables:

- Port `JobSpec`, `JobStatus`, `JobEvent`, and `JobStore`
- Port job idempotency behavior
- Port session list/create/switch/import/export shell
- Preserve `.brokk` directory layout

Exit criteria:

- Storage tests exist and pass
- Session routes work at the protocol level

### Phase 3: Port Read-Only Surface

Deliverables:

- `GET /v1/models`
- `GET /v1/favorites`
- `GET /v1/activity`
- `GET /v1/context`
- `GET /v1/context/fragments/{id}`
- `GET /v1/context/conversation`
- `GET /v1/tasklist`
- `GET /v1/completions`

Exit criteria:

- Read-only client workflows can connect and inspect state

### Phase 4: Port Context Mutation Surface

Deliverables:

- Context drop, pin, readonly
- History compression and clearing
- Drop-all
- File/class/method/text attachment
- Task list replacement

Exit criteria:

- Session context can be manipulated from the client without protocol differences

### Phase 5: Port Job Submission And Basic Runner

Deliverables:

- `POST /v1/jobs`
- `GET /v1/jobs/{jobId}`
- `GET /v1/jobs/{jobId}/events`
- `POST /v1/jobs/{jobId}/cancel`
- `GET /v1/jobs/{jobId}/diff`
- Single active job reservation
- Basic event streaming from Go

Exit criteria:

- A minimal job can be submitted, observed, cancelled, and completed with durable state

### Phase 6: Port Easiest Modes First

Recommended mode order:

1. `ASK`
2. `SEARCH`
3. `PLAN`

Reason:

- These are the least invasive modes for early compatibility work.
- They establish model selection, eventing, and context flow without full code modification.

Exit criteria:

- Read-only and planning jobs work end to end

### Phase 7: Port Code-Modifying Modes

Recommended order:

1. `ARCHITECT`
2. `CODE`
3. `LUTZ`

Focus:

- Task execution loops
- Code diff generation
- Auto-commit behavior
- Auto-compress behavior

Exit criteria:

- Code-changing jobs complete with persisted events and diff artifacts

### Phase 8: Port GitHub Review And Issue Workflows

Recommended order:

1. `REVIEW`
2. `ISSUE_DIAGNOSE`
3. `ISSUE_WRITER`
4. `ISSUE`

Focus:

- GitHub auth handling
- PR diff computation
- Issue fetch and comment workflows
- Branch management and verification loops

Exit criteria:

- GitHub-connected automation flows work without client changes

### Phase 9: Compatibility Harness

Deliverables:

- Black-box tests that can run the Java and Go executors against the same requests
- Golden files or normalized comparisons for:
  - HTTP status codes
  - JSON schema shape
  - Event ordering
  - Persistence layout
- Fixture workspaces for deterministic tests

Exit criteria:

- Major route and storage compatibility is enforced automatically

### Phase 10: Client Cutover

Deliverables:

- Add a way for `brokk-code` to launch the Go executor instead of Java
- Keep rollback path to Java during rollout
- Add release notes and operator docs

Exit criteria:

- Controlled swap from Java to Go is possible

## Execution Order Inside Each Phase

For each translated component:

1. Capture the Java behavior
2. Write a small compatibility note in this document or linked notes
3. Port the data model
4. Port the logic directly
5. Add a black-box or fixture-based test
6. Compare behavior against Java
7. Only then move to the next component

## Immediate Starting Slice

Recommended first implementation slice:

- Create `go-runtime/`
- Implement config parsing
- Implement auth-capable HTTP server
- Implement `GET /health/live`
- Implement `GET /health/ready`
- Implement `GET /v1/executor`
- Implement `JobStore`
- Implement `POST /v1/jobs` and `GET /v1/jobs/{jobId}` as persistence-only stubs

This gives a thin but useful compatibility shell without needing the full agent stack on day one.

## Risks

### Risk 1: Hidden Dependency Explosion

`JobRunner` reaches deeply into Brokk internals. The port may require translating far more than expected.

Mitigation:

- Port in slices
- Keep a running dependency map
- Treat "headless runtime" as the real unit of work

### Risk 2: Session Format Coupling

Session import/export may depend on Java-side serialization or zip conventions that are easy to miss.

Mitigation:

- Reverse engineer session zip shape early
- Add round-trip tests using real Java-generated sessions

### Risk 3: LLM Workflow Drift

A "better" Go design may accidentally change execution order or event patterns enough to break clients or automation.

Mitigation:

- Preserve mode-by-mode control flow
- Compare event streams and durable state, not just final success

### Risk 4: Git And GitHub Edge Cases

Review and issue flows depend on branch state, merge-base logic, auth, and duplicate comment handling.

Mitigation:

- Delay these modes until the base runtime is solid
- Build mode-specific fixture tests

### Risk 5: Scope Creep

Trying to make the Go version cleaner may slow or derail the translation.

Mitigation:

- Prefer ugly direct ports first
- Refactor only after compatibility exists

## Verification Plan

### Contract Tests

Add tests for:

- CLI parsing
- Env var fallback and precedence
- Auth middleware
- Validation failures
- Route existence and method behavior
- Job persistence format
- Event sequencing
- Session import/export compatibility

### Cross-Implementation Tests

Run both Java and Go versions against the same:

- Empty workspace
- Small git repo
- Repo with existing `.brokk` state
- Issue-review fixtures
- Context-heavy session fixture

Compare:

- Status codes
- JSON field presence
- Event type sequences
- Persistence layout
- Major side effects

### Operational Testing

- Launch from `brokk-code`
- Create session
- Attach context
- Run `ASK`
- Run `SEARCH`
- Run a code-changing mode
- Run a GitHub-connected mode last

## Tracking Checklist

### Foundation

- [x] Create `go-runtime/` module
- [ ] Add build instructions
- [ ] Add test instructions
- [ ] Add CI target for Go runtime

### Contract Capture

- [ ] Record all endpoint request and response schemas
- [ ] Record all job event types and payload shapes
- [ ] Record all persisted JSON formats
- [ ] Record session zip format
- [ ] Record all headers used by the API

### Server

- [x] Port config parsing
- [x] Port auth middleware
- [x] Port JSON response helpers
- [x] Port health endpoints
- [x] Port executor-info endpoint

### Storage

- [x] Port `JobSpec`
- [x] Port `JobStatus`
- [x] Port `JobEvent`
- [x] Port `JobStore`
- [x] Port idempotency mapping
- [x] Port diff artifact storage

### Sessions

- [x] Port list sessions
- [x] Port current session
- [x] Port create session
- [x] Port switch session
- [x] Port import session zip
- [x] Port export session zip

### Context

- [x] Port context listing
- [x] Port fragment fetch
- [x] Port conversation fetch
- [x] Port drop fragments
- [x] Port pin fragments
- [x] Port readonly fragments
- [x] Port compress history
- [x] Port clear history
- [x] Port drop all context
- [x] Port file attachment
- [x] Port class attachment
- [x] Port method attachment
- [x] Port text attachment
- [x] Port task list get/set

### Read-Only Discovery

- [x] Port models route
- [x] Port favorites route
- [x] Port activity route
- [x] Port completions route

### Jobs

- [x] Port job submission
- [x] Port job polling
- [x] Port job events
- [x] Port job cancellation
- [x] Port job diff retrieval
- [x] Port single-job reservation semantics

### Modes

- [x] Port `ASK`
- [x] Port `SEARCH`
- [x] Port `PLAN`
- [x] Port `ARCHITECT`
- [x] Port `CODE`
- [x] Port `LUTZ`
- [x] Port `REVIEW`
- [ ] Port `ISSUE_DIAGNOSE`
- [ ] Port `ISSUE_WRITER`
- [ ] Port `ISSUE`

### Verification

- [ ] Add black-box compatibility tests
- [ ] Add storage compatibility tests
- [ ] Add session compatibility tests
- [ ] Add event-sequence compatibility tests
- [ ] Add client launch test path for `brokk-code`

## Open Questions

- How much of the current Java context/session format is already stable enough to treat as fixed contract?
- Should the Go executor embed its own reimplementation of agent logic, or should some workflows call existing external tools and only gradually replace them?
- Can `brokk-code` support a feature flag to choose Java or Go executor during migration?
- Are there hidden API consumers besides `brokk-code` that depend on undocumented behaviors?
- Which mode should be considered the minimum viable drop-in target for the first real client rollout?

## Decision Log

### 2026-03-17

- Start with a compatibility-first plan.
- Favor direct translation over idiomatic redesign.
- Treat the executor as a port of the headless Brokk runtime slice, not only the HTTP wrapper.
- Build the Go runtime in a new `go-runtime/` directory.
- Implement read-only and persistence-heavy slices before GitHub and issue automation flows.
- Initial Go scaffold implemented:
  - `go-runtime/` module created
  - config parsing added
  - auth-capable HTTP shell added
  - `GET /health/live`, `GET /health/ready`, and `GET /v1/executor` added
  - durable `JobStore` added
  - minimal `POST /v1/jobs`, `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/events`, `POST /v1/jobs/{jobId}/cancel`, and `GET /v1/jobs/{jobId}/diff` shells added
  - initial Go tests added and passing
- Initial session layer implemented:
  - `.brokk/sessions` zip storage added
  - current-session persistence added
  - `POST /v1/sessions`, `PUT /v1/sessions`, `GET /v1/sessions`, `GET /v1/sessions/current`, `POST /v1/sessions/switch`, and `GET /v1/sessions/{sessionId}` added
  - readiness now reflects real current session state
  - session API tests added and passing
- Initial context/tasklist layer implemented:
  - session-scoped context state added
  - `GET /v1/context`, `GET /v1/context/fragments/{id}`, and `GET /v1/context/conversation` added
  - `POST /v1/context/drop`, `POST /v1/context/pin`, `POST /v1/context/readonly`, `POST /v1/context/compress-history`, `POST /v1/context/clear-history`, `POST /v1/context/drop-all`, and `POST /v1/context/text` added
  - `GET /v1/tasklist` and `POST /v1/tasklist` added
  - context/tasklist API tests added and passing
- Basic file attachment route implemented:
  - `POST /v1/context/files` added with workspace-relative path validation and file-backed fragments
  - class and method attachment routes currently return validation placeholders rather than analyzer-backed results
- Read-only discovery surface expanded:
  - `GET /v1/models` added with a static compatibility-oriented model list
  - `GET /v1/favorites` added with an empty favorites payload
  - `GET /v1/completions` added with filesystem-backed file completion results
  - `GET /v1/activity` added with the expected top-level envelope
  - deeper activity actions and analyzer-backed symbol completion are still not ported
- First execution-mode slice implemented:
  - async job runner added under `go-runtime/internal/execution/`
  - `ASK` mode added with session-context summarization output
  - `SEARCH` mode added with workspace path/content search output
  - `PLAN` mode added with task-list synthesis and session task-list persistence
  - cancellation semantics tightened so pre-start cancellation cannot be overwritten by `RUNNING`
  - execution-mode tests added for `ASK`, `SEARCH`, `PLAN`, and cancellation behavior
- First code-changing slice implemented:
  - `ARCHITECT` mode now writes a concrete workspace file in the Go runtime preview
  - job execution stores `artifacts/diff.txt` and exposes it through `GET /v1/jobs/{jobId}/diff`
  - session context is updated to include the generated workspace file fragment
  - `STATE_HINT` workspace-updated events are emitted for the edit
  - end-to-end ARCHITECT test coverage added for job completion, workspace file creation, and diff retrieval
- Second code-changing slice implemented:
  - `CODE` mode now performs a single-shot preview edit against the first attached editable file when available
  - when no file is attached, `CODE` falls back to a generated workspace output file
  - `CODE` persists `artifacts/diff.txt`, updates the session file fragment, and reports the selected code model in the result text
  - end-to-end CODE test coverage added for attached-file mutation and diff retrieval
- First multi-step execution slice implemented:
  - `LUTZ` now generates a three-step task list, persists it to session tasklist state, and executes the tasks sequentially
  - tasks are marked done as execution advances and job progress is updated after each top-level task
  - `LUTZ` writes a cumulative workspace edit plus `artifacts/diff.txt`
  - planning and execution notifications are emitted into the durable event stream
  - end-to-end LUTZ test coverage added for tasklist persistence, sequential completion, and diff retrieval
- Initial analyzer-backed slice implemented:
  - new `go-runtime/internal/analyzer/` package added with an on-demand workspace symbol index
  - `GET /v1/completions` now merges symbol completions with file completions for non-path queries
  - `POST /v1/context/classes` now resolves and attaches matching class summaries
  - `POST /v1/context/methods` now resolves and attaches matching method sources
  - analyzer-backed API tests added for symbol completion and class/method attachment
- Analyzer-backed SEARCH expansion implemented:
  - `SEARCH` now checks the symbol index for class and method matches before falling back to raw workspace file/path scans
  - symbol-aware SEARCH output includes fqName and snippet context for class/method matches
  - SEARCH tests now cover analyzer-backed method discovery in addition to raw file/content search
- Reservation and activity-history slice implemented:
  - single active job reservation semantics now reject a second concurrent `POST /v1/jobs` with `409` and `JOB_IN_PROGRESS`
  - `GET /v1/activity` now returns a compatibility-oriented recent-jobs group derived from persisted job metadata
  - `GET /v1/activity/diff?contextId=...` now returns persisted `diff.txt` artifacts for code-changing jobs
  - undo, redo, and richer historical activity actions are still not ported
- Initial REVIEW slice implemented:
  - `REVIEW` mode now runs through the Go job runner and validates the expected PR metadata tags
  - dedicated `POST /v1/jobs/pr-review` route added for PR review job creation
  - review execution scans attached or workspace files for deterministic review findings and writes `review.md` plus `review.json` artifacts
  - REVIEW emits compatibility-oriented notifications and a `reviewReady` state hint
  - GitHub posting, annotated remote diff retrieval, and the full Java review prompt pipeline are still not ported
- REVIEW utility and artifact fidelity improved:
  - new `go-runtime/internal/review/` package added with Java-aligned severity normalization, inline-comment filtering, and unified-diff line annotation helpers
  - REVIEW artifacts now use Java-style `summaryMarkdown` and `bodyMarkdown` JSON fields
  - REVIEW now writes `diff.txt` and `annotated-diff.txt` artifacts in addition to `review.md` and `review.json`
  - when a local git repository is available, REVIEW prefers a local git diff; otherwise it falls back to a synthetic workspace diff
  - review utility tests added for severity filtering and diff annotation behavior
- Initial GitHub-connected REVIEW posting added:
  - new `go-runtime/internal/githubclient/` package added for PR metadata fetch, summary comment posting, inline comment listing, and inline comment posting with HTTP 422 fallback
  - `POST /v1/jobs/pr-review` now accepts an optional `githubApiUrl` override for local integration testing of GitHub review flows
  - REVIEW can now fetch PR metadata and post the generated summary plus filtered inline comments when GitHub review identity is present
  - duplicate inline comments are skipped based on path and line, matching the Java flow more closely
  - full default GitHub production wiring, remote PR ref fetch, and the Java LLM review prompt pipeline are still not fully ported
- Java-style remote ref diff path added for REVIEW:
  - REVIEW now prefers fetched remote refs over local `HEAD` heuristics when GitHub review identity is configured
  - the runner resolves a remote name with `origin` preference, emits a `Fetching PR refs from remote '...'` notification, fetches `pull/<n>/head` into `refs/remotes/<remote>/pr/<n>`, fetches the base branch, and diffs those refs
  - end-to-end tests now cover a bare git remote plus `refs/pull/<n>/head` flow to verify the fetched PR diff path
  - local preview workspaces still skip GitHub review fetch/post behavior when no `.git` repo is materialized
- REVIEW prompt pipeline added:
  - the new `go-runtime/internal/review/` helpers now build a Java-aligned PR review prompt with escaped PR intent blocks, diff fencing, severity policy, and strict JSON output requirements
  - REVIEW now writes `review-prompt.txt` and `review-response.json` artifacts in addition to the parsed review artifacts
  - the runner now passes deterministic review output through the same parse/validation step the Java flow relies on before using the structured response
  - review tests now cover prompt policy text and wrapped JSON response parsing behavior
- REVIEW generation now uses annotated diff content directly:
  - heuristic review output is now derived from annotated diff lines and diff file headers instead of a separate workspace file-scan path
  - generated inline comments now use diff-selected file paths and line numbers, which is much closer to the Java review flow contract
  - review utility tests now cover diff-driven comment generation and line selection
- REVIEW GitHub production-default gating improved:
  - GitHub review fetch/post behavior is now keyed off GitHub review identity plus a materialized workspace `.git` repo, instead of only the explicit `githubApiUrl` override
  - the default GitHub API base now falls back to `https://api.github.com` when no override is supplied
  - local preview workspaces continue to produce review artifacts but skip live GitHub operations unless they are backed by a real git repository
  - app tests now cover the production-default review path with a local git-backed workspace in addition to the fake GitHub server flow
- REVIEW runtime seam and pre-scan context slice added:
  - new `go-runtime/internal/reviewruntime/` package added with a dedicated generator interface and a default heuristic review generator
  - REVIEW now performs a lightweight "Brokk Context Engine" pre-scan that builds `review-context.md` and `review-context.json` artifacts from touched files, session fragments, and analyzer hits
  - the review prompt now includes an explicit workspace-context block, which brings the Go flow closer to the Java `SearchAgent` pre-scan plus review-generation shape
  - runner notifications now include context-engine start/complete messages and the selected review generation provider
  - end-to-end tests now verify the context artifacts and provider notifications, and unit tests cover the new review runtime package
- REVIEW model-backed generator slice added:
  - `go-runtime/internal/reviewruntime/` now includes an OpenAI-compatible HTTP generator that posts the review prompt to `/v1/chat/completions`
  - the provider is configured from existing executor settings: `BROKK_API_KEY`, `PROXY_SETTING`, and `--vendor`
  - proxy routing currently maps `BROKK` to `https://proxy.brokk.ai`, `STAGING` to `https://staging.brokk.ai`, and `LOCALHOST` to `http://localhost:4000`
  - REVIEW now uses the configured HTTP model path when a Brokk API key is present, but falls back to heuristic generation if the provider is unavailable or returns an error
  - unit tests now cover OpenAI-compatible request/response parsing, proxy URL selection, and provider fallback behavior
- REVIEW malformed-output repair slice added:
  - the OpenAI-compatible review generator now validates the returned review JSON before handing control back to the runner
  - when the provider returns malformed content, the generator issues up to two repair attempts using a strict "return only the JSON object" correction prompt
  - repaired responses are surfaced through the provider label (for example `openai-compatible (repaired 1x)`) so event streams and artifacts retain some provenance
  - if repair still fails, the existing fallback generator chain remains responsible for degrading to heuristic review generation
  - unit tests now cover malformed-response repair and repaired-provider labeling

## How To Update This Plan

When work starts on a component:

- Mark the checklist item in progress in a follow-up edit if useful.
- Add newly discovered dependencies or hidden contracts to the relevant section.
- Add decisions to the decision log instead of relying on memory.
- Do not delete old decisions unless they were plainly wrong; supersede them with a new dated note.
