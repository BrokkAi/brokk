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

Or create it via the API instead of a file:

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

Agents are markdown files with YAML frontmatter. The filename must match the agent name (e.g., `security-auditor.md` for an agent named `security-auditor`).

```markdown
---
name: <agent-name>
description: <what the agent does>
tools:                          # optional — defaults to a broad search+workspace set
  - <tool-name>
  - <tool-name>
model: <model-name-or-id>      # optional — defaults to the job's plannerModel
maxTurns: <number>              # optional — defaults to 20
---

<system prompt — this is what the agent "is" and how it should behave>
```

### Frontmatter Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `name` | yes | -- | Lowercase letters, digits, and hyphens (e.g., `my-agent-2`) |
| `description` | yes | -- | Short description of what the agent does |
| `tools` | no | All search + workspace tools | Which tools the agent can use (see [Available Tools](#available-tools)) |
| `model` | no | Job's `plannerModel` | LLM model to use (e.g., `claude-sonnet-4-20250514`) |
| `maxTurns` | no | 20 | Maximum number of tool-calling turns before the agent must answer |

### System Prompt

The markdown body below the `---` closing delimiter is the agent's system prompt. This is where you define the agent's personality, instructions, and output format. Write it as if you're briefing a colleague:

- **Who** they are (role, expertise)
- **What** they should do (step-by-step workflow)
- **How** they should respond (format, structure, level of detail)

## Storage and Layering

Agents are stored in two locations, with project overriding user:

| Location | Scope | Example |
|----------|-------|---------|
| `.brokk/agents/` | Project (committed with your repo) | `.brokk/agents/security-auditor.md` |
| `~/.brokk/agents/` | User (shared across all projects) | `~/.brokk/agents/explain-code.md` |

If both levels define an agent with the same name, the **project-level** definition wins. This lets teams share standard agents via the repo while individuals can customize or add their own.

## Available Tools

When you specify a `tools` list, you're choosing which capabilities the agent has. If you omit `tools`, the agent gets a broad default set (all search and workspace tools appropriate for your project).

### Search Tools (read-only, find code)

| Tool | What it does |
|------|-------------|
| `searchSymbols` | Find classes, functions, fields by name pattern |
| `scanUsages` | Find where a symbol is used/called across the codebase |
| `getSymbolLocations` | Get file locations for fully qualified symbol names |
| `findFilesContaining` | Find files containing a regex pattern |
| `findFilenames` | Search for files by name |
| `searchFileContents` | Regex search in file contents with context lines |
| `searchGitCommitMessages` | Search commit messages by pattern |
| `getGitLog` | Git log for a file or directory |
| `explainCommit` | Explain what a commit changed and why |
| `xmlSkim` | Structural overview of XML/HTML files |
| `xmlSelect` | Query XML/HTML with XPath |
| `jq` | Query JSON files with jq expressions |

### Read Tools (load source into workspace)

| Tool | What it does |
|------|-------------|
| `getClassSources` | Full source of classes by fully qualified name |
| `getMethodSources` | Source of specific methods |
| `getClassSkeletons` | Class skeletons (fields + method signatures, no bodies) |
| `getFileSummaries` | File summaries (top-level declarations) |
| `getFileContents` | Read full file contents |
| `skimFiles` | Quick overview of files showing declarations |
| `listFiles` | Directory listing |

### Workspace Tools (add/remove context)

| Tool | What it does |
|------|-------------|
| `addFilesToWorkspace` | Add files to the agent's working context |
| `addClassesToWorkspace` | Add class sources to context |
| `addClassSummariesToWorkspace` | Add class summaries to context |
| `addMethodsToWorkspace` | Add method sources to context |
| `addFileSummariesToWorkspace` | Add file summaries to context |
| `addLineRangeToWorkspace` | Add specific line ranges to context |
| `addUrlContentsToWorkspace` | Load URL content into context |
| `dropWorkspaceFragments` | Remove items from context |
| `createOrReplaceTaskList` | Create a task list |

### Other Tools

| Tool | What it does |
|------|-------------|
| `runShellCommand` | Execute a shell command |
| `importDependency` | Import a project dependency |

### Always Available

These are always included regardless of your `tools` list:
- `answer` — provide the final answer (ends the agent loop)
- `abortSearch` — abort if the task can't be completed
- `think` — step-by-step reasoning (internal scratchpad)

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
model: claude-sonnet-4-20250514
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

## API Reference

For the full CRUD API reference and curl examples, see:
- [Headless Executor API Endpoints](headless-executor.md#custom-agents-authenticated)
- [curl Examples](headless-executor-testing-with-curl.md#custom-agents)
