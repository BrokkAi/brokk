import json
import tomllib

import pytest

from brokk_code.mcp_config import (
    configure_claude_code_mcp_settings,
    configure_codex_mcp_settings,
    install_claude_mcp_summaries_skill,
    install_claude_mcp_workspace_skill,
    install_codex_mcp_summaries_skill,
    install_codex_mcp_workspace_skill,
)
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def test_configure_claude_code_mcp_settings_uses_claude_json(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))

    returned_path = configure_claude_code_mcp_settings(force=True)

    expected_path = tmp_path / ".claude.json"
    assert returned_path == expected_path
    assert expected_path.exists()

    data = json.loads(expected_path.read_text(encoding="utf-8"))
    assert "mcpServers" in data
    assert "brokk" in data["mcpServers"]
    assert data["mcpServers"]["brokk"]["command"] == "uvx"
    assert data["mcpServers"]["brokk"]["args"] == ["brokk", "mcp"]


def test_configure_claude_code_mcp_settings_preserves_existing_keys(tmp_path):
    config_path = tmp_path / ".claude.json"
    existing_data = {
        "firstStartTime": "2024-01-01T00:00:00Z",
        "unrelatedKey": 42,
        "mcpServers": {"other": {"command": "node", "args": ["server.js"]}},
    }
    config_path.write_text(json.dumps(existing_data), encoding="utf-8")

    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    new_data = json.loads(config_path.read_text(encoding="utf-8"))
    assert new_data["firstStartTime"] == "2024-01-01T00:00:00Z"
    assert new_data["unrelatedKey"] == 42
    assert "other" in new_data["mcpServers"]
    assert "brokk" in new_data["mcpServers"]


def test_configure_claude_code_mcp_settings_conflict(tmp_path):
    config_path = tmp_path / ".claude.json"
    existing_data = {
        "mcpServers": {
            "brokk": {"command": "old-command"},
        }
    }
    config_path.write_text(json.dumps(existing_data), encoding="utf-8")

    with pytest.raises(ExistingBrokkCodeEntryError):
        configure_claude_code_mcp_settings(force=False, settings_path=config_path)

    # Verify it wasn't overwritten
    data = json.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "old-command"


