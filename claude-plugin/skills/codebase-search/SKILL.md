---
name: brokk-codebase-search
description: >-
  Grep-like text search and file discovery using Brokk's searchFileContents,
  findFilesContaining, findFilenames, and listFiles tools.
---

# Codebase Search

Use these Brokk MCP tools for text-based search and file discovery.

## Tools

| Tool | Purpose |
|---|---|
| `searchFileContents` | Regex search across file contents (with context lines) |
| `findFilesContaining` | Find files whose contents match a pattern |
| `findFilenames` | Find files by name/glob pattern |
| `listFiles` | List directory contents |

## Tips

- `searchFileContents` supports regex and optional context lines --
  use it like grep.
- `findFilesContaining` returns only file paths (no content) -- use it
  when you just need to know which files match.
- `findFilenames` accepts glob patterns for matching file names.
- `listFiles` is useful for exploring directory structure when you are
  not sure what exists.
