import json
import logging
import os
import re
import tempfile
import tomllib
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from brokk_code.zed_config import (
    ExistingBrokkCodeEntryError,
    atomic_write_settings,
    loads_json_or_jsonc,
)

_IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_SERVER_NAME = "brokk"
_BROKK_MARKER = "# Brokk"
_BROKK_BEGIN_MANAGED = "<!-- BROKK:BEGIN MANAGED SECTION -->"
_BROKK_END_MANAGED = "<!-- BROKK:END MANAGED SECTION -->"
_BROKK_MARKER_RE = re.compile(f"^{_BROKK_MARKER}$", re.MULTILINE)
_BROKK_MANAGED_RE = re.compile(
    f"{re.escape(_BROKK_BEGIN_MANAGED)}.*?{re.escape(_BROKK_END_MANAGED)}", re.DOTALL
)
_BROKK_CODEX_WORKSPACE_SKILL_NAME = "brokk-mcp-workspace"
_BROKK_CODEX_SUMMARIES_SKILL_NAME = "brokk-get-file-summaries"
_BROKK_CLAUDE_WORKSPACE_SKILL_NAME = "brokk-mcp-workspace"
_BROKK_CLAUDE_SUMMARIES_SKILL_NAME = "brokk-get-file-summaries"
_BROKK_CODEX_PLUGIN_NAME = "brokk"
_BROKK_CODEX_PLUGIN_MARKETPLACE_NAME = "brokk-local"
_BROKK_CODEX_PLUGIN_DISPLAY_NAME = "Brokk"

_BROKK_INSTRUCTIONS_BODY_CLAUDE = f"""{_BROKK_MARKER}
- Use callSearchAgent to explore the codebase when you don't know where relevant code lives.
- Use callCodeAgent (not Edit/Write) for all code changes.
- Use getFileSummaries to understand the API surface of packages or directories: class skeletons for
  ordinary source files, and for supported framework DSLs (starting with Angular .component.html)
  structured template summaries (components, bindings, pipes, events, control flow, etc.)."""

_BROKK_INSTRUCTIONS_BODY_CODEX = f"""{_BROKK_MARKER}
- Use callSearchAgent to explore the codebase when you don't know where relevant code lives.
- Use callCodeAgent (not Edit/Write) for all code changes.
- Use getFileSummaries to understand the API surface of packages or directories: class skeletons for
  ordinary source files, and for supported framework DSLs (starting with Angular .component.html)
  structured template summaries (components, bindings, pipes, events, control flow, etc.).
- At the start of each Codex session, activate Brokk MCP for the current workspace by
  calling activateWorkspace."""

_BROKK_MANAGED_BLOCK_CLAUDE = (
    f"{_BROKK_BEGIN_MANAGED}\n{_BROKK_INSTRUCTIONS_BODY_CLAUDE}\n{_BROKK_END_MANAGED}"
)
_BROKK_MANAGED_BLOCK_CODEX = (
    f"{_BROKK_BEGIN_MANAGED}\n{_BROKK_INSTRUCTIONS_BODY_CODEX}\n{_BROKK_END_MANAGED}"
)

_LEGACY_BLOCKS = [
    # old 3-line generic block
    f"""{_BROKK_MARKER}
- Prefer Brokk MCP tools for syntax-aware search and edits.
- Prefer callCodeAgent for code changes.
- Avoid shell text search when Brokk syntax-aware tools can answer.""",
    # old generic block with activateWorkspace/getActiveWorkspace
    f"""{_BROKK_MARKER}
- Prefer Brokk MCP tools for syntax-aware search and edits.
- Prefer callCodeAgent for code changes.
- Avoid shell text search when Brokk syntax-aware tools can answer.
- At the start of each Codex session, activate Brokk MCP for the current workspace by
  calling activateWorkspace, then verify with getActiveWorkspace.""",
    # tools-specific guidance block from b7efcf...
    f"""{_BROKK_MARKER}
- Use searchSymbols (not Grep) to find class/function/field definitions by name.
- Use scanUsages (not Grep) to find call sites and usages of a known symbol.
- Use getMethodSources (not Read) to retrieve specific method implementations.
- Use getClassSkeletons (not Read) to understand a class's API and structure.
- Use getClassSources (not Read) only when you need the full class implementation.
- Use getFileSummaries or skimFiles (not Read/Glob) for multi-file overviews.
- Use scan to get oriented when starting a new task.
- Use callCodeAgent (not Edit/Write) for all code changes.""",
]
_BROKK_MCP_PERMISSION_ALLOW: list[str] = [
    "Bash(./gradlew:*)",
    # MCP permissions do not support wildcards for tool names.
    # Allow the entire Brokk MCP server by name instead.
    "mcp__brokk",
]


