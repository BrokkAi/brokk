import json

import pytest

from brokk_code.mcp_config import (
    configure_claude_code_mcp_settings,
    configure_codex_mcp_settings,
)
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def test_configure_claude_code_mcp_settings_uses_claude_json(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    # Path.home() usually respects $HOME or user profile.
    # If Path.home() doesn't reflect monkeypatch in some OS,
    # we use the returned path to verify.

    returned_path = configure_claude_code_mcp_settings(force=True)

    expected_path = tmp_path / ".claude.json"
    assert returned_path == expected_path
    assert expected_path.exists()

    data = json.loads(expected_path.read_text(encoding="utf-8"))
    assert "mcpServers" in data
    assert "brokk" in data["mcpServers"]
    assert data["mcpServers"]["brokk"]["command"] == "jbang"


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
    assert "# Brokk" in instructions.read_text()


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
    assert "# Brokk" in agents_md.read_text()


def test_configure_codex_mcp_settings_skips_duplicate_brokk_mark(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    agents_md = tmp_path / ".codex" / "AGENTS.md"

    agents_md.parent.mkdir(parents=True)
    agents_md.write_text("# Existing\n\n# Brokk\nAlready here")

    configure_codex_mcp_settings(force=True)

    content = agents_md.read_text()
    assert content.count("# Brokk") == 1
    assert "Already here" in content
