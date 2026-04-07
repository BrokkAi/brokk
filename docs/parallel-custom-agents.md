# Parallel custom agents (client-side)

_Last update: 7 April 2026_

This document describes how a **client orchestrator** (IDE plugin, CLI, or another agent) can run **multiple stored custom agents** in parallel against the headless executor, then merge or interpret results.

Brokk does not expose a single job mode that fans out to N custom agents. Instead, parallelism is a **client concern**: submit several jobs, each bound to one custom agent definition. Custom agents are Markdown files with YAML frontmatter, registered in the executor's agent store; each runs inside `CustomAgentExecutor` with the tools listed in its definition (including optional `computeCyclomaticComplexity` and `analyzeCommentSemantics` from `CodeQualityTools`).

## Prerequisites

- **Stored agents**: Each workflow (e.g. complexity scan, comment heuristics, git-adjacent review) has a name such as `code-quality-complexity` and exists in the agent store the executor loads.
- **Tool allowlists**: In each agent file, list only the tools that agent needs. Code-quality helpers include:
  - `computeCyclomaticComplexity`
  - `analyzeCommentSemantics`
  Plus the usual search/workspace tools as needed.

## HTTP: one job per custom agent (recommended for parallelism)

Use `POST /v1/jobs` with:

- **`plannerModel`**: Required (non-blank). For `SEARCH` jobs this is the scan/chat model used by the outer loop.
- **`agent`**: Name of the stored custom agent to run (e.g. `code-quality-complexity`).
- **`taskInput`**: Task for that agent (scope, paths, thresholds, what to return).
- **`tags.mode`**: Omit or set to `SEARCH`. If you pass `agent`, the server sets mode to `SEARCH` when needed and rewrites the task so the outer `LutzAgent` uses `callCustomAgent` to invoke your definition.

The important behavior: **each request with a distinct `agent` value runs one custom agent** for the given `taskInput`. For **parallel** runs, issue **N requests** with the same or different `agent` names and distinct `Idempotency-Key` values, and **await all jobs** (poll `GET /v1/jobs/{jobId}` or subscribe to events).

### Example pattern

1. **Job A** — `agent: code-quality-complexity`, `taskInput`: list of Java packages to scan and reporting format.
2. **Job B** — `agent: code-quality-comments`, `taskInput`: same file set or overlapping set.
3. **Client** waits for both to reach a terminal state, then reads final output from events or task history as your integration already does.

### Optional: `scanModel`

If the project defines a default scan model, you can omit `scanModel`. Otherwise pass `scanModel` so the outer search loop and nested `CustomAgentExecutor` use the intended model.

## Single job, sequential delegation (no extra parallelism)

If you do **not** need wall-clock parallelism, a single `POST /v1/jobs` with **`agent` unset** and `tags.mode: SEARCH` can use a **free-form `taskInput`** that instructs the model to call `callCustomAgent` multiple times (different agent names) in sequence. That uses one job and one outer loop; throughput is lower than N parallel jobs.

## Aggregating results

- Treat each job's successful completion as one **independent report** (markdown in the stop details / streamed output).
- Your client should **merge** summaries, dedupe file paths, and present a combined view. The server does not merge parallel custom-agent jobs for you.

## Idempotency and retries

- Send a unique **`Idempotency-Key`** per logical run. Replays with the same key return the same job as the API contract for `/v1/jobs`.
- For parallel batches, use **one key per job** so each sub-agent run is distinct.

## Failure handling

- If one parallel job fails, others may still succeed. Decide in the client whether to fail the whole batch or report partial results.
- Validate agent names **before** fan-out (unknown agent names return **400** at job creation).

## Related endpoints

- **`GET /v1/context/analytics/git-hotspots`**: Same analysis as tool `analyzeGitHotspots` (JSON). Query parameters: `since` (e.g. `180d` or ISO-8601 instant), optional `until` (ISO-8601 exclusive end), `maxCommits`, optional `maxFiles` (0 = unlimited file rows in JSON).

For parallel custom-agent workflows, **mode stays `SEARCH`** (or whatever your orchestrator uses); behavior is encoded in stored agent definitions plus client orchestration.