def test_configure_claude_code_mcp_settings_appends_to_claude_md(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    configure_claude_code_mcp_settings(force=True)

    claude_json = tmp_path / ".claude.json"
    instructions = tmp_path / ".claude" / "CLAUDE.md"

    assert claude_json.exists()
    assert instructions.exists()
    content = instructions.read_text()
    assert "<!-- BROKK:BEGIN MANAGED SECTION -->" in content
    assert "# Brokk" in content
    assert "getSummaries" in content
    assert "searchSymbols" not in content
    assert "scanUsages" not in content
    assert "scan" not in content
    assert "getMethodSources" not in content
    assert "getClassSources" not in content
    assert "callCodeAgent" in content
    assert "activateWorkspace" not in content
    assert "getActiveWorkspace" not in content
    assert "<!-- BROKK:END MANAGED SECTION -->" in content


def test_configure_claude_code_mcp_settings_skips_duplicate_brokk_mark(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    instructions_path = tmp_path / ".claude" / "CLAUDE.md"
    instructions_path.parent.mkdir(parents=True)

    instructions_path.write_text("# Existing content\n\n# Brokk\nCustom instructions")

    configure_claude_code_mcp_settings(force=True)

    content = instructions_path.read_text()
    # Should skip managed block because legacy '# Brokk' marker is found
    assert "<!-- BROKK:BEGIN MANAGED SECTION -->" not in content
    assert content.count("# Brokk") == 1
    assert "Custom instructions" in content


def test_configure_codex_mcp_settings_appends_to_codex_agents(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    configure_codex_mcp_settings(force=True)

    config_path = tmp_path / ".codex" / "config.toml"
    agents_md = tmp_path / ".codex" / "AGENTS.md"

    assert config_path.exists()
    assert agents_md.exists()
    content = agents_md.read_text()
    assert "<!-- BROKK:BEGIN MANAGED SECTION -->" in content
    assert "# Brokk" in content
    assert "getSummaries" in content
    assert "activateWorkspace" in content
    assert "searchSymbols" not in content
    assert "scanUsages" not in content
    assert "scan" not in content
    assert "getMethodSources" not in content
    assert "getClassSources" not in content
    assert "callCodeAgent" in content
    assert "getActiveWorkspace" not in content
    assert "<!-- BROKK:END MANAGED SECTION -->" in content


def test_configure_codex_mcp_settings_skips_duplicate_brokk_mark(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"

    agents_md.parent.mkdir(parents=True)
    agents_md.write_text("# Existing\n\n# Brokk\nAlready here")

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    # Should skip managed block because legacy '# Brokk' marker is found
    assert "<!-- BROKK:BEGIN MANAGED SECTION -->" not in content
    assert content.count("# Brokk") == 1
    assert "Already here" in content


def test_configure_mcp_updates_stale_managed_block(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"
    agents_md.parent.mkdir(parents=True)

    stale_content = (
        "<!-- BROKK:BEGIN MANAGED SECTION -->\nOld content\n<!-- BROKK:END MANAGED SECTION -->"
    )
    agents_md.write_text(f"Prefix\n\n{stale_content}\n\nSuffix")

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    assert "Prefix" in content
    assert "Suffix" in content
    assert "Old content" not in content
    assert "activateWorkspace" in content
    assert content.count("<!-- BROKK:BEGIN MANAGED SECTION -->") == 1


def test_configure_mcp_migrates_legacy_block(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"
    agents_md.parent.mkdir(parents=True)

    legacy = (
        "# Brokk\n"
        "- Prefer Brokk MCP tools for syntax-aware search and edits.\n"
        "- Prefer callCodeAgent for code changes.\n"
        "- Avoid shell text search when Brokk syntax-aware tools can answer."
    )
    agents_md.write_text(legacy)

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    assert "<!-- BROKK:BEGIN MANAGED SECTION -->" in content
    assert "activateWorkspace" in content
    # Legacy didn't have delimiters, migration replaces the whole file if it was exact match
    assert content.startswith("<!-- BROKK:BEGIN MANAGED SECTION -->")


def test_configure_claude_code_mcp_settings_uses_uvx_command(tmp_path):
    """Verify the config uses the provided uvx command path."""
    config_path = tmp_path / ".claude.json"
    configure_claude_code_mcp_settings(
        force=True, settings_path=config_path, uvx_command="/usr/local/bin/uvx"
    )

    data = json.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "/usr/local/bin/uvx"
    assert data["mcpServers"]["brokk"]["args"] == ["brokk", "mcp"]


def test_configure_codex_mcp_settings_uses_uvx_command(tmp_path):
    """Verify the config uses the provided uvx command path."""
    config_path = tmp_path / "config.toml"
    configure_codex_mcp_settings(
        force=True, settings_path=config_path, uvx_command="/home/user/.local/bin/uvx"
    )

    data = tomllib.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcp_servers"]["brokk"]["command"] == "/home/user/.local/bin/uvx"
    assert data["mcp_servers"]["brokk"]["args"] == ["brokk", "mcp"]


def test_configure_claude_code_mcp_settings_defaults_to_uvx(tmp_path):
    """When no uvx_command is given, defaults to bare 'uvx'."""
    config_path = tmp_path / ".claude.json"
    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    data = json.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "uvx"
    assert data["mcpServers"]["brokk"]["args"] == ["brokk", "mcp"]


def test_configure_claude_code_mcp_settings_uses_brokk_server_permission_rule(tmp_path) -> None:
    config_path = tmp_path / ".claude.json"

    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    data = json.loads(config_path.read_text(encoding="utf-8"))
    allow_rules = data["permissions"]["allow"]
    assert "mcp__brokk" in allow_rules
    assert all(
        not (rule.startswith("mcp__brokk__") and rule != "mcp__brokk") for rule in allow_rules
    )


def test_configure_claude_code_mcp_settings_keeps_existing_permissions(tmp_path) -> None:
    config_path = tmp_path / ".claude.json"
    existing = {
        "permissions": {
            "allow": ["mcp__external", "Bash(./gradlew:*)"],
        }
    }
    config_path.write_text(json.dumps(existing), encoding="utf-8")

    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    data = json.loads(config_path.read_text(encoding="utf-8"))
    allow_rules = data["permissions"]["allow"]
    assert "mcp__external" in allow_rules
    assert "mcp__brokk" in allow_rules
    assert allow_rules.count("mcp__brokk") == 1


def test_install_codex_mcp_workspace_skill_creates_expected_skill(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    skill_path = install_codex_mcp_workspace_skill()

    assert skill_path == tmp_path / ".codex" / "skills" / "brokk-mcp-workspace" / "SKILL.md"
    assert skill_path.exists()
    content = skill_path.read_text(encoding="utf-8")
    assert "name: brokk-mcp-workspace" in content
    assert "activateWorkspace" in content
    # In the skill itself (not instruction block), getActiveWorkspace verification is fine
    assert "getActiveWorkspace" in content


def test_install_codex_mcp_summaries_skill_creates_expected_skill(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    skill_path = install_codex_mcp_summaries_skill()

    assert skill_path == tmp_path / ".codex" / "skills" / "brokk-get-summaries" / "SKILL.md"
    assert skill_path.exists()
    content = skill_path.read_text(encoding="utf-8")
    assert "name: brokk-get-summaries" in content
    assert "getSummaries" in content
    assert "class skeletons" in content


def test_install_claude_mcp_workspace_skill_creates_expected_skill(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    skill_path = install_claude_mcp_workspace_skill()

    assert skill_path == tmp_path / ".claude" / "skills" / "brokk-mcp-workspace" / "SKILL.md"
    assert skill_path.exists()
    content = skill_path.read_text(encoding="utf-8")
    assert "name: brokk-mcp-workspace" in content
    assert "activateWorkspace" in content
    assert "getActiveWorkspace" in content


def test_install_claude_mcp_summaries_skill_creates_expected_skill(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    skill_path = install_claude_mcp_summaries_skill()

    assert skill_path == tmp_path / ".claude" / "skills" / "brokk-get-summaries" / "SKILL.md"
    assert skill_path.exists()
    content = skill_path.read_text(encoding="utf-8")
    assert "name: brokk-get-summaries" in content
    assert "getSummaries" in content
    assert "class skeletons" in content


def test_configure_codex_mcp_settings_recovers_malformed_delimiters(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"
    agents_md.parent.mkdir(parents=True)

    # Malformed: END before BEGIN with stale text
    malformed_content = (
        "<!-- BROKK:END MANAGED SECTION -->\nstale text\n<!-- BROKK:BEGIN MANAGED SECTION -->\n"
    )
    agents_md.write_text(malformed_content)

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    # Should contain exactly one BEGIN and one END in the right order
    assert content.count("<!-- BROKK:BEGIN MANAGED SECTION -->") == 1
    assert content.count("<!-- BROKK:END MANAGED SECTION -->") == 1
    assert content.find("<!-- BROKK:BEGIN MANAGED SECTION -->") < content.find(
        "<!-- BROKK:END MANAGED SECTION -->"
    )
    assert "activateWorkspace" in content
    assert "getActiveWorkspace" not in content
    # Recovery path strips stray markers; stale text might persist or be moved
    # depending on implementation.
    # but the key is that the managed block is valid.