def _ensure_brokk_instructions(path: Path, managed_block: str) -> None:
    """Manages Brokk instructions in a markdown file with delimiters and migration."""
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(managed_block, encoding="utf-8")
        return

    content = path.read_text(encoding="utf-8")

    # Update existing managed block. If delimiters are present but malformed
    # (e.g. END before BEGIN), recover by rewriting a fresh managed block.
    if _BROKK_BEGIN_MANAGED in content and _BROKK_END_MANAGED in content:
        new_content, replacement_count = _BROKK_MANAGED_RE.subn(managed_block, content)
        if replacement_count > 0:
            if new_content != content:
                path.write_text(new_content, encoding="utf-8")
            return

        path.write_text(managed_block, encoding="utf-8")
        return

    # Check for exact legacy matches or empty files
    trimmed = content.strip()
    if not trimmed or any(trimmed == legacy.strip() for legacy in _LEGACY_BLOCKS):
        path.write_text(managed_block, encoding="utf-8")
        return

    # Preserve custom # Brokk content
    if _BROKK_MARKER_RE.search(content):
        return

    # Append managed block
    separator = "\n\n" if not content.endswith("\n\n") else ""
    if content.endswith("\n") and not content.endswith("\n\n"):
        separator = "\n"
    new_content = content + separator + managed_block

    path.write_text(new_content, encoding="utf-8")


def _toml_key(key: str) -> str:
    if _IDENTIFIER_RE.match(key):
        return key
    return json.dumps(key)


def _toml_value(value: Any) -> str:
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return str(value)
    if isinstance(value, list):
        return f"[{', '.join(_toml_value(item) for item in value)}]"
    if isinstance(value, dict):
        return (
            "{ " + ", ".join(f"{_toml_key(k)} = {_toml_value(v)}" for k, v in value.items()) + " }"
        )
    if value is None:
        return "null"
    raise TypeError(f"Unsupported TOML value type: {type(value)!r}")


def _serialize_toml(data: dict[str, Any]) -> str:
    """Serializes a dictionary to TOML format."""
    lines: list[str] = []

    for key, value in data.items():
        if not isinstance(value, dict):
            lines.append(f"{_toml_key(key)} = {_toml_value(value)}")

    for key, value in data.items():
        if not isinstance(value, dict):
            continue

        if key == "mcp_servers":
            for server_name, server_config in value.items():
                lines.append("")
                if not isinstance(server_config, dict):
                    raise ValueError(
                        "Expected every '[mcp_servers.<name>]' entry to be a TOML table"
                    )
                lines.append(f"[{_toml_key(key)}.{_toml_key(server_name)}]")
                for cfg_key, cfg_val in server_config.items():
                    lines.append(f"{_toml_key(cfg_key)} = {_toml_value(cfg_val)}")
            continue

        if value:
            lines.append("")
            lines.append(f"[{_toml_key(key)}]")
            for cfg_key, cfg_val in value.items():
                lines.append(f"{_toml_key(cfg_key)} = {_toml_value(cfg_val)}")
        else:
            lines.append("")
            lines.append(f"[{_toml_key(key)}]")

    return "\n".join(lines) + "\n"


def _atomic_write_toml(path: Path, text: str) -> None:
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temp_file:
        temp_file.write(text)
        temp_path = Path(temp_file.name)

    if path.exists():
        temp_path.chmod(path.stat().st_mode)

    temp_path.replace(path)


