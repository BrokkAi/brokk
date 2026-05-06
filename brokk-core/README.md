# Brokk Core MCP Server

Standalone MCP server providing code intelligence tools via tree-sitter analysis. No LLM dependencies -- pure structural analysis of source code.

This is the MCP server used by the [Brokk Claude Code plugin](../claude-plugin/README.md). Tool definitions are the source of truth in [`BrokkCoreMcpServer.toolSpecifications()`](src/main/java/ai/brokk/mcpserver/BrokkCoreMcpServer.java).

## Quick start

The server is launched via `uvx brokk mcp-core`. The Claude Code plugin configures this automatically in `.mcp.json`:

```json
{
  "mcpServers": {
    "brokk": {
      "command": "uvx",
      "args": ["brokk", "mcp-core"],
      "startup_timeout_sec": 60,
      "tool_timeout_sec": 300
    }
  }
}
```

**Prerequisite**: [uv](https://docs.astral.sh/uv/) must be installed. The `uvx` command fetches the `brokk` package from PyPI automatically.

## Tool reference

All tools require an active workspace. Call `activateWorkspace` first with an absolute path to the project directory -- the server normalizes to the nearest git root.

### Workspace management

| Tool | Description | Parameters |
|------|-------------|------------|
| `activateWorkspace` | Set the active workspace. Normalizes to the nearest git root. | `workspacePath` (string, required) -- absolute path to the workspace directory |
| `getActiveWorkspace` | Return the currently active workspace root. | (none) |

### Symbol navigation

| Tool | Description | Parameters |
|------|-------------|------------|
| `searchSymbols` | Find where classes, functions, fields, and modules are defined. Patterns are case-insensitive regex with implicit `^` and `$`, so use wildcarding: `.*Foo.*`, `Abstract.*`, `[a-z]*DAO`. | `patterns` (string[], required), `includeTests` (bool, required), `limit` (int, required; default 50, max 100) |
| `scanUsages` | Find where/how a symbol is used/called/accessed across the codebase. Requires fully qualified names -- call `searchSymbols` first if you only have a partial name. | `symbols` (string[], required), `includeTests` (bool, required) |
| `getSymbolLocations` | Returns file locations for given fully qualified symbol names. | `symbols` (string[], required) |

### Code reading

| Tool | Description                                                                                                                                                                                                                          | Parameters |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| `getSummaries` | Per-file and per-class summaries: class skeletons for source files, structured template summaries for framework DSLs (e.g. Angular `.component.html`). Supports glob patterns (`*` matches one directory, `**` matches recursively). | `filePaths` (string[], required) -- paths relative to project root |
| `getClassSources` | Full source code of classes (max 10). Prefer `getSummaries` or `getMethodSources` when possible.                                                                                                                                      | `classNames` (string[], required) -- fully qualified class names |
| `getMethodSources` | Full source code of specific methods/functions by fully qualified name. Preferred over `getClassSources` when you only need 1-2 methods.                                                                                             | `methodNames` (string[], required) -- fully qualified method names |
| `getFileContents` | Read the full contents of one or more files.                                                                                                                                                                                         | `filenames` (string[], required) -- relative file paths |
| `skimFiles` | Quick overview of files showing top-level declarations without full source.                                                                                                                                                          | `filePaths` (string[], required) |

### File discovery and search

| Tool | Description | Parameters |
|------|-------------|------------|
| `findFilenames` | Search for files by name pattern. Patterns are case-insensitive and match anywhere in the filename. | `patterns` (string[], required), `limit` (int, required; default 50) |
| `findFilesContaining` | Find files containing text matching the given regex patterns. | `patterns` (string[], required), `limit` (int, required; default 50) |
| `searchFileContents` | Search for regex patterns in file contents with context lines. | `patterns` (string[], required), `filepath` (string, required -- path or glob), `caseInsensitive` (bool, optional), `multiline` (bool, optional), `contextLines` (int, optional; default 2), `maxFiles` (int, optional; default 20) |
| `listFiles` | List files in a directory, showing the tree structure. | `directoryPath` (string, required) -- relative to project root |

### Git

| Tool | Description | Parameters |
|------|-------------|------------|
| `searchGitCommitMessages` | Search git commit messages for a pattern. | `pattern` (string, required) -- regex, `limit` (int, required; default 20) |
| `getGitLog` | Get git log for a file or directory path. | `path` (string, required), `limit` (int, required; default 20) |

### Structured data

| Tool | Description | Parameters |
|------|-------------|------------|
| `jq` | Query JSON files using jq filter expressions. | `filepath` (string, required) -- path or glob, `filter` (string, required) -- jq expression, `maxFiles` (int, required; default 10), `matchesPerFile` (int, required; default 50) |
| `xmlSkim` | Get a structural overview of XML/HTML files showing element hierarchy. | `filepath` (string, required) -- path or glob, `maxFiles` (int, required; default 10) |
| `xmlSelect` | Query XML/HTML files using XPath expressions. | `filepath` (string, required), `xpath` (string, required), `output` (string, optional; `"text"` or `"xml"`, default `"text"`), `attrName` (string, optional), `maxFiles` (int, optional; default 10), `matchesPerFile` (int, optional; default 50) |

### Code quality analysis

These tools perform static analysis using tree-sitter-parsed ASTs. They operate on file paths relative to the project root.

#### `computeCyclomaticComplexity`

Computes heuristic cyclomatic complexity for methods in the given files. Flags methods above the threshold for review or refactor. Returns a markdown report of flagged methods.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePaths` | string[] | yes | -- | File paths relative to project root |
| `threshold` | int | no | 10 | Complexity threshold; methods above this are flagged |

#### `reportCommentDensityForCodeUnit`

Comment density for one symbol identified by fully qualified name. Reports header vs inline comment line counts, declaration span lines, and rolled-up totals for class-like units. Currently Java only.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqName` | string | yes | -- | Fully qualified name (e.g. `com.example.MyClass` or `com.example.MyClass.method`) |
| `maxLines` | int | no | 120 | Maximum output lines |

#### `reportCommentDensityForFiles`

Comment density tables for the given source files: one section per file and one row per top-level declaration. Includes own and rolled-up header/inline line counts. Currently Java only.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePaths` | string[] | yes | -- | File paths relative to project root |
| `maxTopLevelRows` | int | no | 60 | Maximum declaration rows across all files |
| `maxFiles` | int | no | 25 | Maximum files to include |

#### `reportExceptionHandlingSmells`

Detects suspicious exception handlers using weighted heuristics designed for high-recall triage. Scores generic catches and tiny/empty handlers, then subtracts credit for richer handling bodies.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePaths` | string[] | yes | -- | File paths relative to project root |
| `minScore` | int | no | 4 | Minimum score to include a finding |
| `maxFindings` | int | no | 80 | Maximum findings to emit |
| `genericThrowableWeight` | int | no | (internal default) | Weight for catching `Throwable` |
| `genericExceptionWeight` | int | no | (internal default) | Weight for catching `Exception` |
| `genericRuntimeExceptionWeight` | int | no | (internal default) | Weight for catching `RuntimeException` |
| `emptyBodyWeight` | int | no | (internal default) | Weight for empty catch bodies |
| `commentOnlyBodyWeight` | int | no | (internal default) | Weight for comment-only catch bodies |
| `smallBodyWeight` | int | no | (internal default) | Weight for small catch bodies |
| `logOnlyBodyWeight` | int | no | (internal default) | Weight for log-only catch bodies |
| `meaningfulBodyCreditPerStatement` | int | no | (internal default) | Score credit subtracted per statement in catch body |
| `meaningfulBodyStatementThreshold` | int | no | (internal default) | Maximum statements that earn meaningful-body credit |
| `smallBodyMaxStatements` | int | no | (internal default) | Maximum statement count considered a small body |

#### `reportStructuralCloneSmells`

Detects duplicated implementation patterns across functions using normalized token similarity with AST refinement for high-recall triage.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePaths` | string[] | yes | -- | File paths relative to project root |
| `minScore` | int | no | 60 | Minimum similarity score (0-100) |
| `minNormalizedTokens` | int | no | 12 | Minimum normalized token count |
| `shingleSize` | int | no | 2 | Shingle size used for token overlap |
| `minSharedShingles` | int | no | 3 | Minimum shared shingles before scoring |
| `astSimilarityPercent` | int | no | 70 | AST refinement threshold (0-100) |
| `maxFindings` | int | no | 80 | Maximum findings to emit |

## Note on MCP servers

The Brokk codebase has two MCP servers:

1. **`BrokkCoreMcpServer`** (this module, `brokk-core/`) -- Standalone, no LLM dependencies, pure tree-sitter analysis. This is what the Claude Code plugin connects to. Exposes 25 tools.
2. **`BrokkExternalMcpServer`** (in `app/`) -- Full server with LLM agent capabilities. Not used by the Claude Code plugin.
