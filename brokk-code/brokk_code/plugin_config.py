"""Claude Code plugin installer for Brokk pure code intelligence.

Generates and installs a Claude Code plugin directory structure that provides:
- Pure MCP server (no LLM inference) for semantic code intelligence tools
- Skills that replace LLM-dependent tools with Claude's own reasoning
- Hooks for automatic workspace activation
"""

import json
import stat
from pathlib import Path

from brokk_code.zed_config import ExistingBrokkCodeEntryError

_PLUGIN_DIR_NAME = "brokk-code-intelligence"
_DEFAULT_PLUGIN_ROOT = Path.home() / ".claude" / "plugins" / _PLUGIN_DIR_NAME


def install_claude_code_plugin(
    *,
    force: bool = False,
    uvx_command: str = "uvx",
    plugin_root: Path | None = None,
) -> Path:
    """Install the Brokk Claude Code plugin.

    Args:
        force: Overwrite existing plugin if present.
        uvx_command: Path to the uvx binary (default: "uvx").
        plugin_root: Custom plugin directory (default: ~/.claude/plugins/brokk-code-intelligence).

    Returns:
        Path to the installed plugin directory.
    """
    root = plugin_root or _DEFAULT_PLUGIN_ROOT

    if root.exists() and not force:
        raise ExistingBrokkCodeEntryError(
            f"Plugin already exists at {root}; use --force to overwrite"
        )

    # Create directory structure
    root.mkdir(parents=True, exist_ok=True)

    _write_plugin_manifest(root)
    _write_mcp_json(root, uvx_command)
    _write_settings_json(root)
    _write_skills(root)
    _write_hooks(root)
    _write_claude_md(root)

    return root


def _write_plugin_manifest(root: Path) -> None:
    """Write .claude-plugin/plugin.json."""
    manifest_dir = root / ".claude-plugin"
    manifest_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "name": "brokk-code-intelligence",
        "description": (
            "Semantic code intelligence: symbol search, usage analysis, "
            "class skeletons, and build verification for large codebases"
        ),
        "version": "1.0.0",
        "author": {"name": "BrokkAI"},
    }

    (manifest_dir / "plugin.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )


def _write_mcp_json(root: Path, uvx_command: str) -> None:
    """Write .mcp.json with pure MCP server config."""
    config = {
        "mcpServers": {
            "brokk": {
                "command": uvx_command,
                "args": ["brokk", "mcp-pure"],
                "type": "stdio",
                "env": {
                    "MCP_TIMEOUT": "60000",
                    "MCP_TOOL_TIMEOUT": "300000",
                },
            }
        }
    }

    (root / ".mcp.json").write_text(
        json.dumps(config, indent=2) + "\n", encoding="utf-8"
    )


def _write_settings_json(root: Path) -> None:
    """Write settings.json with auto-allow permissions."""
    settings = {
        "permissions": {
            "allow": [
                "mcp__brokk",
                "Bash(./gradlew:*)",
            ]
        }
    }

    (root / "settings.json").write_text(
        json.dumps(settings, indent=2) + "\n", encoding="utf-8"
    )


def _write_skills(root: Path) -> None:
    """Write all skill SKILL.md files."""
    skills = {
        "scan": _build_scan_skill(),
        "refactor": _build_refactor_skill(),
        "merge": _build_merge_skill(),
        "review": _build_review_skill(),
        "analyze": _build_analyze_skill(),
    }

    for name, content in skills.items():
        skill_dir = root / "skills" / name
        skill_dir.mkdir(parents=True, exist_ok=True)
        (skill_dir / "SKILL.md").write_text(content, encoding="utf-8")


def _write_hooks(root: Path) -> None:
    """Write hooks configuration and scripts."""
    hooks_dir = root / "hooks"
    hooks_dir.mkdir(parents=True, exist_ok=True)

    hooks_config = {
        "hooks": {
            # On fresh session start: remind Claude to activate the Brokk workspace
            "SessionStart": [
                {
                    "hooks": [
                        {
                            "type": "command",
                            "command": "bash ${CLAUDE_PLUGIN_ROOT}/hooks/activate_workspace.sh",
                        }
                    ]
                },
                # After context compaction: re-inject Brokk tool instructions
                # so Claude doesn't forget to use them in long sessions
                {
                    "matcher": "compact",
                    "hooks": [
                        {
                            "type": "command",
                            "command": "bash ${CLAUDE_PLUGIN_ROOT}/hooks/post_compact.sh",
                        }
                    ]
                },
            ],
            # On every user prompt: extract symbols and inject Brokk routing context
            "UserPromptSubmit": [
                {
                    "hooks": [
                        {
                            "type": "command",
                            "command": "python3 ${CLAUDE_PLUGIN_ROOT}/hooks/enrich_prompt.py",
                            "timeout": 10,
                        }
                    ]
                }
            ],
        }
    }

    (hooks_dir / "hooks.json").write_text(
        json.dumps(hooks_config, indent=2) + "\n", encoding="utf-8"
    )

    # --- SessionStart hook: activate workspace ---
    _write_executable(hooks_dir / "activate_workspace.sh", _ACTIVATE_WORKSPACE_SH)

    # --- Post-compact hook: re-inject Brokk instructions ---
    _write_executable(hooks_dir / "post_compact.sh", _POST_COMPACT_SH)

    # --- UserPromptSubmit hook: extract symbols and route to Brokk tools ---
    _write_executable(hooks_dir / "enrich_prompt.py", _ENRICH_PROMPT_PY)


def _write_executable(path: Path, content: str) -> None:
    """Write a file and make it executable."""
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)