def _merge_claude_permissions(data: dict[str, Any]) -> None:
    permissions = data.get("permissions")
    if permissions is None:
        permissions = {}
        data["permissions"] = permissions
    elif not isinstance(permissions, dict):
        raise ValueError("Expected 'permissions' to be a JSON object")

    allow_rules = permissions.get("allow")
    if allow_rules is None:
        allow_rules = []
        permissions["allow"] = allow_rules
    elif not isinstance(allow_rules, list):
        raise ValueError("Expected 'permissions.allow' to be an array")

    seen: set[str] = set()
    for existing_rule in allow_rules:
        if not isinstance(existing_rule, str):
            raise ValueError("Expected every entry in 'permissions.allow' to be a string")
        seen.add(existing_rule)

    for rule in _BROKK_MCP_PERMISSION_ALLOW:
        if rule not in seen:
            allow_rules.append(rule)
            seen.add(rule)


def _brokk_mcp_config(uvx_command: str) -> dict[str, Any]:
    return {
        "command": uvx_command,
        "args": ["brokk", "mcp"],
        "type": "stdio",
    }


@dataclass(frozen=True)
class InstalledCodexPlugin:
    plugin_path: Path
    marketplace_path: Path


_GITHUB_RAW_BASE = "https://raw.githubusercontent.com/BrokkAi/brokk"
_CLAUDE_PLUGIN_VERSION = "0.4.0"

_log = logging.getLogger(__name__)

# Skills from claude-plugin/ that the Codex plugin should include
_CODEX_SKILL_NAMES: list[str] = [
    "code-navigation",
    "code-reading",
    "codebase-search",
    "git-exploration",
    "structured-data",
    "workspace",
    "review-pr",
    "guided-review",
    "guided-issue",
]

# Shared list of reviewer agents used by multiple skills
_REVIEW_AGENTS = [
    "security-reviewer",
    "dry-reviewer",
    "senior-dev-reviewer",
    "devops-reviewer",
    "architect-reviewer",
]

# Map of skill name -> list of agent files to concatenate
_SKILL_AGENT_DEPS: dict[str, list[str]] = {
    "review-pr": _REVIEW_AGENTS,
    "guided-review": _REVIEW_AGENTS,
    "guided-issue": [
        "issue-diagnostician",
        "issue-planner",
        *_REVIEW_AGENTS,
    ],
}


def _fetch_github_file(path: str) -> str:
    """Fetch a file from the brokk GitHub repo.

    Tries the ``claude-plugin-{version}`` tag first, falls back to ``master``
    if the tag does not exist (HTTP 404).
    """
    tag_ref = f"claude-plugin-{_CLAUDE_PLUGIN_VERSION}"
    for ref in (tag_ref, "master"):
        url = f"{_GITHUB_RAW_BASE}/{ref}/{path}"
        _log.info("Fetching %s", url)
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                return resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            if exc.code == 404 and ref != "master":
                _log.warning("Tag %s not found, falling back to master", tag_ref)
                continue
            raise
    raise RuntimeError(f"Failed to fetch {path} from GitHub")


def _fetch_codex_skills() -> dict[str, str]:
    """Fetch all skills and concat agent prompts for skills that need them."""
    skills: dict[str, str] = {}
    for name in _CODEX_SKILL_NAMES:
        content = _fetch_github_file(f"claude-plugin/skills/{name}/SKILL.md")
        agent_names = _SKILL_AGENT_DEPS.get(name, [])
        if agent_names:
            agent_sections: list[str] = []
            for agent_name in agent_names:
                agent_md = _fetch_github_file(f"claude-plugin/agents/{agent_name}.md")
                agent_sections.append(f"## {agent_name}\n\n```md\n{agent_md.rstrip()}\n```")
            content = content.rstrip() + "\n\n# Embedded Agent Prompts\n\n"
            content += "\n\n".join(agent_sections) + "\n"
        skills[name] = content
    return skills



def _fetch_plugin_manifest() -> dict[str, Any]:
    """Fetch plugin.json from GitHub and adapt for Codex (strip agents field)."""
    raw = _fetch_github_file("claude-plugin/.claude-plugin/plugin.json")
    manifest = json.loads(raw)
    # Codex doesn't support the agents field; drop it
    manifest.pop("agents", None)
    return manifest


