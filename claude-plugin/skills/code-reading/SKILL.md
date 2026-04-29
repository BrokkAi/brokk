---
name: brokk-code-reading
description: >-
  Read implementation details and file structure using Brokk's
  get_symbol_sources, get_symbol_summaries, get_summaries, summarize_symbols,
  and skim_files tools.
---

# Code Reading

Use these Brokk MCP tools to read source code at the right level of detail.
For raw file contents (non-source files, configs, etc.) use the built-in
`Read` tool instead -- bifrost does not expose a file-contents tool.

## Tools

| Tool | Purpose |
|---|---|
| `get_symbol_sources` | Full source blocks for one or more named symbols (classes, functions, fields) |
| `get_symbol_summaries` | Ranged summaries (signatures + structure, no bodies) for named symbols |
| `get_summaries` | API surface for files, glob patterns, or class names |
| `summarize_symbols` | Compact recursive symbol summaries for matching files |
| `skim_files` | Quick declaration-level inventory for matching files |

## Tips

- Start with `get_summaries` (or `summarize_symbols` for a more compact
  output) when you need API shape, adjacent types, or package-level
  structure before reading concrete bodies.
- `get_symbol_sources` and `get_symbol_summaries` both accept an optional
  `kind_filter` (`class`, `function`, `field`, `module`, `any`) to
  disambiguate when a short name resolves in multiple kinds.
- Use `get_symbol_sources` when you only need a specific symbol's body --
  it is much cheaper than reading the whole file. The `kind_filter`
  replaces the old separate "method sources" vs. "class sources" tools.
- Use `skim_files` when you only need a very quick declaration-level file
  inventory across a glob.
- For raw file contents (build files, configs, READMEs, generated code),
  use the built-in `Read` tool, since bifrost only exposes structured
  symbol-aware accessors.
