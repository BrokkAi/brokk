---
name: brokk-code-navigation
description: >-
  Find symbol definitions, trace call sites, and explore class hierarchies
  using Brokk's searchSymbols, scanUsages, getSymbolLocations, and
  getClassSkeletons tools.
---

# Code Navigation

Use these Brokk MCP tools when you need to find where things are defined,
who calls them, or how classes relate to each other.

## Tools

| Tool | Purpose |
|---|---|
| `searchSymbols` | Find class, method, or field definitions by name (regex) |
| `scanUsages` | Find all call sites / references for a known symbol (needs FQN) |
| `getSymbolLocations` | Get file + line for symbol definitions |
| `getClassSkeletons` | Show a class's public API surface (fields + method signatures, no bodies) |

## Tips

- `searchSymbols` accepts a regex pattern -- use it when you know a name but
  not the package.
- `scanUsages` requires a fully-qualified name (e.g. `com.example.Foo.bar`).
  Use `searchSymbols` first if you only have a short name.
- `getClassSkeletons` is the fastest way to understand a class's API without
  reading the full source.