def _fetch_plugin_mcp_config() -> dict[str, Any]:
    """Fetch .mcp.json from GitHub."""
    raw = _fetch_github_file("claude-plugin/.mcp.json")
    return json.loads(raw)


def _marketplace_root(marketplace_path: Path) -> Path:
    if (
        marketplace_path.name == "marketplace.json"
        and marketplace_path.parent.name == "plugins"
        and marketplace_path.parent.parent.name == ".agents"
    ):
        return marketplace_path.parent.parent.parent
    return marketplace_path.parent


def _relative_marketplace_source_path(*, plugin_path: Path, marketplace_path: Path) -> str:
    marketplace_root = _marketplace_root(marketplace_path)
    # `os.path.relpath` returns platform-specific separators. Marketplace JSON
    # expects stable POSIX-style paths, so normalize to forward slashes.
    relative_path = os.path.relpath(plugin_path, start=marketplace_root).replace("\\", "/")
    if relative_path.startswith("./") or relative_path.startswith("../"):
        return relative_path
    return f"./{relative_path}"


def _build_codex_plugin_marketplace_entry(
    *, plugin_path: Path, marketplace_path: Path
) -> dict[str, Any]:
    return {
        "name": _BROKK_CODEX_PLUGIN_NAME,
        "source": {
            "source": "local",
            "path": _relative_marketplace_source_path(
                plugin_path=plugin_path, marketplace_path=marketplace_path
            ),
        },
        "policy": {
            "installation": "AVAILABLE",
            "authentication": "ON_INSTALL",
        },
        "category": "Productivity",
        "interface": {
            "displayName": _BROKK_CODEX_PLUGIN_DISPLAY_NAME,
        },
    }


def _write_codex_plugin_files(*, plugin_path: Path) -> None:
    # Fetch all remote content first so a network failure doesn't leave a
    # partially installed plugin directory.
    codex_skills = _fetch_codex_skills()
    manifest = _fetch_plugin_manifest()
    mcp_config = _fetch_plugin_mcp_config()

    manifest_dir = plugin_path / ".codex-plugin"
    skills_dir = plugin_path / "skills"
    manifest_dir.mkdir(parents=True, exist_ok=True)
    skills_dir.mkdir(parents=True, exist_ok=True)

    atomic_write_settings(manifest_dir / "plugin.json", manifest)
    atomic_write_settings(plugin_path / ".mcp.json", mcp_config)

    for skill_dir_name, skill_markdown in codex_skills.items():
        skill_dir = skills_dir / skill_dir_name
        skill_dir.mkdir(parents=True, exist_ok=True)
        (skill_dir / "SKILL.md").write_text(skill_markdown, encoding="utf-8")


