# Custom Agents

Custom agents let you define reusable AI workflows with a specific system prompt, tool set, and model configuration. Once defined, you invoke them by name — Brokk handles tool registration, the agentic loop, and result streaming.

This follows the same pattern as [Claude Code custom agents](https://docs.anthropic.com/en/docs/claude-code/agents): markdown files with YAML frontmatter.

## Quick Start

### 1. Create an agent file

Create `.brokk/agents/code-reviewer.md` in your project:

```markdown
---
name: code-reviewer
description: Reviews code for quality, security, and best practices
tools:
  - searchSymbols
  - scanUsages
  - searchFileContents
  - getClassSources
  - getMethodSources
  - addFilesToWorkspace
maxTurns: 10
---

You are an expert code reviewer. When given a task:

1. Search for the relevant code using the available tools
2. Read the full source of classes and methods you need to review
3. Analyze for:
    - Correctness bugs and logic errors
    - Security vulnerabilities (injection, auth bypass, data exposure)
    - Performance issues (N+1 queries, unnecessary allocations, blocking calls)
    - Code style and maintainability
4. Provide your findings as a structured answer with severity levels
```

### 2. Invoke it

Custom agents are registered as the `callCustomAgent` tool, available to all built-in agents (SearchAgent, LutzAgent, ArchitectAgent). There are two ways to invoke them:

**Via the `agent` convenience field** (routes to SEARCH mode with an instruction to call the agent):

```bash
curl -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Idempotency-Key: review-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{
    "taskInput": "Review the authentication module for security issues",
    "plannerModel": "claude-sonnet-4-20250514",
    "agent": "code-reviewer"
  }'
```

**Or naturally from any mode** -- the LLM can call `callCustomAgent` whenever it decides to during any SEARCH, LUTZ, or ARCHITECT job:

```bash
curl -X POST "${BASE}/v1/jobs" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Idempotency-Key: lutz-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{
    "taskInput": "Use the code-reviewer agent to check the auth module, then fix any issues found",
    "plannerModel": "claude-sonnet-4-20250514",
    "tags": {"mode": "LUTZ"}
  }'
```

**Or create via the API** instead of a file:

```bash
curl -X POST "${BASE}/v1/agents" \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "code-reviewer",
    "description": "Reviews code for quality, security, and best practices",
    "tools": ["searchSymbols", "scanUsages", "searchFileContents", "getClassSources", "getMethodSources", "addFilesToWorkspace"],
    "maxTurns": 10,
    "systemPrompt": "You are an expert code reviewer. When given a task:\n\n1. Search for the relevant code using the available tools\n2. Read the full source of classes and methods you need to review\n3. Analyze for correctness, security, performance, and style\n4. Provide your findings as a structured answer with severity levels"
  }'
```

## Agent File Format

Agents are markdown files with YAML frontmatter. Filename/name matching is recommended (e.g., `security-auditor.md` for an agent named `security-auditor`) for consistency and predictable CRUD behavior, but job invocation resolves by the `name` field in frontmatter from the merged agent registry.

```markdown
---
name: <agent-name>
description: <what the agent does>
tools:                          # optional — defaults to a broad search+workspace set
  - <tool-name>
  - <tool-name>
maxTurns: <number>              # optional — defaults to 20
---

<system prompt — this is what the agent "is" and how it should behave>
```

## Recommended Response Contract For Aggregation

When one search run invokes multiple custom agents and then merges their outputs, use a machine-readable response contract so downstream synthesis stays deterministic.

Required first payload from each sub-agent:

```json
{
  "role": "Complexity Specialist",
  "tried": [
    "Computed complexity for top-level classes in src/core",
    "Compared hotspots against recent git churn"
  ],
  "found": [
    "High branching complexity in src/core/mapper.ts::map",
    "Ownership risk in src/service/auth.ts due to concentrated churn"
  ],
  "looked": [
    "src/core/mapper.ts",
    "src/service/auth.ts",
    "ElasticsearchMetadataFilterMapper.map"
  ]
}
```

Guidelines:
- Emit this JSON object first, with no markdown before it.
- Keep all keys present; use empty arrays when no items exist.
- Keep `looked` tied to concrete repository paths or symbols inspected.
- After the JSON object, include optional markdown narrative for humans.

### Frontmatter Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `name` | yes | -- | Lowercase letters, digits, and hyphens (e.g., `my-agent-2`) |
| `description` | yes | -- | Short description of what the agent does |
| `tools` | no | All search + workspace tools | Which tools the agent can use (see [Available Tools](#available-tools)) |
| `maxTurns` | no | 20 | Maximum number of tool-calling turns before the agent must answer |

The agent inherits the model from the parent agent that invokes it (the job's planner model for SEARCH jobs, or the parent agent's model for LUTZ/ARCHITECT jobs). This follows the same pattern as Claude Code — agents define *what* to do, the caller decides *which model* to use.

### System Prompt

The markdown body below the `---` closing delimiter is the agent's system prompt. This is where you define the agent's personality, instructions, and output format. Write it as if you're briefing a colleague:

- **Who** they are (role, expertise)
- **What** they should do (step-by-step workflow)
- **How** they should respond (format, structure, level of detail)

## How Agents Run

Custom agents are registered as the `callCustomAgent` tool on all built-in agents. When the LLM (or the `agent` convenience field) invokes a custom agent:

1. Brokk loads the agent definition (system prompt, tools, model, maxTurns)
2. A sub-agent is spawned with the agent's system prompt as its own LLM session
3. The task is sent as the first user message, along with the current workspace table of contents
4. The sub-agent enters its own **agentic loop**: calling tools, getting results, repeating up to `maxTurns` times
5. The loop ends when the sub-agent calls `answer` (success) or `abortSearch` (gave up), or when the turn limit is reached
6. The answer is returned to the parent agent as a tool result, which can continue its own work

**Composability:** Because agents are tools, they compose naturally. A LUTZ job can call a custom security-auditor agent, get the findings, and then call `callCodeAgent` to fix the issues — all in one job. This is the same pattern as `callSearchAgent` and `callCodeAgent`.

**Streaming results:** After submitting the job, poll `GET /v1/jobs/{jobId}/events` to stream tool calls, LLM output tokens, and the final answer in real time. See the [events documentation](headless-executor-events.md) for event types.

**Turn limit behavior:** If the agent reaches `maxTurns` without calling `answer`, the sub-agent returns a `TOOL_ERROR` to the parent. On the final turn, the agent is told it must finish — most models will comply. Increase `maxTurns` for complex tasks that require many search steps.

**Context:** The agent starts with whatever is in the current session's workspace. If you've added files or classes to context before running the job, the agent can see them. The agent can also add more context during execution using workspace tools.

## Writing Effective System Prompts

The system prompt is the most important part of your agent definition. A few tips:

**Be specific about the workflow.** Don't just say "review code" — tell the agent which tools to use in what order. Compare:

- Vague: "You are a code reviewer. Review code and report issues."
- Specific: "You are a code reviewer. First use `searchSymbols` to find the classes mentioned in the task. Then use `getClassSources` to read their full source. Analyze for X, Y, Z. Report findings in a table."

**Define the output format.** If you want structured output (tables, severity levels, numbered findings), say so explicitly. LLMs follow formatting instructions well.

**Match tools to the task.** If your agent only reads code, don't include `runShellCommand`. Fewer tools means fewer distractions for the model and faster execution.

**Set appropriate maxTurns.** Simple lookup tasks need 5-8 turns. Thorough analysis across a subsystem needs 15-20. If your agent keeps hitting the turn limit, either increase it or make the system prompt more focused.

## Choosing Tools

**Leave `tools` empty for general-purpose agents.** The default set includes all search and workspace tools, which is appropriate for most tasks. The default is dynamically filtered based on your project (e.g., git tools are only included if the project has a git repo, XML tools only if you have XML files).

**Restrict tools for focused agents.** If your agent should only search and read (not modify the workspace), omit the `add*ToWorkspace` tools. This makes the agent faster and prevents it from cluttering context with irrelevant files.

**Include `runShellCommand` carefully.** This gives the agent shell access. Useful for agents that need to run linters, check build output, or query APIs — but be aware it can execute arbitrary commands.

## Limitations

Custom agents are currently **read-only search agents**. They can search the codebase, read files, and provide answers, but they **cannot write or modify code**. For code changes, use the built-in ARCHITECT, CODE, or LUTZ modes.

Specifically, custom agents can:
- Search symbols, files, and git history
- Read class sources, method sources, and file contents
- Add/remove items from the workspace context
- Run shell commands (if `runShellCommand` is in the tool list)
- Provide a final answer in markdown

Custom agents cannot:
- Edit or create files
- Make git commits
- Create pull requests
- Run the build/test verification loop

## Storage and Layering

Agents are stored in two locations, with project overriding user:

| Location | Scope | Example |
|----------|-------|---------|
| `.brokk/agents/` | Project (committed with your repo) | `.brokk/agents/security-auditor.md` |
| `~/.brokk/agents/` | User (shared across all projects) | `~/.brokk/agents/explain-code.md` |

If both levels define an agent with the same name, the **project-level** definition wins. This lets teams share standard agents via the repo while individuals can customize or add their own.

## Available Tools

When you specify a `tools` list, you're choosing which capabilities the agent has. If you omit `tools`, the agent gets a broad default set of search and workspace tools appropriate for your project. Actual availability may still depend on project capabilities such as git, XML, JSON, or Java analysis support.

### Search and repository tools

| Tool | What it does |
|------|-------------|
| `searchSymbols` | Find classes, functions, and fields by name pattern |
| `scanUsages` | Find where a symbol is used or called across the codebase |
| `getSymbolLocations` | Get file locations for fully qualified symbol names |
| `findFilesContaining` | Find files containing a regex pattern |
| `findFilenames` | Search for files by name |
| `searchFileContents` | Regex search in file contents with context lines |
| `skimFiles` | Quick overview of files showing declarations |
| `listFiles` | Directory listing |
| `getFileSummaries` | File summaries with top-level declarations |
| `getClassSkeletons` | Class skeletons with fields and method signatures |
| `getClassSources` | Full source of classes by fully qualified name |
| `getMethodSources` | Source of specific methods |
| `getFileContents` | Read full file contents |
| `searchGitCommitMessages` | Search commit messages by pattern |
| `getGitLog` | Git log for a file or directory |
| `explainCommit` | Explain what a commit changed and why |
| `xmlSkim` | Structural overview of XML or HTML files |
| `xmlSelect` | Query XML or HTML with XPath |
| `jq` | Query JSON files with jq expressions |

### Workspace tools

| Tool | What it does |
|------|-------------|
| `addFilesToWorkspace` | Add files to the agent's working context |
| `addLineRangeToWorkspace` | Add specific line ranges to context |
| `addClassesToWorkspace` | Add class sources to context |
| `addUrlContentsToWorkspace` | Load URL content into context |
| `addClassSummariesToWorkspace` | Add class summaries to context |
| `addFileSummariesToWorkspace` | Add file summaries to context |
| `addMethodsToWorkspace` | Add method sources to context |
| `dropWorkspaceFragments` | Remove items from context |
| `createOrReplaceTaskList` | Create or replace a task list |

### Code-quality and utility tools

| Tool | What it does |
|------|-------------|
| `runShellCommand` | Execute a shell command |
| `importDependency` | Import a project dependency |
| `computeCyclomaticComplexity` | Compute cyclomatic complexity for Java code when analysis data is available |
| `reportCommentDensityForCodeUnit` | Report comment density for one Java symbol, with bounded output |
| `reportCommentDensityForFiles` | Report comment density tables for Java files, with bounded output |
| `analyzeGitHotspots` | Analyze churn hotspots using bounded commit and file limits |

Java comment-density tools return a short message when the analyzer has no Java snapshot. `analyzeGitHotspots` supports `sinceDays` and optional ISO `sinceIso` and `untilIso`, plus bounded `maxCommits` and `maxFiles`.

### Always available

These are always included regardless of your `tools` list:
- `answer` - provide the final answer and end the agent loop
- `abortSearch` - abort if the task cannot be completed
- `think` - internal scratchpad reasoning

## Example Agents

### Security Auditor

`.brokk/agents/security-auditor.md`:

```markdown
---
name: security-auditor
description: Scans code for OWASP top 10 vulnerabilities
tools:
  - searchSymbols
  - scanUsages
  - searchFileContents
  - getClassSources
  - getMethodSources
  - addFilesToWorkspace
maxTurns: 15
---

You are a security auditor specializing in application security.

When analyzing code, systematically check for:
- **Injection** (SQL, command, LDAP, XPath): trace user input to sinks
- **Broken Authentication**: weak password handling, session management flaws
- **Sensitive Data Exposure**: hardcoded secrets, unencrypted storage, logging PII
- **XXE**: XML parsing without disabling external entities
- **Broken Access Control**: missing authorization checks, IDOR
- **Security Misconfiguration**: debug mode, default credentials, overly permissive CORS
- **XSS**: unescaped output in HTML/JS contexts
- **Insecure Deserialization**: untrusted data in ObjectInputStream, JSON deserializers
- **Known Vulnerabilities**: outdated libraries with CVEs
- **Insufficient Logging**: missing audit trails for security-sensitive operations

For each finding, report:
1. **Severity** (Critical/High/Medium/Low)
2. **Location** (file:line)
3. **Description** of the vulnerability
4. **Impact** if exploited
5. **Remediation** with a concrete code suggestion
```

### Architecture Explainer

`~/.brokk/agents/explain-architecture.md` (user-level, works across all projects):

```markdown
---
name: explain-architecture
description: Explains how a codebase or subsystem is architected
tools:
  - searchSymbols
  - scanUsages
  - getClassSkeletons
  - getFileSummaries
  - skimFiles
  - listFiles
  - findFilenames
maxTurns: 20
---

You are a software architect who explains codebases clearly.

When asked about a system or subsystem:

1. **Discover structure**: use listFiles and findFilenames to understand the directory layout
2. **Identify key abstractions**: use searchSymbols to find main classes, interfaces, and entry points
3. **Trace relationships**: use scanUsages to understand how components connect
4. **Read skeletons**: use getClassSkeletons and getFileSummaries for an overview without drowning in implementation details
5. **Dive selectively**: only read full source when a specific pattern or algorithm needs explanation

Present your analysis as:
- **Overview**: 2-3 sentence summary of what this subsystem does
- **Key Components**: table of main classes/interfaces with one-line descriptions
- **Data Flow**: how data moves through the system (request lifecycle, event flow, etc.)
- **Design Patterns**: notable patterns used and why
- **Extension Points**: where and how to add new functionality
```

### Test Gap Finder

`.brokk/agents/test-gaps.md`:

```markdown
---
name: test-gaps
description: Finds untested code paths and suggests test cases
tools:
  - searchSymbols
  - scanUsages
  - getClassSources
  - getMethodSources
  - findFilenames
  - searchFileContents
  - addFilesToWorkspace
maxTurns: 15
---

You are a test engineering specialist focused on finding gaps in test coverage.

Process:
1. Find the production code relevant to the user's query
2. Find existing tests by searching for test files (look for *Test.java, *Spec.java, test_*.py, etc.)
3. Read both the production code and existing tests
4. Identify untested code paths:
    - Public methods with no corresponding test
    - Branch conditions (if/else, switch) not covered
    - Error handling paths (catch blocks, edge cases)
    - Boundary conditions (null, empty, max values)
    - Integration points (API calls, database queries)

For each gap, suggest:
- **What to test**: the specific behavior or path
- **Why it matters**: what could go wrong if untested
- **Test outline**: a sketch of the test case (setup, action, assertion)

Prioritize by risk: untested error handling and security-sensitive paths first.
```

### Dependency Analyzer

`.brokk/agents/dep-check.md`:

```markdown
---
name: dep-check
description: Analyzes project dependencies for issues and upgrade opportunities
tools:
  - findFilenames
  - getFileContents
  - searchFileContents
  - xmlSkim
  - xmlSelect
  - jq
maxTurns: 10
---

You are a dependency management specialist.

When analyzing dependencies:
1. Find build files (pom.xml, build.gradle, package.json, requirements.txt, Cargo.toml, etc.)
2. Read them to understand current dependency versions
3. Check for:
    - Outdated major versions that may have breaking changes
    - Multiple versions of the same library (version conflicts)
    - Unused dependencies (declared but never imported)
    - Dependencies that overlap in functionality
    - Dependencies with known security issues (based on your training data)

Present findings as a prioritized list with:
- **Dependency**: name and current version
- **Issue**: what's wrong
- **Recommendation**: specific action to take
- **Risk**: what could break if you change it
```

## Troubleshooting

### "Unknown agent: my-agent" when submitting a job

The agent name doesn't match any loaded definition in `.brokk/agents/` or `~/.brokk/agents/`. Check:
- A file exists in one of those directories and has valid YAML frontmatter
- The `name` field inside the YAML frontmatter exactly matches `"agent"` in your job request
- The name uses only lowercase letters, digits, and hyphens

If `GET /v1/agents` includes the agent but `GET /v1/agents/{name}` does not, align filename and frontmatter name (recommended: `<name>.md`).

### "name must match [a-z][a-z0-9-]*"

Agent names must start with a lowercase letter and contain only lowercase letters, digits, and hyphens. No uppercase, spaces, dots, or underscores.

### "unknown tool: myTool"

The tool name in your `tools` list doesn't match any known tool. Tool names are case-sensitive and must exactly match the names in the [Available Tools](#available-tools) section.

### Agent hits turn limit without answering

The agent ran out of turns before calling `answer`. Either:
- Increase `maxTurns` in the agent definition
- Make the system prompt more focused so the agent converges faster
- Narrow the `taskInput` to a more specific question

### Agent ignores tools and answers immediately

Your system prompt may not be instructing the agent to use tools. Be explicit: "Use `searchSymbols` to find..." rather than "Search for...". Also ensure the tools you reference in the prompt are in the `tools` list.

## API Reference

For the full CRUD API reference and curl examples, see:
- [Headless Executor API Endpoints](headless-executor.md#custom-agents-authenticated)
- [curl Examples](headless-executor-testing-with-curl.md#custom-agents)
