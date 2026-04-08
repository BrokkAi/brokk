# Parallel custom agents

_Last update: 7 April 2026_

This document describes how to use **multiple stored custom agents** in parallel when you want separate specialist analyses over the same repository scope.

The key constraint is operational: a single Brokk headless instance can run only **one job at a time**. That means "parallel custom agents" is primarily about **workspace-level fan-out inside one search run**, not submitting multiple concurrent jobs to the same executor. In practice, you ask the outer search agent to create task lists, prepare workspace context, and invoke `callCustomAgent` for distinct subtasks, or you use multiple executor instances if you truly need job-level concurrency.

Custom agents are Markdown files with YAML frontmatter, registered in the executor's agent store. Each runs inside `CustomAgentExecutor` with the tools listed in its definition.

## When to use this pattern

Use parallel custom agents when you want:
- One agent to inspect complexity hotspots
- Another to inspect comment quality or maintainability
- Another to inspect git churn or ownership risk
- A final synthesis step that merges what each specialist found

This works best when all agents can share a common prepared workspace or a coordinated file/symbol scope.

## Recommended approach: one job, workspace-oriented delegation

Use a single `POST /v1/jobs` request and let the outer search flow coordinate the specialist agents.

Send:

- **`plannerModel`**: Required (non-blank).
- **`taskInput`**: A prompt that instructs the outer agent to split the work, prepare workspace context, invoke the desired custom agents, and then merge their results.
- **`tags.mode`**: Usually `SEARCH`.
- **`agent`**: Usually omit this when orchestrating multiple custom agents from one run. Set it only when the whole job should be one specific custom agent.

Example orchestration intent:

1. Add the relevant files, classes, or line ranges to workspace.
2. Create task structure if useful.
3. Call one custom agent per specialty.
4. Collect each agent's output.
5. Return a merged final report with deduped paths and findings.

Because this all happens in one job, it matches the executor's single-job execution model.

## About true concurrency

If you submit multiple `/v1/jobs` requests to the same headless executor, they do not provide meaningful parallel execution because that Brokk instance handles one job at a time. To get true wall-clock parallelism, run against:
- multiple Brokk headless instances, or
- another client-side topology that distributes jobs across isolated executors.

If you only have one executor, prefer a single coordinated run.

## Aggregating results

Treat each custom agent as an independent specialist report generator. The outer search flow or your client integration should:

- merge summaries,
- dedupe file paths and symbols,
- preserve which agent produced which finding,
- and synthesize conflicts or overlaps into a final answer.

## Failure handling

- If one specialist agent fails, the outer run can still continue and report partial results.
- Validate agent names before relying on them in automation.
- Keep each custom agent tightly scoped so a failure is easier to isolate.

## Example task shape

A useful `taskInput` for one-job orchestration looks like this:

```text
Review src/main/java/com/acme/payments using multiple custom agents.
First add the relevant files and classes to workspace.
Then call:
- code-quality-complexity for complexity hotspots
- code-quality-comments for comment density and readability
- git-risk-review for churn and ownership signals
Finally merge the results into one report with:
- top findings
- deduped file list
- overlap between agents
- recommended follow-up actions
```

---

## Appendix: modes and custom-agent tool references

Values below are defined in the Java sources ([`JobRunner.Mode`](../app/src/main/java/ai/brokk/executor/jobs/JobRunner.java), [`SearchPrompts.Objective`](../app/src/main/java/ai/brokk/prompts/SearchPrompts.java), [`AgentDefinition.KNOWN_TOOL_NAMES`](../app/src/main/java/ai/brokk/executor/agents/AgentDefinition.java)). Update this section when those APIs change.

### A. Job modes (`tags.mode` / `POST /v1/jobs`)

| Mode | Notes |
|------|--------|
| `ARCHITECT` | Default when `mode` omitted or invalid; architect-style planning and coding loop. |
| `ASK` | Direct Q&A against the repo. |
| `SEARCH` | Read-only search (`LutzAgent`); best fit for coordinating custom agents. |
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

Used internally to pick prompts and terminal tools. Not the same string as `tags.mode`, but each job mode maps to one of these via `JobRunner.objectiveForMode`.

- `ANSWER_ONLY`
- `TASKS_ONLY`
- `LUTZ`
- `WORKSPACE_ONLY`
- `ISSUE_DESCRIPTION`
- `CODE_ONLY`

### C. Outer-loop tools relevant to custom-agent orchestration

When a job runs `LutzAgent` in `SEARCH`, the coordinating model may have tools such as:

- `callSearchAgent`
- `callCustomAgent`
- `callMcpTool` (only if the project defines MCP tools)

Terminal tools depend on the search objective and may include `answer`, `workspaceComplete`, `createOrReplaceTaskList`, `callCodeAgent`, `describeIssue`, `askForClarification`, and `abortSearch`.

For the detailed list of tools you can place in a custom agent's YAML `tools` field, see [Custom Agents](custom-agents.md#available-tools).