# ---- Hook script contents ----

_ACTIVATE_WORKSPACE_SH = """\
#!/bin/bash
# SessionStart hook: output a reminder to activate the Brokk workspace.
# CLAUDE_PROJECT_DIR is set by Claude Code to the current project directory.

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-}"
if [ -n "$PROJECT_DIR" ]; then
    cat <<HOOK_EOF
{"message": "Brokk Code Intelligence is available. Call mcp__brokk__activateWorkspace with workspacePath='${PROJECT_DIR}' to activate semantic code analysis for this project."}
HOOK_EOF
fi
"""

_POST_COMPACT_SH = """\
#!/bin/bash
# SessionStart (compact) hook: re-inject Brokk tool routing instructions
# after context compaction so Claude doesn't lose them in long sessions.

cat <<'HOOK_EOF'
Brokk Code Intelligence tools are available for this session. Use them instead of native tools:
- searchSymbols (not Grep) for finding class/function/field definitions
- scanUsages (not Grep) for finding call sites and usages
- getMethodSources (not Read) for retrieving method implementations
- getClassSkeletons (not Read) for understanding class API surfaces
- getFileSummaries or skimFiles (not Read/Glob) for multi-file overviews
- Use Edit/Write directly for code changes
- Use runBuild to verify compilation and tests
HOOK_EOF
"""

