import json
import tempfile
from pathlib import Path

from brokk_code import __version__
from brokk_code.zed_config import ExistingBrokkCodeEntryError

_PLUGIN_DIR_NAME = "brokk"
_PLUGIN_META_DIR = ".claude-plugin"


def _plugin_manifest() -> dict:
    return {
        "name": "brokk",
        "description": (
            "Semantic code intelligence -- symbol navigation, "
            "cross-reference analysis, and structural code understanding "
            "powered by tree-sitter"
        ),
        "version": __version__,
        "author": "Brokk AI",
        "license": "Apache-2.0",
        "homepage": "https://github.com/BrokkAI/brokk",
        "keywords": [
            "code-intelligence",
            "tree-sitter",
            "code-navigation",
            "semantic-search",
        ],
    }


def _mcp_config(uvx_command: str) -> dict:
    return {
        "mcpServers": {
            "brokk": {
                "command": uvx_command,
                "args": ["brokk", "mcp-core"],
            }
        }
    }


# ---------------------------------------------------------------------------
# Skill content
# ---------------------------------------------------------------------------

_SKILL_CODE_NAVIGATION = """\
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
"""

_SKILL_CODE_READING = """\
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
"""

_SKILL_CODEBASE_SEARCH = """\
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
"""

_SKILL_GIT_EXPLORATION = """\
---
name: brokk-git-exploration
description: >-
  Explore change history using Brokk's searchGitCommitMessages and
  getGitLog tools.
---

# Git Exploration

Use these Brokk MCP tools to understand change history.

## Tools

| Tool | Purpose |
|---|---|
| `searchGitCommitMessages` | Search commit messages by pattern |
| `getGitLog` | View recent commit history |

## Tips

- Use `searchGitCommitMessages` to find when a feature was added or a
  bug was fixed.
- Use `getGitLog` to see recent changes and understand the pace of
  development in a file or directory.
"""

_SKILL_STRUCTURED_DATA = """\
---
name: brokk-structured-data
description: >-
  Query JSON and XML/HTML data using Brokk's jq, xmlSkim, and xmlSelect
  tools.
---

# Structured Data

Use these Brokk MCP tools to query JSON configuration files and
XML/HTML documents.

## Tools

| Tool | Purpose |
|---|---|
| `jq` | Query JSON data with jq expressions |
| `xmlSkim` | Get a structural overview of an XML/HTML document |
| `xmlSelect` | Run XPath queries against XML/HTML |

## Tips

- Use `jq` for JSON config files (package.json, tsconfig.json, etc.).
- Use `xmlSkim` first to understand document structure, then `xmlSelect`
  with XPath for targeted extraction.
"""

_SKILL_WORKSPACE = """\
---
name: brokk-workspace
description: >-
  Set or query the active workspace using Brokk's activateWorkspace and
  getActiveWorkspace tools. Required before code intelligence works on a
  new project or when switching repositories.
---

# Workspace

Use these Brokk MCP tools to set or check which project the server is
analyzing. The server will not return useful results for code intelligence
tools until a workspace is activated.

## Tools

| Tool | Purpose |
|---|---|
| `activateWorkspace` | Set the active workspace directory (absolute path; normalizes to git root) |
| `getActiveWorkspace` | Return the current workspace root path |

## Parameters

### activateWorkspace

| Parameter | Type | Required | Description |
|---|---|---|---|
| `workspacePath` | string | yes | Absolute path to the desired workspace directory |

### getActiveWorkspace

No parameters.

## Tips

- Always call `activateWorkspace` before using any other Brokk tools when
  starting work on a new project or switching repositories.
- The server automatically resolves the given path upward to the nearest
  `.git` root, so you can pass a subdirectory path.
- Use `getActiveWorkspace` to confirm which project root is currently active.
"""

_SKILLS: dict[str, str] = {
    "workspace": _SKILL_WORKSPACE,
    "code-navigation": _SKILL_CODE_NAVIGATION,
    "code-reading": _SKILL_CODE_READING,
    "codebase-search": _SKILL_CODEBASE_SEARCH,
    "git-exploration": _SKILL_GIT_EXPLORATION,
    "structured-data": _SKILL_STRUCTURED_DATA,
}


# ---------------------------------------------------------------------------
# File writing helpers
# ---------------------------------------------------------------------------


def _atomic_write_json(path: Path, data: dict) -> None:
    text = json.dumps(data, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as tmp:
        tmp.write(text)
        tmp_path = Path(tmp.name)

    if path.exists():
        tmp_path.chmod(path.stat().st_mode)

    tmp_path.replace(path)


def _write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def install_plugin(
    *,
    plugin_path: Path | None = None,
    uvx_command: str = "uvx",
    force: bool = False,
) -> tuple[Path, bool]:
    """Create the Claude Code plugin directory structure at ~/.claude/plugins/brokk/.

    Returns a tuple of (plugin root directory, whether this was a reinstall).
    Raises ExistingBrokkCodeEntryError if the plugin already exists and force is False.
    """
    root = plugin_path or (Path.home() / ".claude" / "plugins" / _PLUGIN_DIR_NAME)
    is_reinstall = root.exists()
    if is_reinstall and not force:
        raise ExistingBrokkCodeEntryError(f"{root} already exists; use --force to overwrite it")
    root.mkdir(parents=True, exist_ok=True)

    # Plugin manifest
    meta_dir = root / _PLUGIN_META_DIR
    meta_dir.mkdir(parents=True, exist_ok=True)
    _atomic_write_json(meta_dir / "plugin.json", _plugin_manifest())

    # MCP server config
    _atomic_write_json(root / ".mcp.json", _mcp_config(uvx_command))

    # Skills
    for skill_name, skill_content in _SKILLS.items():
        skill_dir = root / skill_name
        skill_dir.mkdir(parents=True, exist_ok=True)
        _write_text(skill_dir / "SKILL.md", skill_content)

    return root, is_reinstall