---

## Appendix: Modes and tools (reference)

Values below are defined in the Java sources ([`JobRunner.Mode`](../app/src/main/java/ai/brokk/executor/jobs/JobRunner.java), [`SearchPrompts.Objective`](../app/src/main/java/ai/brokk/prompts/SearchPrompts.java), [`AgentDefinition.KNOWN_TOOL_NAMES`](../app/src/main/java/ai/brokk/executor/agents/AgentDefinition.java)). Update this section when those APIs change.

### A. Job modes (`tags.mode` / `POST /v1/jobs`)

| Mode | Notes |
|------|--------|
| `ARCHITECT` | Default when `mode` omitted or invalid; architect-style planning and coding loop. |
| `ASK` | Direct Q&A against the repo. |
| `SEARCH` | Read-only search (`LutzAgent`); use with optional `agent` for `callCustomAgent`. |
| `REVIEW` | PR review (requires GitHub tags). |
| `GUIDED_REVIEW` | Guided review with planner model from tags. |
| `LUTZ` | Lutz search/workspace flow with broader terminal options. |
| `PLAN` | Planning without full architect loop semantics per job wiring. |
| `CODE` | Code agent execution. |
| `ISSUE` | Issue-style workflow. |
| `ISSUE_DIAGNOSE` | Issue diagnosis path. |
| `ISSUE_WRITER` | Issue creation path. |
| `LITE_AGENT` | Lighter agent with constrained tooling. |
| `LITE_PLAN` | Read-only plan variant of lite agent. |

### B. Search objectives (`SearchPrompts.Objective`)

Used internally to pick prompts and **terminal** tools (how the run may finish). Not the same string as `tags.mode`, but each job mode maps to one of these via `JobRunner.objectiveForMode`.

- `ANSWER_ONLY`
- `TASKS_ONLY`
- `LUTZ`
- `WORKSPACE_ONLY`
- `ISSUE_DESCRIPTION`
- `CODE_ONLY`

### C. Tools allowed in custom agent definitions (`tools` in YAML)

These names are validated against `AgentDefinition.KNOWN_TOOL_NAMES`. Actual availability may still be filtered by analyzer/project capabilities (e.g. git, XML, JSON).

**Search / repo**

- `searchSymbols`, `scanUsages`, `getSymbolLocations`, `skimFiles`, `findFilesContaining`, `findFilenames`, `searchFileContents`
- `getFileSummaries`, `getClassSkeletons`, `getClassSources`, `getMethodSources`, `getFileContents`, `listFiles`
- `searchGitCommitMessages`, `getGitLog`, `explainCommit`
- `xmlSkim`, `xmlSelect`, `jq`

**Workspace**

- `addFilesToWorkspace`, `addLineRangeToWorkspace`, `addClassesToWorkspace`, `addUrlContentsToWorkspace`, `addClassSummariesToWorkspace`, `addFileSummariesToWorkspace`, `addMethodsToWorkspace`, `dropWorkspaceFragments`, `createOrReplaceTaskList`

**Other**

- `runShellCommand`, `importDependency`
- `computeCyclomaticComplexity`, `analyzeCommentSemantics`, `analyzeGitHotspots` (code quality; git hotspots take `sinceDays` / optional ISO `sinceIso` and `untilIso`, plus `maxCommits` and `maxFiles` with a hard cap of 500 files)
- `answer`, `abortSearch` (always added if missing when resolving effective tools)
- `think` (always added if missing)

### D. Tools on the outer `SEARCH` loop only (not in `KNOWN_TOOL_NAMES`)

When the job runs `LutzAgent` in `SEARCH` (including when `agent` is set and the task is rewritten to call `callCustomAgent`), the model also gets tools such as:

- `callSearchAgent`, `callCustomAgent`
- `callMcpTool` (only if the project defines MCP tools)

Terminal tools depend on the search **objective** (e.g. `answer`, `workspaceComplete`, `createOrReplaceTaskList`, `callCodeAgent`, `describeIssue`, `askForClarification` in interactive Chrome, plus `abortSearch`). Dependency tools are registered when supported by the project.
