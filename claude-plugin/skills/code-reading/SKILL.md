---
name: brokk-code-reading
description: >-
  Read implementation details and file structure using Brokk's getClassSources,
  getMethodSources, getFileContents, skimFiles, and getSummaries tools.
---

# Code Reading

Use these Brokk MCP tools to read source code at the right level of detail.

## Tools

| Tool | Purpose |
|---|---|
| `getClassSources` | Full source of one or more classes |
| `getMethodSources` | Source of specific methods (by FQN) |
| `getFileContents` | Raw file contents (any file type) |
| `skimFiles` | Quick structural overview of files |
| `getSummaries` | API surface and neighboring structure for classes, files, or packages |

## Tips

- Start with `getSummaries` when you need API shape, adjacent types, or
  package-level structure before diving into implementations.
- Use `getSummaries` with glob patterns to survey a whole package, then
  switch to `getMethodSources` or `getFileContents` for concrete logic.
- Use `skimFiles` when you only need a very quick declaration-level file
  inventory.
- Use `getMethodSources` when you only need a specific method -- it is
  much cheaper than `getClassSources`.
- Only fall back to `getClassSources` when you need the complete
  implementation.
