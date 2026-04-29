---
name: brokk-codebase-search
description: >-
  File discovery and code search using Brokk's search_symbols, skim_files,
  and summarize_symbols tools, with a fallback to Grep for arbitrary text.
---

# Codebase Search

Use these Brokk MCP tools to find files and code in the workspace. Pick
the tool that matches what you are looking for:

- A symbol (class, method, function, field, module name) -> `search_symbols`
- Files by name or glob pattern -> `skim_files` or `summarize_symbols`
- Arbitrary text (string literals, comments, error messages, configs) ->
  the built-in `Grep` tool, since bifrost is symbol-aware, not text-aware

## Tools

| Tool | Purpose |
|---|---|
| `search_symbols` | Find class, method, field, or module definitions by name (case-insensitive regex over fully-qualified names) |
| `skim_files` | Quick declaration-level file inventory for matching glob patterns |
| `summarize_symbols` | Compact recursive symbol summary for matching glob patterns |

## Tips

- `search_symbols` is the right choice for "where is `parseRequest`
  defined?" or "what classes match `.*Service$`?". It returns matching
  files grouped by classes, functions, and fields. Pass
  `include_tests: true` to include test files (excluded by default).
- `skim_files` and `summarize_symbols` both take an array of
  `file_patterns` (project-relative paths or globs like
  `src/**/*.rs`). Use `skim_files` for a flat declaration listing and
  `summarize_symbols` when you also want nested structure. Either tool
  doubles as a "find files by glob" replacement for the older
  `findFilenames` / `listFiles` workflow.
- For text that is *not* a code symbol (a log message, a string literal,
  a YAML key, an error string), bifrost will not find it. Use the
  built-in `Grep` tool or Bash `grep -rn` instead.
- To find call sites of a known symbol, combine: `search_symbols` to
  confirm the symbol exists, `get_symbol_locations` (in the
  code-navigation skill) for the definition, and then `Grep` for the
  short name across the project.