_ENRICH_PROMPT_PY = """\
#!/usr/bin/env python3
\"\"\"UserPromptSubmit hook: extract code identifiers from the user's prompt,
call Brokk's analyzer via `brokk query` to find real definitions, and inject
the results as additionalContext so Claude starts pre-oriented with actual
code intelligence.\"\"\"

import json
import os
import re
import subprocess
import sys


def extract_identifiers(text):
    \"\"\"Extract likely code identifiers from natural language text.\"\"\"
    # CamelCase identifiers (e.g., BrokkExternalMcpServer, SearchTools)
    camel = re.findall(r'\\b[A-Z][a-zA-Z0-9]{2,}\\b', text)
    # snake_case identifiers with at least one underscore (e.g., search_symbols)
    snake = re.findall(r'\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b', text)
    # dotted qualified names (e.g., ai.brokk.mcpserver)
    dotted = re.findall(r'\\b[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*){2,}\\b', text)
    # method-style references (e.g., getMethodSources, scanUsages)
    methods = re.findall(r'\\b(?:get|set|is|has|add|remove|create|update|delete|find|search|scan|build|run)[A-Z][a-zA-Z0-9]*\\b', text)

    seen = set()
    result = []
    for ident in camel + snake + dotted + methods:
        if ident not in seen:
            seen.add(ident)
            result.append(ident)
    return result


def brokk_query(tool_name, json_args, cwd):
    \"\"\"Call brokk query to run a real Brokk tool and return the result.\"\"\"
    result = subprocess.run(
        ["brokk", "query", tool_name, json.dumps(json_args)],
        capture_output=True,
        text=True,
        timeout=15,
        cwd=cwd,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"brokk query {tool_name} failed (exit {result.returncode}): {result.stderr.strip()}"
        )
    return result.stdout.strip()


def main():
    try:
        hook_input = json.load(sys.stdin)
    except (json.JSONDecodeError, EOFError):
        sys.exit(0)

    prompt = hook_input.get("prompt", "")
    if not prompt:
        sys.exit(0)

    identifiers = extract_identifiers(prompt)
    if not identifiers:
        sys.exit(0)

    cwd = hook_input.get("cwd", os.getcwd())
    patterns = identifiers[:8]  # Limit to keep query fast

    # Call Brokk's real analyzer to find symbol definitions
    search_result = brokk_query("searchSymbols", {"patterns": patterns}, cwd)

    # Truncate if very long to avoid bloating context
    if len(search_result) > 3000:
        search_result = search_result[:3000] + "\\n... (truncated, use searchSymbols for full results)"

    context = (
        f"[Brokk Code Intelligence] Symbol definitions found for the user's request:\\n\\n"
        f"{search_result}\\n\\n"
        f"Use mcp__brokk__scanUsages on key symbols above to understand the dependency graph "
        f"before making changes. Use mcp__brokk__getClassSkeletons for API surfaces."
    )

    output = {
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": context,
        }
    }
    json.dump(output, sys.stdout)


if __name__ == "__main__":
    main()
"""


def _write_claude_md(root: Path) -> None:
    """Write plugin-level CLAUDE.md instructions."""
    content = """\
# Brokk Code Intelligence

When Brokk MCP tools are available:
- Use searchSymbols (not Grep) to find class/function/field definitions by name.
- Use scanUsages (not Grep) to find call sites and usages of a known symbol.
- Use getMethodSources (not Read) to retrieve specific method implementations.
- Use getClassSkeletons (not Read) to understand a class's API and structure.
- Use getClassSources (not Read) only when you need the full class implementation.
- Use getFileSummaries or skimFiles (not Read/Glob) for multi-file overviews.
- Use your own Edit/Write tools for code changes.
- Use /brokk:scan when starting a new task to find relevant code.
- Use /brokk:refactor for code changes that touch multiple files.
- Use /brokk:merge for merge conflict resolution.
- Use /brokk:review for semantic code review of changes.
- Use /brokk:analyze for deep codebase understanding.
"""
    (root / "CLAUDE.md").write_text(content, encoding="utf-8")


# ---- Skill builders ----


