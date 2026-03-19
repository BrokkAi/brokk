import json
import tomllib

import pytest

from brokk_code.mcp_config import (
    configure_claude_code_mcp_settings,
    configure_codex_mcp_settings,
)
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def test_configure_claude_code_mcp_settings_uses_claude_json(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setattr("brokk_code.mcp_config.resolve_brokk_command", lambda _cmd=None: "brokk")

    returned_path = configure_claude_code_mcp_settings(force=True)

    expected_path = tmp_path / ".claude.json"
    assert returned_path == expected_path
    assert expected_path.exists()

    data = json.loads(expected_path.read_text(encoding="utf-8"))
    assert "mcpServers" in data
    assert "brokk" in data["mcpServers"]
    assert data["mcpServers"]["brokk"]["command"] == "brokk"
    assert data["mcpServers"]["brokk"]["args"] == ["mcp"]


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
    assert "# Brokk" in content
    assert "Prefer Brokk MCP tools for syntax-aware search and edits." in content
    assert "Prefer callCodeAgent for code changes." in content
    assert "Avoid shell text search when Brokk syntax-aware tools can answer." in content


def test_configure_claude_code_mcp_settings_skips_duplicate_brokk_mark(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    instructions_path = tmp_path / ".claude" / "CLAUDE.md"
    instructions_path.parent.mkdir(parents=True)

    instructions_path.write_text("# Existing content\n\n# Brokk\nCustom instructions")

    configure_claude_code_mcp_settings(force=True)

    content = instructions_path.read_text()
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
    assert "# Brokk" in content
    assert "Prefer Brokk MCP tools for syntax-aware search and edits." in content
    assert "Prefer callCodeAgent for code changes." in content
    assert "Avoid shell text search when Brokk syntax-aware tools can answer." in content


def test_configure_codex_mcp_settings_skips_duplicate_brokk_mark(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"

    agents_md.parent.mkdir(parents=True)
    agents_md.write_text("# Existing\n\n# Brokk\nAlready here")

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    assert content.count("# Brokk") == 1
    assert "Already here" in content


def test_configure_claude_code_mcp_settings_uses_absolute_brokk_path(tmp_path, monkeypatch):
    """Verify the config uses an absolute brokk path for non-login shell compatibility."""
    monkeypatch.setattr(
        "brokk_code.mcp_config.resolve_brokk_command",
        lambda _cmd=None: "/usr/local/bin/brokk",
    )

    config_path = tmp_path / ".claude.json"
    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    data = json.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "/usr/local/bin/brokk"


def test_configure_codex_mcp_settings_uses_absolute_brokk_path(tmp_path, monkeypatch):
    """Verify the config uses an absolute brokk path for non-login shell compatibility."""
    monkeypatch.setattr(
        "brokk_code.mcp_config.resolve_brokk_command",
        lambda _cmd=None: "/home/user/.local/bin/brokk",
    )

    config_path = tmp_path / "config.toml"
    configure_codex_mcp_settings(force=True, settings_path=config_path)

    data = tomllib.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcp_servers"]["brokk"]["command"] == "/home/user/.local/bin/brokk"
    assert data["mcp_servers"]["brokk"]["args"] == ["mcp"]


def test_configure_mcp_settings_falls_back_to_bare_brokk(tmp_path, monkeypatch):
    """If brokk cannot be resolved, fall back to bare 'brokk' command."""
    monkeypatch.setattr("brokk_code.mcp_config.shutil.which", lambda _name: None)
    monkeypatch.setattr("brokk_code.mcp_config.sys.argv", ["brokk"])

    config_path = tmp_path / ".claude.json"
    configure_claude_code_mcp_settings(force=True, settings_path=config_path)

    data = json.loads(config_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "brokk"


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
