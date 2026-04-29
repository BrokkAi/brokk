# Brokk Claude Code Plugin

Semantic code intelligence for [Claude Code](https://docs.anthropic.com/en/docs/claude-code) -- symbol navigation, cross-reference analysis, and structural code understanding powered by tree-sitter.

## Install from marketplace

```shell
/plugin marketplace add BrokkAi/brokk
/plugin install brokk@brokk-marketplace
```

## Local testing (from a clone of this repo)

```shell
claude --plugin-dir ./claude-plugin
```

## Prerequisites

[uv](https://docs.astral.sh/uv/) must be installed. The plugin runs `uvx brokk bifrost`, which fetches the `brokk` package from PyPI and downloads the [bifrost](https://github.com/BrokkAi/bifrost) native MCP server (currently pinned to v0.1.2) on first use. Bifrost ships native binaries for arm64 macOS, x86_64/aarch64 Linux, and x86_64/aarch64 Windows; Intel macOS is not supported.

## Skills

The plugin adds the following skills to Claude Code:

| Skill | Invocation | Description |
|-------|------------|-------------|
| Code Navigation | `/brokk:code-navigation` | Symbol searching, usage scanning, class skeleton navigation |
| Code Reading | `/brokk:code-reading` | Reading source code at different detail levels |
| Codebase Search | `/brokk:codebase-search` | Text search, file discovery, directory listing |
| Git Exploration | `/brokk:git-exploration` | Git commit history exploration |
| Guided Issue | `/brokk:guided-issue` | End-to-end issue resolution: select a GitHub issue, diagnose the codebase, plan changes, implement in an isolated branch, review with specialist agents, and open a pull request |
| Guided Review | `/brokk:guided-review` | Interactive guided code review: run parallel agents, then walk through findings one-by-one with code context and triage |
| PR Review | `/brokk:review-pr` | Adversarial multi-agent PR review with security, DRY, intent, devops, and architecture analysis |
| Structured Data | `/brokk:structured-data` | JSON and XML/HTML querying |
| Today | `/brokk:today` | Suggest GitHub issues to work on today, pick which ones, and generate a Slack-ready summary |
| Workspace | `/brokk:workspace` | Workspace activation and management |
| Write Issue | `/brokk:write-issue` | Draft a new GitHub issue with an AI-enhanced description referencing real source code, affected components, and suggested starting points |

## Skill usage examples

### Tool-guidance skills

These skills load tool-selection tips and usage guidance into the conversation.
Claude also invokes them automatically when your request matches -- for example,
asking "show me the SearchTools class" triggers `code-reading` behind the scenes.
You can invoke them explicitly to prime the context before asking your question.

**Code Navigation** -- Find where symbols are defined and who calls them:
```
/brokk:code-navigation
```
Then ask: `Find all implementations of the IAnalyzer interface`

**Code Reading** -- Read source code at the right level of detail:
```
/brokk:code-reading
```
Then ask: `Show me the full source of the SearchTools class`

**Codebase Search** -- Text search and file discovery:
```
/brokk:codebase-search
```
Then ask: `Find all files containing "TODO" in the brokk-core module`

**Git Exploration** -- Understand change history:
```
/brokk:git-exploration
```
Then ask: `What commits touched BrokkCoreMcpServer.java in the last month?`

**Structured Data** -- Query JSON and XML/HTML files:
```
/brokk:structured-data
```
Then ask: `What dependencies are declared in package.json?`

**Workspace** -- Set which project the server analyzes:
```
/brokk:workspace
```
Then ask: `Activate the workspace at /home/user/projects/my-app`

### Workflow skills

These skills drive multi-step processes and accept arguments directly.

**Guided Issue** -- End-to-end issue resolution workflow:
```
/brokk:guided-issue 3349
```

**Guided Review** -- Interactive guided code review with triage:
```
/brokk:guided-review
```

**PR Review** -- Adversarial multi-agent review of a pull request:
```
/brokk:review-pr 42
```

**Today** -- Daily planning with GitHub issues:
```
/brokk:today
```

**Write Issue** -- Draft a GitHub issue with code references:
```
/brokk:write-issue Draft an issue about the missing error handling in parseJsonRequest
```

## Agents

The plugin includes specialist agents used by the review and issue resolution skills:

| Agent | Description |
|-------|-------------|
| architect-reviewer | Evaluates coupling, cohesion, SOLID principles, and design quality |
| devops-reviewer | Reviews infrastructure, CI/CD, and operational concerns |
| dry-reviewer | Searches for code duplication and reimplemented functionality |
| issue-diagnostician | Explores codebase to diagnose a GitHub issue: identifies affected files, traces code paths, and forms a root cause hypothesis |
| issue-enhancer | Enhances a draft GitHub issue with relevant source code references, affected components, and technical context |
| issue-planner | Takes a diagnosis and produces an ordered list of concrete code changes with specific file paths, method names, and descriptions |
| security-reviewer | Hunts for injection, auth bypasses, data leaks, backdoors, and CVEs |
| senior-dev-reviewer | Verifies intent, catches smuggled changes, checks test coverage |

## Additional prerequisites

The PR Review, Guided Review, Guided Issue, Today, and Write Issue skills require [gh](https://cli.github.com/) (GitHub CLI) installed and authenticated.
