---
name: brokk-git-exploration
description: >-
  Explore change history using Brokk's search_git_commit_messages,
  get_git_log, get_commit_diff, and analyze_git_hotspots tools, with Bash
  git/gh for blame and GitHub lookups.
---

# Git Exploration

Bifrost exposes MCP tools for commit history; use them first. For blame,
arbitrary diffs between two commits, and PR/issue lookups, fall back to
the built-in `Bash` tool with `git` and `gh`.

## Tools

| Tool | Purpose |
|---|---|
| `search_git_commit_messages` | Regex search across commit messages; returns matching commits with their edited files |
| `get_git_log` | Recent commits, optionally filtered to those touching a given path |
| `get_commit_diff` | Unified diff for a single commit versus its parent (bounded by file count and lines per file) |
| `analyze_git_hotspots` | Churn x complexity hotspots: correlates recent commit activity with per-file cyclomatic complexity |

## Bash fallbacks

| Goal | Command |
|---|---|
| Who last touched each line | `git blame path/to/file` |
| Diff between two arbitrary commits | `git diff <a>..<b> -- path/to/file` |
| Search commit *content* (added/removed lines) | `git log -S 'literal' --oneline` or `git log -G 'regex' --oneline` |
| Find when a symbol was introduced | `git log --diff-filter=A -S 'symbol_name' -- path/` |
| List PRs that touched a file | `gh pr list --state all --search 'path/to/file in:title,body'` |
| Show a PR | `gh pr view <number>` |
| Show an issue | `gh issue view <number>` |
| Unpushed commits on this branch | `git log @{u}..HEAD` |

## Tips

- For "what changed recently in this area?", `get_git_log` with a
  `file_path` is the fastest start; follow up with `get_commit_diff` on
  the interesting hashes.
- `search_git_commit_messages` matches the message text; for changes to
  the code itself use Bash `git log -S` (literal count changes) or
  `-G` (regex over the diff).
- `analyze_git_hotspots` accepts a time window (`since_days` or ISO
  instants) plus `max_files` / `max_commits` bounds -- useful for "where
  is the risky code?" questions before a refactor.
- `gh search prs/issues` is broader than `gh pr list --search`; use it
  when you do not have the file path handy.
