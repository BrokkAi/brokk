---
name: brokk-code-reading
description: >-
  Read implementation details and file structure using Brokk's getClassSources,
  getMethodSources, getFileContents, skimFiles, and getFileSummaries tools.
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
| `getFileSummaries` | Class skeletons (fields + signatures) for packages/directories |

## Tips

- Start with `skimFiles` or `getFileSummaries` for an overview before
  diving into full source.
- Use `getFileSummaries` with glob patterns to survey a whole package.
- Use `getMethodSources` when you only need a specific method -- it is
  much cheaper than `getClassSources`.
- Only fall back to `getClassSources` when you need the complete
  implementation.