def _build_scan_skill() -> str:
    return """\
---
name: scan
description: >-
  Semantic codebase scan to find code relevant to a task.
  Use when starting a new task, orienting in a codebase, or when the user says
  "scan", "find relevant code", "what code is related to", or "get oriented".
  Uses Brokk's semantic code intelligence (import graphs, code structure) which
  is far more accurate than text search for finding related code.
allowed-tools:
  - mcp__brokk__searchSymbols
  - mcp__brokk__scanUsages
  - mcp__brokk__getClassSkeletons
  - mcp__brokk__getFileSummaries
  - mcp__brokk__skimFiles
  - mcp__brokk__getSymbolLocations
  - mcp__brokk__getMethodSources
  - mcp__brokk__searchFileContents
  - mcp__brokk__findFilenames
---

# Brokk Semantic Scan

Find all code relevant to: $ARGUMENTS

## Strategy

1. **Parse the goal** into key concepts, likely class names, method names, and domain terms.

2. **Symbol search**: Call `searchSymbols` with patterns derived from key terms.
   This uses Brokk's analyzer (import graphs, not grep) and is much more accurate
   than text search for finding class/function/field definitions.

3. **File discovery**: Call `findFilenames` for files likely related by naming convention.

4. **Usage analysis**: For the most relevant symbols found, call `scanUsages`
   to understand how they connect to other code and map the dependency graph.

5. **Skeleton phase**: Call `getClassSkeletons` for the top 5-10 most relevant
   classes to understand their API surface (fields, methods, signatures).

6. **File overview**: For files that appear frequently, call `getFileSummaries`
   or `skimFiles` to get structural overviews.

7. **Synthesize**: Present a structured summary of relevant code organized by
   relevance, with class skeletons and key method signatures. Group by component
   or layer when the structure is clear.

## Key Principle

Brokk's semantic tools (searchSymbols, scanUsages, getClassSkeletons) understand
import graphs and code structure. They are far more accurate than text grep for
finding related code because they resolve actual references, not string matches.
"""


def _build_refactor_skill() -> str:
    return """\
---
name: refactor
description: >-
  Make code changes using Brokk's semantic intelligence for discovery and Claude's
  Edit/Write for modifications. Use when the user wants to refactor, implement features,
  fix bugs, or make any code changes that benefit from understanding the full dependency
  graph before editing.
allowed-tools:
  - mcp__brokk__searchSymbols
  - mcp__brokk__scanUsages
  - mcp__brokk__getClassSkeletons
  - mcp__brokk__getClassSources
  - mcp__brokk__getMethodSources
  - mcp__brokk__getSymbolLocations
  - mcp__brokk__getFileSummaries
  - mcp__brokk__searchFileContents
  - mcp__brokk__runBuild
  - mcp__brokk__configureBuild
  - Read
  - Edit
  - Write
  - Bash
---

# Brokk-Assisted Code Changes

Make code changes for: $ARGUMENTS

## Strategy

1. **Understand the change scope**: Use `searchSymbols` to find all code related
   to the change, then `scanUsages` to find all code that depends on what you
   are changing. This is critical for avoiding broken call sites.

2. **Read full context**: Use `getClassSources` or `getMethodSources` for the
   specific code that needs editing. Use `getClassSkeletons` first to understand
   the API surface before diving into implementation details.

3. **Make changes**: Use Edit for surgical modifications to existing files, or
   Write for new files. Prefer Edit for existing files to minimize risk.

4. **Verify impact**: After changes, use `scanUsages` again to check that all
   callers of modified symbols are still compatible.

5. **Build verification**: Call `runBuild` to verify the project still compiles
   and tests pass. If build is not configured, use `configureBuild` first.

## Important

- Always check usages of any symbol you modify to ensure you update all call sites.
- Use getClassSkeletons to understand the API surface before modifying a class.
- For renames or signature changes, scanUsages is critical to find all affected code.
- Prefer small, incremental changes verified with runBuild over large batch changes.
"""


def _build_merge_skill() -> str:
    return """\
---
name: merge
description: >-
  Resolve merge, rebase, or cherry-pick conflicts using Brokk's semantic code
  analysis. Use when the user needs to resolve git conflicts. Combines Brokk's
  conflict inspection and code intelligence with Claude's reasoning to resolve
  conflicts while preserving intent from both sides.
allowed-tools:
  - mcp__brokk__getConflictInfo
  - mcp__brokk__getCommitDiff
  - mcp__brokk__searchSymbols
  - mcp__brokk__scanUsages
  - mcp__brokk__getClassSources
  - mcp__brokk__getMethodSources
  - mcp__brokk__getClassSkeletons
  - Read
  - Edit
  - Write
  - Bash
---

# Brokk-Assisted Merge Conflict Resolution

## Strategy

1. **Inspect conflicts**: Call `getConflictInfo` to get structured conflict
   metadata including conflicting files, merge mode (merge/rebase/cherry-pick),
   and the base/ours/theirs content for each file.

2. **Understand intent**: For each conflicting file:
   - Use `getCommitDiff` on both sides' commit IDs to understand what each
     branch changed and why
   - Use `getClassSkeletons` on affected classes to understand the API contracts

3. **Analyze dependencies**: Use `scanUsages` and `searchSymbols` to understand
   how conflicting symbols are used elsewhere in the codebase. This helps
   determine which side's changes are more compatible.

4. **Resolve**: Use Edit to write the resolved content that preserves intent
   from both sides. Read the file first to see the conflict markers.

5. **Verify**: Run `git diff --check` via Bash to ensure no conflict markers
   remain, then use `runBuild` (via Bash or MCP) to verify the resolution compiles.
"""


