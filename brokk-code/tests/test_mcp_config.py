import json
import stat
import tomllib

import pytest

from brokk_code.mcp_config import (
    configure_claude_code_mcp_settings,
    configure_codex_mcp_settings,
)
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def test_configure_claude_code_mcp_settings_defaults_to_settings_json(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    written_path = configure_claude_code_mcp_settings()

    assert written_path == tmp_path / ".claude" / "settings.json"
    data = json.loads(written_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["env"] == {
        "MCP_TIMEOUT": "60000",
        "MCP_TOOL_TIMEOUT": "300000",
    }


def test_configure_claude_code_mcp_settings_creates_file(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"

    written_path = configure_claude_code_mcp_settings(settings_path=settings_path)

    assert written_path == settings_path
    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "brokk"
    assert data["mcpServers"]["brokk"]["args"] == ["mcp"]
    assert data["mcpServers"]["brokk"]["type"] == "stdio"
    assert data["mcpServers"]["brokk"]["env"] == {
        "MCP_TIMEOUT": "60000",
        "MCP_TOOL_TIMEOUT": "300000",
    }


def test_configure_claude_code_mcp_settings_merges_existing_values(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"
    settings_path.write_text(
        json.dumps(
            {
                "theme": "One Dark",
                "mcpServers": {
                    "Other": {
                        "command": "other-agent",
                    }
                },
            }
        ),
        encoding="utf-8",
    )

    configure_claude_code_mcp_settings(settings_path=settings_path)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["theme"] == "One Dark"
    assert "Other" in data["mcpServers"]
    assert "brokk" in data["mcpServers"]


def test_configure_claude_code_mcp_settings_rejects_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"
    settings_path.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "brokk": {
                        "command": "existing",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ExistingBrokkCodeEntryError):
        configure_claude_code_mcp_settings(settings_path=settings_path)


def test_configure_claude_mcp_force_overwrite(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"
    settings_path.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "brokk": {
                        "command": "existing",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    configure_claude_code_mcp_settings(settings_path=settings_path, force=True)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["mcpServers"]["brokk"]["command"] == "brokk"


def test_configure_claude_code_mcp_settings_preserves_existing_permissions(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"
    settings_path.write_text(json.dumps({}), encoding="utf-8")
    settings_path.chmod(0o640)
    expected_mode = stat.S_IMODE(settings_path.stat().st_mode)

    configure_claude_code_mcp_settings(settings_path=settings_path)

    mode = stat.S_IMODE(settings_path.stat().st_mode)
    assert mode == expected_mode


def test_configure_claude_code_mcp_settings_validates_type(tmp_path) -> None:
    settings_path = tmp_path / ".claude.json"
    settings_path.write_text("[ ]", encoding="utf-8")

    with pytest.raises(ValueError, match="Expected a JSON object"):
        configure_claude_code_mcp_settings(settings_path=settings_path)


def test_configure_codex_mcp_settings_creates_file(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"

    written_path = configure_codex_mcp_settings(settings_path=settings_path)

    assert written_path == settings_path
    data = tomllib.loads(settings_path.read_text(encoding="utf-8"))
    assert data["mcp_servers"]["brokk"]["command"] == "brokk"
    assert data["mcp_servers"]["brokk"]["args"] == ["mcp"]
    assert data["mcp_servers"]["brokk"]["type"] == "stdio"
    assert data["mcp_servers"]["brokk"]["startup_timeout_sec"] == 60.0
    assert data["mcp_servers"]["brokk"]["tool_timeout_sec"] == 300.0


def test_configure_codex_mcp_settings_merges_existing_values(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """
        [features]
        rmcp_client = true

        [mcp_servers.other]
        command = "other-agent"
        """,
        encoding="utf-8",
    )

    configure_codex_mcp_settings(settings_path=settings_path)

    data = tomllib.loads(settings_path.read_text(encoding="utf-8"))
    assert data["features"]["rmcp_client"] is True
    assert "other" in data["mcp_servers"]
    assert "brokk" in data["mcp_servers"]


def test_configure_codex_mcp_settings_rejects_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """
        [mcp_servers.brokk]
        command = "existing"
        """,
        encoding="utf-8",
    )

    with pytest.raises(ExistingBrokkCodeEntryError):
        configure_codex_mcp_settings(settings_path=settings_path)


def test_configure_codex_mcp_settings_force_overwrites_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """
        [mcp_servers.brokk]
        command = "existing"
        """,
        encoding="utf-8",
    )

    configure_codex_mcp_settings(settings_path=settings_path, force=True)

    data = tomllib.loads(settings_path.read_text(encoding="utf-8"))
    assert data["mcp_servers"]["brokk"]["command"] == "brokk"


def test_configure_codex_mcp_settings_preserves_existing_permissions(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text("[features]\nrmcp_client = true\n", encoding="utf-8")
    settings_path.chmod(0o640)
    expected_mode = stat.S_IMODE(settings_path.stat().st_mode)

    configure_codex_mcp_settings(settings_path=settings_path)

    mode = stat.S_IMODE(settings_path.stat().st_mode)
    assert mode == expected_mode


def test_configure_codex_mcp_settings_validates_types(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """
        mcp_servers = "bad"
        """,
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="Expected 'mcp_servers' to be a TOML table"):
        configure_codex_mcp_settings(settings_path=settings_path)


def test_configure_codex_mcp_settings_invalid_toml_exits_nonzero(tmp_path) -> None:
    settings_path = tmp_path / ".codex" / "config.toml"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text("[mcp_servers", encoding="utf-8")

    with pytest.raises(ValueError, match="Could not parse"):
        configure_codex_mcp_settings(settings_path=settings_path)
