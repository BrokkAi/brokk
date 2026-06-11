---
name: brokk-code-reading
description: >-
  Read implementation details and file structure using Brokk's
  get_symbol_sources, get_summaries, list_symbols, and get_file_contents
  tools.
---

# Code Reading

Use these Brokk MCP tools to read source code at the right level of detail.

## Tools

| Tool | Purpose |
|---|---|
| `get_symbol_sources` | Full source blocks for one or more named symbols (classes, functions, fields) |
| `get_summaries` | API surface for files, glob patterns, or class names (signatures + structure, no bodies) |
| `list_symbols` | Compact recursive symbol outline for matching files |
| `get_file_contents` | Raw text contents of one or more files |
| `get_symbol_ancestors` | Ancestor class chain for known classes, when inheritance context matters |

## Tips

- Start with `get_summaries` (or `list_symbols` for a more compact
  output) when you need API shape, adjacent types, or package-level
  structure before reading concrete bodies. `get_summaries` accepts glob
  targets like `src/auth/**/*.rs` as well as class names.
- `get_symbol_sources` accepts an optional `kind_filter` (`class`,
  `function`, `field`, `module`, `any`) to disambiguate when a short name
  resolves in multiple kinds.
- Use `get_symbol_sources` when you only need a specific symbol's body --
  it is much cheaper than reading the whole file. The `kind_filter`
  replaces the old separate "method sources" vs. "class sources" tools.
- Use `list_symbols` when you need a quick declaration-level file outline
  across a glob.
- For raw file contents (build files, configs, READMEs, generated code),
  use `get_file_contents`; it takes project-relative or absolute paths
  inside the active workspace.
