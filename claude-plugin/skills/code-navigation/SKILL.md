---
name: brokk-code-navigation
description: >-
  Find symbol definitions and discover related files using Brokk's
  search_symbols, get_symbol_locations, and most_relevant_files tools.
---

# Code Navigation

Use these Brokk MCP tools when you need to find where things are defined
or which files in the project are related to a starting set.

## Tools

| Tool | Purpose |
|---|---|
| `search_symbols` | Find class, method, field, or module definitions by name (case-insensitive regex over fully-qualified names) |
| `get_symbol_locations` | Get file + line range for known symbol names |
| `most_relevant_files` | Rank project files most related to a set of seed files (uses git history co-change and import graph) |

## Tips

- `search_symbols` takes an array of patterns. Patterns are case-insensitive
  regexes matched against fully-qualified names, so a substring like
  `parseRequest` matches `com.example.http.ParseRequestHandler.parseRequest`.
- Pass `include_tests: true` to include matches from detected test files;
  the default excludes tests.
- `get_symbol_locations` accepts both fully-qualified and short symbol names
  and returns the first matching definition's file and line range. Use the
  optional `kind_filter` (`class`, `function`, `field`, `module`, or `any`)
  to disambiguate.
- Bifrost does not provide a caller-graph tool. To find call sites of a
  known symbol, use `get_symbol_locations` to confirm the definition, then
  fall back to the `Grep` tool (or Bash `grep -rn`) for the short name
  across the project.
- `most_relevant_files` is the fastest way to expand from a known file
  into adjacent code worth reading -- pass the file you are starting from
  as a `seed_files` entry and review the ranked results.
