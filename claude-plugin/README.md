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

[uv](https://docs.astral.sh/uv/) must be installed. The plugin runs `uvx brokk mcp-core`, which fetches the `brokk` package from PyPI automatically.

## Skills

The plugin adds the following skills to Claude Code:

| Skill | Description |
|-------|-------------|
| Code Navigation | Symbol searching, usage scanning, class skeleton navigation |
| Code Reading | Reading source code at different detail levels |
| Codebase Search | Text search, file discovery, directory listing |
| Git Exploration | Git commit history exploration |
| Workspace | Workspace activation and management |
| Structured Data | JSON and XML/HTML querying |
| PR Review | Adversarial multi-agent PR review with security, DRY, intent, devops, and architecture analysis |

## Agents

The plugin includes specialist agents used by the PR Review skill:

| Agent | Description |
|-------|-------------|
| security-reviewer | Hunts for injection, auth bypasses, data leaks, backdoors, and CVEs |
| dry-reviewer | Searches for code duplication and reimplemented functionality |
| senior-dev-reviewer | Verifies intent, catches smuggled changes, checks test coverage |
| devops-reviewer | Reviews infrastructure, CI/CD, and operational concerns |
| architect-reviewer | Evaluates coupling, cohesion, SOLID principles, and design quality |

## Additional prerequisites

The PR Review skill requires [gh](https://cli.github.com/) (GitHub CLI) installed and authenticated for reviewing PRs by number.