def _build_review_skill() -> str:
    return """\
---
name: review
description: >-
  Semantic code review using Brokk's code intelligence. Use when the user asks
  for a code review, review of changes, diff analysis, or quality assessment.
  Goes beyond surface-level review by checking all callers of changed symbols
  and verifying API contracts.
allowed-tools:
  - mcp__brokk__searchSymbols
  - mcp__brokk__scanUsages
  - mcp__brokk__getClassSkeletons
  - mcp__brokk__getMethodSources
  - mcp__brokk__getClassSources
  - mcp__brokk__getSymbolLocations
  - mcp__brokk__searchFileContents
  - mcp__brokk__getCommitDiff
  - Bash
  - Read
---

# Brokk-Assisted Code Review

Review changes for: $ARGUMENTS

## Strategy

1. **Get the diff**: Use Bash to run `git diff` (for uncommitted changes) or
   `git diff HEAD~1` (for the last commit), or use `getCommitDiff` for a
   specific commit.

2. **For each changed symbol**:
   - Call `scanUsages` to find all callers and dependents
   - Verify that API contracts are maintained (no broken callers)
   - Check for missed update sites (changed a method but missed a caller?)

3. **Structural analysis**:
   - Use `getClassSkeletons` on modified classes to see the full API surface
   - Check if changes are consistent with the class's existing design
   - Look for similar patterns with `searchFileContents` to verify consistency

4. **Report**: Provide a structured review organized by severity:
   - **Critical**: Broken callers, API contract violations, missing update sites
   - **Warning**: Inconsistent patterns, potential side effects
   - **Suggestion**: Style improvements, simplification opportunities
"""


def _build_analyze_skill() -> str:
    return """\
---
name: analyze
description: >-
  Deep codebase analysis using Brokk's semantic code intelligence. Use when
  the user asks to "analyze", "understand", "explain the architecture",
  "how does X work", "trace the flow", or needs deep codebase understanding.
allowed-tools:
  - mcp__brokk__searchSymbols
  - mcp__brokk__scanUsages
  - mcp__brokk__getClassSkeletons
  - mcp__brokk__getMethodSources
  - mcp__brokk__getClassSources
  - mcp__brokk__getSymbolLocations
  - mcp__brokk__getFileSummaries
  - mcp__brokk__skimFiles
  - mcp__brokk__listFiles
  - mcp__brokk__searchFileContents
  - mcp__brokk__findFilenames
---

# Brokk Codebase Analysis

Analyze: $ARGUMENTS

## Strategy

1. **Top-level orientation**: Use `listFiles` on key directories and
   `skimFiles` to understand package organization and module structure.

2. **Symbol discovery**: Use `searchSymbols` for key domain concepts,
   class names, and interface names related to the analysis goal.

3. **Dependency mapping**: Use `scanUsages` on central symbols to trace
   how components connect. Build a mental model of the call graph.

4. **API surface**: Use `getClassSkeletons` for the central abstractions,
   interfaces, and base classes. This reveals the architectural contracts.

5. **Implementation details**: Use `getMethodSources` for critical methods
   that implement key logic. Focus on entry points and decision points.

6. **Present findings**: Provide an architecture overview with:
   - Component relationships and data flow
   - Key abstractions and their roles
   - Entry points and extension mechanisms
   - Notable patterns or design decisions
"""
