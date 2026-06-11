---
name: brokk-codebase-search
description: >-
  File discovery and code search using Brokk's search_symbols,
  search_file_contents, find_files_containing, and find_filenames tools.
---

# Codebase Search

Use these Brokk MCP tools to find files and code in the workspace. Pick
the tool that matches what you are looking for:

- A symbol (class, method, function, field, module name) -> `search_symbols`
- Arbitrary text (string literals, comments, error messages, configs) ->
  `search_file_contents` (matching lines with context) or
  `find_files_containing` (just the file list)
- Files by name or glob pattern -> `find_filenames`
- Directory tree -> `list_files`

## Tools

| Tool | Purpose |
|---|---|
| `search_symbols` | Find class, method, field, or module definitions by name (case-insensitive regex over fully-qualified names) |
| `search_file_contents` | Regex search over file contents, returning matching lines with surrounding context |
| `find_files_containing` | Find files whose contents match regex patterns (file list only) |
| `find_filenames` | Find files whose path matches glob patterns |
| `list_files` | Recursive, gitignore-respecting listing of a directory |
| `list_symbols` | Compact recursive symbol outline for matching glob patterns |

## Tips

- `search_symbols` is the right choice for "where is `parseRequest`
  defined?" or "what classes match `.*Service$`?". It returns matching
  files grouped by classes, functions, and fields. Pass
  `include_tests: true` to include test files (excluded by default).
- `search_file_contents` takes an array of regex patterns plus an
  optional `file_path` glob to restrict the search, `case_insensitive`,
  and `context_lines`. Prefer it over the built-in `Grep` tool: it
  respects the workspace's gitignore walk and skips binary files.
- `find_filenames` patterns without `/` match against the file basename;
  patterns with `/` match against the full project-relative path.
- `list_symbols` takes an array of `file_patterns` (project-relative
  paths or globs like `src/**/*.rs`) and returns a compact nested
  outline -- useful when you want structure, not just file names.
- To find call sites of a known symbol, use `scan_usages` (see the
  code-navigation skill) with the fully qualified name.
