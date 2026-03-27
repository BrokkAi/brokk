"""Tests for the Claude Code plugin installer."""

import json
import re

import pytest

from brokk_code.plugin_config import install_claude_code_plugin
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def _parse_frontmatter(content: str) -> dict[str, str]:
    """Parse simple YAML frontmatter between --- delimiters."""
    assert content.startswith("---")
    end_idx = content.index("---", 3)
    frontmatter_text = content[3:end_idx].strip()
    result = {}
    for line in frontmatter_text.splitlines():
        # Handle simple key: value and key: >- multiline
        match = re.match(r"^(\w[\w-]*):\s*(.*)$", line)
        if match:
            result[match.group(1)] = match.group(2).strip()
    return result


def test_install_creates_directory_structure(tmp_path):
    """Verify all expected files and directories are created."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    assert (plugin_root / ".claude-plugin" / "plugin.json").is_file()
    assert (plugin_root / ".mcp.json").is_file()
    assert (plugin_root / "settings.json").is_file()
    assert (plugin_root / "CLAUDE.md").is_file()
    assert (plugin_root / "hooks" / "hooks.json").is_file()
    assert (plugin_root / "hooks" / "activate_workspace.sh").is_file()

    for skill_name in ["scan", "refactor", "merge", "review", "analyze"]:
        assert (plugin_root / "skills" / skill_name / "SKILL.md").is_file()


def test_plugin_manifest_valid_json(tmp_path):
    """Verify plugin.json is valid JSON with required fields."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    manifest = json.loads((plugin_root / ".claude-plugin" / "plugin.json").read_text())
    assert manifest["name"] == "brokk-code-intelligence"
    assert "description" in manifest
    assert "author" in manifest


def test_mcp_json_points_to_pure_server(tmp_path):
    """Verify .mcp.json uses the mcp-pure command."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    config = json.loads((plugin_root / ".mcp.json").read_text())
    server_config = config["mcpServers"]["brokk"]
    assert "mcp-pure" in server_config["args"]
    assert server_config["type"] == "stdio"


def test_settings_json_has_permissions(tmp_path):
    """Verify settings.json contains expected auto-allow rules."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    settings = json.loads((plugin_root / "settings.json").read_text())
    allow_rules = settings["permissions"]["allow"]
    assert "mcp__brokk" in allow_rules


def test_skills_have_valid_frontmatter(tmp_path):
    """Each SKILL.md must have name and description in YAML frontmatter."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    for skill_name in ["scan", "refactor", "merge", "review", "analyze"]:
        skill_path = plugin_root / "skills" / skill_name / "SKILL.md"
        content = skill_path.read_text()

        assert content.startswith("---"), f"{skill_name}: must start with ---"
        frontmatter = _parse_frontmatter(content)

        assert "name" in frontmatter, f"{skill_name}: frontmatter must have 'name'"
        assert "description" in frontmatter, f"{skill_name}: frontmatter must have 'description'"
        assert frontmatter["name"] == skill_name


def test_hooks_json_valid(tmp_path):
    """Verify hooks.json has valid structure with all three hook events."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    hooks = json.loads((plugin_root / "hooks" / "hooks.json").read_text())
    assert "hooks" in hooks
    assert "SessionStart" in hooks["hooks"]
    assert "UserPromptSubmit" in hooks["hooks"]

    # SessionStart should have two entries: startup activation + compact re-injection
    session_start = hooks["hooks"]["SessionStart"]
    assert len(session_start) == 2
    assert "hooks" in session_start[0]
    # Second entry is the compact matcher
    assert session_start[1].get("matcher") == "compact"

    # UserPromptSubmit should have the enrich_prompt hook
    prompt_submit = hooks["hooks"]["UserPromptSubmit"]
    assert len(prompt_submit) > 0
    assert "hooks" in prompt_submit[0]


def test_all_hook_scripts_executable(tmp_path):
    """Verify all hook scripts are executable."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    for script_name in ["activate_workspace.sh", "post_compact.sh", "enrich_prompt.py"]:
        script = plugin_root / "hooks" / script_name
        assert script.is_file(), f"{script_name} should exist"
        assert script.stat().st_mode & 0o100, f"{script_name} should be executable"


def test_enrich_prompt_script_extracts_identifiers(tmp_path):
    """Verify the enrich_prompt.py hook produces valid output for prompts with code symbols.

    When brokk query is not available (as in tests), the script should fall back
    to injecting the extracted symbol names with a hint to use MCP tools.
    """
    import subprocess

    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    script = plugin_root / "hooks" / "enrich_prompt.py"
    hook_input = json.dumps({
        "prompt": "Refactor the BrokkExternalMcpServer to use SearchTools",
        "cwd": str(tmp_path),
    })

    result = subprocess.run(
        ["python3", str(script)],
        input=hook_input,
        capture_output=True,
        text=True,
        timeout=15,
    )
    assert result.returncode == 0
    output = json.loads(result.stdout)
    context = output["hookSpecificOutput"]["additionalContext"]
    assert "BrokkExternalMcpServer" in context
    assert "SearchTools" in context
    assert "searchSymbols" in context


def test_enrich_prompt_no_output_for_plain_text(tmp_path):
    """Verify the hook produces no output for prompts without code identifiers."""
    import subprocess

    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    script = plugin_root / "hooks" / "enrich_prompt.py"
    hook_input = json.dumps({"prompt": "hello how are you"})

    result = subprocess.run(
        ["python3", str(script)],
        input=hook_input,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode == 0
    assert result.stdout.strip() == ""


def test_post_compact_script_outputs_instructions(tmp_path):
    """Verify the post-compact hook re-injects Brokk tool instructions."""
    import subprocess

    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    script = plugin_root / "hooks" / "post_compact.sh"
    result = subprocess.run(
        ["bash", str(script)],
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode == 0
    assert "searchSymbols" in result.stdout
    assert "scanUsages" in result.stdout


def test_install_raises_without_force(tmp_path):
    """Installing over an existing plugin without --force should raise."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    with pytest.raises(ExistingBrokkCodeEntryError):
        install_claude_code_plugin(plugin_root=plugin_root)


def test_install_force_overwrites(tmp_path):
    """Installing with --force should succeed even if plugin exists."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    # Modify a file to verify it gets overwritten
    (plugin_root / "CLAUDE.md").write_text("modified")

    install_claude_code_plugin(force=True, plugin_root=plugin_root)

    content = (plugin_root / "CLAUDE.md").read_text()
    assert "Brokk Code Intelligence" in content


def test_custom_uvx_command(tmp_path):
    """Verify custom uvx command is used in .mcp.json."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root, uvx_command="/custom/uvx")

    config = json.loads((plugin_root / ".mcp.json").read_text())
    assert config["mcpServers"]["brokk"]["command"] == "/custom/uvx"


def test_claude_md_mentions_skills(tmp_path):
    """Verify CLAUDE.md references the available skills."""
    plugin_root = tmp_path / "brokk-plugin"
    install_claude_code_plugin(plugin_root=plugin_root)

    content = (plugin_root / "CLAUDE.md").read_text()
    assert "/brokk:scan" in content
    assert "/brokk:refactor" in content
    assert "/brokk:merge" in content