def _load_marketplace(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}

    raw_text = path.read_text(encoding="utf-8")
    data = loads_json_or_jsonc(raw_text)
    if not isinstance(data, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return data


def _merge_codex_tool_approval(settings_path: Path | None = None, uvx_command: str = "uvx") -> None:
    """Ensure a full ``[mcp_servers.brokk]`` entry with
    ``default_tools_approval_mode = "approve"`` exists in Codex's ``config.toml``.

    Codex's approval checker only reads the TOML config layers, not
    plugin-loaded MCP servers, so a complete server definition (including
    transport) is required here."""
    path = settings_path or Path.home() / ".codex" / "config.toml"
    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        if raw_text.strip():
            try:
                settings = tomllib.loads(raw_text)
            except ValueError as exc:
                raise ValueError(f"Could not parse {path} as TOML: {exc}") from exc
        else:
            settings = {}
    else:
        settings = {}

    mcp_servers = settings.get("mcp_servers")
    if mcp_servers is None:
        mcp_servers = {}
        settings["mcp_servers"] = mcp_servers

    expected = {
        "command": uvx_command,
        "args": ["brokk", "mcp-core"],
        "default_tools_approval_mode": "approve",
    }
    server = mcp_servers.get(_SERVER_NAME)
    if isinstance(server, dict) and all(server.get(k) == v for k, v in expected.items()):
        return

    mcp_servers[_SERVER_NAME] = expected
    path.parent.mkdir(parents=True, exist_ok=True)
    toml_text = _serialize_toml(settings)
    _atomic_write_toml(path, toml_text)


def install_codex_local_plugin(
    *,
    force: bool = False,
    plugin_path: Path | None = None,
    marketplace_path: Path | None = None,
    settings_path: Path | None = None,
    uvx_command: str = "uvx",
) -> InstalledCodexPlugin:
    plugin_dir = plugin_path or (Path.home() / ".codex" / "plugins" / _BROKK_CODEX_PLUGIN_NAME)
    marketplace = marketplace_path or (Path.home() / ".agents" / "plugins" / "marketplace.json")

    plugin_dir.parent.mkdir(parents=True, exist_ok=True)
    if plugin_dir.exists():
        manifest_path = plugin_dir / ".codex-plugin" / "plugin.json"
        if manifest_path.exists():
            existing_manifest = loads_json_or_jsonc(manifest_path.read_text(encoding="utf-8"))
            if not isinstance(existing_manifest, dict):
                raise ValueError(f"Expected a JSON object in {manifest_path}")
            existing_name = existing_manifest.get("name")
            if existing_name != _BROKK_CODEX_PLUGIN_NAME and not force:
                raise ExistingBrokkCodeEntryError(
                    f"{manifest_path} already defines plugin '{existing_name}'; "
                    "use --force to overwrite it"
                )
        elif any(plugin_dir.iterdir()) and not force:
            raise ExistingBrokkCodeEntryError(
                f"{plugin_dir} already exists and is not a Brokk plugin; "
                "use --force to overwrite it"
            )

    _write_codex_plugin_files(plugin_path=plugin_dir)

    marketplace_data = _load_marketplace(marketplace)
    if "name" not in marketplace_data:
        marketplace_data["name"] = _BROKK_CODEX_PLUGIN_MARKETPLACE_NAME

    plugins = marketplace_data.get("plugins")
    if plugins is None:
        plugins = []
        marketplace_data["plugins"] = plugins
    elif not isinstance(plugins, list):
        raise ValueError("Expected 'plugins' to be an array")

    new_entry = _build_codex_plugin_marketplace_entry(
        plugin_path=plugin_dir, marketplace_path=marketplace
    )
    replacement_index: int | None = None
    for index, existing_plugin in enumerate(plugins):
        if not isinstance(existing_plugin, dict):
            raise ValueError("Expected every marketplace plugin entry to be a JSON object")
        if existing_plugin.get("name") != _BROKK_CODEX_PLUGIN_NAME:
            continue
        if existing_plugin != new_entry and not force:
            raise ExistingBrokkCodeEntryError(
                f"plugins['{_BROKK_CODEX_PLUGIN_NAME}'] already exists; use --force to overwrite it"
            )
        replacement_index = index
        break

    if replacement_index is None:
        plugins.append(new_entry)
    else:
        plugins[replacement_index] = new_entry

    marketplace.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_settings(marketplace, marketplace_data)

    _merge_codex_tool_approval(settings_path, uvx_command=uvx_command)

    return InstalledCodexPlugin(plugin_path=plugin_dir, marketplace_path=marketplace)


def configure_claude_code_mcp_settings(
    *, force: bool = False, settings_path: Path | None = None, uvx_command: str = "uvx"
) -> Path:
    """Configure Claude Code MCP settings.

    Args:
        force: Overwrite existing brokk entry if present.
        settings_path: Custom path to .claude.json (default: ~/.claude.json).
        uvx_command: Path to the uvx binary (default: "uvx").
    """
    path = settings_path or Path.home() / ".claude.json"
    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        try:
            settings = json.loads(raw_text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Could not parse {path} as JSON: {exc}") from exc
        if not isinstance(settings, dict):
            raise ValueError(f"Expected a JSON object in {path}")
    else:
        settings = {}

    mcp_servers = settings.get("mcpServers")
    if mcp_servers is None:
        mcp_servers = {}
        settings["mcpServers"] = mcp_servers
    elif not isinstance(mcp_servers, dict):
        raise ValueError("Expected 'mcpServers' to be a JSON object")

    if _SERVER_NAME in mcp_servers and not force:
        raise ExistingBrokkCodeEntryError(
            f"mcpServers['{_SERVER_NAME}'] already exists; use --force to overwrite it"
        )

    server_config = _brokk_mcp_config(uvx_command) | {
        "env": {
            "MCP_TIMEOUT": "60000",
            "MCP_TOOL_TIMEOUT": "300000",
        },
    }
    mcp_servers[_SERVER_NAME] = server_config
    _merge_claude_permissions(settings)
    path.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_settings(path, settings)

    # Append to CLAUDE.md
    if settings_path is None:
        claude_md_path = Path.home() / ".claude" / "CLAUDE.md"
    else:
        claude_md_path = path.parent / "CLAUDE.md"

    _ensure_brokk_instructions(claude_md_path, _BROKK_MANAGED_BLOCK_CLAUDE)

    return path


def configure_codex_mcp_settings(
    *, force: bool = False, settings_path: Path | None = None, uvx_command: str = "uvx"
) -> Path:
    """Configure Codex MCP settings.

    Args:
        force: Overwrite existing brokk entry if present.
        settings_path: Custom path to config.toml (default: ~/.codex/config.toml).
        uvx_command: Path to the uvx binary (default: "uvx").
    """
    path = settings_path or Path.home() / ".codex" / "config.toml"
    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        if raw_text.strip():
            try:
                settings = tomllib.loads(raw_text)
            except ValueError as exc:
                raise ValueError(f"Could not parse {path} as TOML: {exc}") from exc
        else:
            settings = {}
        if not isinstance(settings, dict):
            raise ValueError(f"Expected a TOML object in {path}")
    else:
        settings = {}

    mcp_servers = settings.get("mcp_servers")
    if mcp_servers is None:
        mcp_servers = {}
        settings["mcp_servers"] = mcp_servers
    elif not isinstance(mcp_servers, dict):
        raise ValueError("Expected 'mcp_servers' to be a TOML table")

    if _SERVER_NAME in mcp_servers and not force:
        raise ExistingBrokkCodeEntryError(
            f"mcp_servers['{_SERVER_NAME}'] already exists; use --force to overwrite it"
        )

    server_config = _brokk_mcp_config(uvx_command) | {
        "startup_timeout_sec": 60.0,
        "tool_timeout_sec": 300.0,
        "default_tools_approval_mode": "approve",
    }
    mcp_servers[_SERVER_NAME] = server_config

    path.parent.mkdir(parents=True, exist_ok=True)
    # Add comment explaining uvx usage (TOML supports comments)
    toml_text = "# Brokk uses uvx to always run the latest version\n" + _serialize_toml(settings)
    _atomic_write_toml(path, toml_text)

    # Append to AGENTS.md
    if settings_path is None:
        agents_md_path = Path.home() / ".codex" / "AGENTS.md"
    else:
        agents_md_path = path.parent / "AGENTS.md"

    _ensure_brokk_instructions(agents_md_path, _BROKK_MANAGED_BLOCK_CODEX)

    return path


def _build_codex_workspace_skill_markdown() -> str:
    return f"""---
name: {_BROKK_CODEX_WORKSPACE_SKILL_NAME}
description: Activate Brokk MCP for the current workspace with global Codex MCP config.
---

# Brokk MCP Workspace Activation

Use this skill when Brokk MCP is connected but looking at the wrong repository.

## Steps

1. Determine the current Codex workspace path from this session (do not ask the user).
2. Call Brokk MCP tool `activateWorkspace` with:
   - `workspacePath`: absolute current workspace path
3. Call Brokk MCP tool `getActiveWorkspace` and verify:
   - `activeWorkspacePath` matches the same path (or its normalized git root)
   - `source` is `runtime_override`
"""


def install_codex_mcp_workspace_skill(*, skills_path: Path | None = None) -> Path:
    root = skills_path or (Path.home() / ".codex" / "skills")
    skill_dir = root / _BROKK_CODEX_WORKSPACE_SKILL_NAME
    skill_dir.mkdir(parents=True, exist_ok=True)

    skill_path = skill_dir / "SKILL.md"
    skill_path.write_text(_build_codex_workspace_skill_markdown(), encoding="utf-8")
    return skill_path


def _build_codex_summaries_skill_markdown() -> str:
    return f"""---
name: {_BROKK_CODEX_SUMMARIES_SKILL_NAME}
description: Use getFileSummaries for class skeletons and framework DSL summaries (e.g. Angular
             templates).
---

# Brokk File Summaries

Use this skill to understand the API surface of a package or directory
without reading full source code. Summaries are not only class skeletons:
for supported framework template DSLs (starting with Angular `.component.html` files),
`getFileSummaries` returns structured template-oriented output (components used, bindings,
pipes, events, control flow, directives, and related symbols) instead of a class API sketch.

## Guidance

1. Use `getFileSummaries` with glob patterns to get class skeletons
   (fields and method signatures, no bodies) for ordinary source files in a package
   or directory, or DSL-oriented summaries for supported template files as above.
2. Only escalate to heavier read tools (`getClassSources`,
   `getMethodSources`) once you have identified the specific classes
   or methods you need.
"""


def install_codex_mcp_summaries_skill(*, skills_path: Path | None = None) -> Path:
    root = skills_path or (Path.home() / ".codex" / "skills")
    skill_dir = root / _BROKK_CODEX_SUMMARIES_SKILL_NAME
    skill_dir.mkdir(parents=True, exist_ok=True)

    skill_path = skill_dir / "SKILL.md"
    skill_path.write_text(_build_codex_summaries_skill_markdown(), encoding="utf-8")
    return skill_path


def _build_claude_workspace_skill_markdown() -> str:
    return f"""---
name: {_BROKK_CLAUDE_WORKSPACE_SKILL_NAME}
description: Activate Brokk MCP for the current workspace with global Claude Code MCP config.
---

# Brokk MCP Workspace Activation

Use this skill when Brokk MCP is connected but looking at the wrong repository.

## Steps

1. Determine the current workspace path from this session (do not ask the user).
2. Call Brokk MCP tool `activateWorkspace` with:
   - `workspacePath`: absolute current workspace path
3. Call Brokk MCP tool `getActiveWorkspace` and verify:
   - `activeWorkspacePath` matches the same path (or its normalized git root)
   - `source` is `runtime_override`
"""


def install_claude_mcp_workspace_skill(*, skills_path: Path | None = None) -> Path:
    root = skills_path or (Path.home() / ".claude" / "skills")
    skill_dir = root / _BROKK_CLAUDE_WORKSPACE_SKILL_NAME
    skill_dir.mkdir(parents=True, exist_ok=True)

    skill_path = skill_dir / "SKILL.md"
    skill_path.write_text(_build_claude_workspace_skill_markdown(), encoding="utf-8")
    return skill_path


def _build_claude_summaries_skill_markdown() -> str:
    return f"""---
name: {_BROKK_CLAUDE_SUMMARIES_SKILL_NAME}
description: Use getFileSummaries for class skeletons and framework DSL summaries (e.g. Angular
             templates).
---

# Brokk File Summaries

Use this skill to understand the API surface of a package or directory
without reading full source code. Summaries are not only class skeletons:
for supported framework template DSLs (starting with Angular `.component.html` files),
`getFileSummaries` returns structured template-oriented output (components used, bindings,
pipes, events, control flow, directives, and related symbols) instead of a class API sketch.

## Guidance

1. Use `getFileSummaries` with glob patterns to get class skeletons
   (fields and method signatures, no bodies) for ordinary source files in a package
   or directory, or DSL-oriented summaries for supported template files as above.
2. Only escalate to heavier read tools (`getClassSources`,
   `getMethodSources`) once you have identified the specific classes
   or methods you need.
"""


def install_claude_mcp_summaries_skill(*, skills_path: Path | None = None) -> Path:
    root = skills_path or (Path.home() / ".claude" / "skills")
    skill_dir = root / _BROKK_CLAUDE_SUMMARIES_SKILL_NAME
    skill_dir.mkdir(parents=True, exist_ok=True)

    skill_path = skill_dir / "SKILL.md"
    skill_path.write_text(_build_claude_summaries_skill_markdown(), encoding="utf-8")
    return skill_path
