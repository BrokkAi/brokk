---
name: brokk-git-exploration
description: >-
  Explore change history using the Bash tool with git and gh, since
  bifrost does not expose git-history MCP tools.
---

# Git Exploration

Bifrost is a code-analyzer MCP server and does not expose git-history
tools. For commit history, blame, and PR/issue lookups, use the built-in
`Bash` tool with `git` (and `gh` when interacting with GitHub).

## Common idioms

| Goal | Command |
|---|---|
| Recent commits in a file | `git log --oneline -n 20 -- path/to/file` |
| Recent commits across the repo | `git log --oneline -n 50` |
| Search commit messages | `git log --grep='pattern' --oneline` |
| Search commit *content* (added/removed lines) | `git log -S 'literal' --oneline` or `git log -G 'regex' --oneline` |
| Who last touched each line | `git blame path/to/file` |
| Diff between two commits | `git diff <a>..<b> -- path/to/file` |
| Find when a symbol was introduced | `git log --diff-filter=A -S 'symbol_name' -- path/` |
| List PRs that touched a file | `gh pr list --state all --search 'path/to/file in:title,body'` |
| Show a PR | `gh pr view <number>` |
| Show an issue | `gh issue view <number>` |

## Tips

- For "what changed recently in this area?", `git log --oneline -n 20 -- <path>`
  is faster than reading individual commits.
- `git log -S 'string'` finds commits where the *count* of a literal
  changed; `-G 'regex'` matches the actual diff. Use `-S` for adding/
  removing a known identifier, `-G` for richer patterns.
- `gh search prs/issues` is broader than `gh pr list --search`; use it
  when you do not have the file path handy.
- For ongoing work, `git log @{u}..HEAD` shows commits on the current
  branch that have not been pushed yet.
