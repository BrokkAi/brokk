import json
import stat
from pathlib import Path

import pytest

from brokk_code.intellij_config import configure_intellij_acp_settings
from brokk_code.rust_acp_install import RustAcpPaths
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def test_configure_intellij_acp_settings_creates_file(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"

    written_path = configure_intellij_acp_settings(settings_path=settings_path)

    assert written_path == settings_path
    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["agent_servers"]["Brokk Code"]["command"] == "uvx"
    assert data["agent_servers"]["Brokk Code"]["args"] == ["brokk", "acp"]
    # Ensure no stale --ide flags are injected
    assert "--ide" not in data["agent_servers"]["Brokk Code"]["args"]
    assert data["default_mcp_settings"] == {}


def test_configure_intellij_acp_settings_merges_existing_values(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        json.dumps(
            {
                "other_key": "val",
                "agent_servers": {
                    "Other": {
                        "command": "other-agent",
                    }
                },
            }
        ),
        encoding="utf-8",
    )

    configure_intellij_acp_settings(settings_path=settings_path)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["other_key"] == "val"
    assert "Other" in data["agent_servers"]
    assert "Brokk Code" in data["agent_servers"]
    assert data["default_mcp_settings"] == {}


def test_configure_intellij_acp_settings_rejects_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        json.dumps(
            {
                "agent_servers": {
                    "Brokk Code": {
                        "command": "existing",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ExistingBrokkCodeEntryError):
        configure_intellij_acp_settings(settings_path=settings_path)


def test_configure_intellij_acp_settings_force_overwrites_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        json.dumps(
            {
                "agent_servers": {
                    "Brokk Code": {
                        "command": "existing",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    configure_intellij_acp_settings(settings_path=settings_path, force=True)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["agent_servers"]["Brokk Code"]["command"] == "uvx"


def test_configure_intellij_acp_settings_preserves_existing_permissions(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(json.dumps({}), encoding="utf-8")
    settings_path.chmod(0o640)
    expected_mode = stat.S_IMODE(settings_path.stat().st_mode)

    configure_intellij_acp_settings(settings_path=settings_path)

    mode = stat.S_IMODE(settings_path.stat().st_mode)
    assert mode == expected_mode


def test_configure_intellij_acp_settings_validates_types(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)

    # Top-level not object
    settings_path.write_text("[]", encoding="utf-8")
    with pytest.raises(ValueError, match="Expected a JSON object"):
        configure_intellij_acp_settings(settings_path=settings_path)

    # agent_servers not object
    settings_path.write_text(json.dumps({"agent_servers": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="Expected 'agent_servers' to be a JSON object"):
        configure_intellij_acp_settings(settings_path=settings_path)

    # default_mcp_settings not object
    settings_path.write_text(json.dumps({"default_mcp_settings": 123}), encoding="utf-8")
    with pytest.raises(ValueError, match="Expected 'default_mcp_settings' to be a JSON object"):
        configure_intellij_acp_settings(settings_path=settings_path)


def test_configure_intellij_acp_settings_parses_jsonc(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """{
  "agent_servers": {
    "Existing": { "command": "cmd" }, // Comment here
  },
}
""",
        encoding="utf-8",
    )

    configure_intellij_acp_settings(settings_path=settings_path)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert "Existing" in data["agent_servers"]
    assert "Brokk Code" in data["agent_servers"]


def test_configure_intellij_acp_settings_invalid_json(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text("{ invalid", encoding="utf-8")

    with pytest.raises(ValueError, match="Could not parse .* as JSON/JSONC"):
        configure_intellij_acp_settings(settings_path=settings_path)


def test_configure_intellij_acp_settings_rust_paths_minimal(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    brokk_acp = Path("/home/u/.brokk/bin/brokk-acp")
    bifrost = Path("/home/u/.brokk/bin/bifrost")
    rust_paths = RustAcpPaths(
        brokk_acp=brokk_acp,
        bifrost=bifrost,
        model="qwen2.5-coder:7b",
    )

    configure_intellij_acp_settings(settings_path=settings_path, rust_paths=rust_paths)

    entry = json.loads(settings_path.read_text(encoding="utf-8"))["agent_servers"]["Brokk Code"]
    assert entry["command"] == str(brokk_acp)
    assert entry["args"] == [
        "--default-model",
        "qwen2.5-coder:7b",
        "--bifrost-binary",
        str(bifrost),
    ]


def test_configure_intellij_acp_settings_rust_paths_with_custom_endpoint(tmp_path) -> None:
    settings_path = tmp_path / ".jetbrains" / "acp.json"
    brokk_acp = Path("/opt/brokk-acp")
    bifrost = Path("/opt/bifrost")
    rust_paths = RustAcpPaths(
        brokk_acp=brokk_acp,
        bifrost=bifrost,
        model="claude-haiku-4-5",
        endpoint_url="http://example.invalid:8080",
        api_key="sk-test-123",
    )

    configure_intellij_acp_settings(settings_path=settings_path, rust_paths=rust_paths)

    args = json.loads(settings_path.read_text(encoding="utf-8"))[
        "agent_servers"
    ]["Brokk Code"]["args"]
    assert args == [
        "--default-model",
        "claude-haiku-4-5",
        "--bifrost-binary",
        str(bifrost),
        "--endpoint-url",
        "http://example.invalid:8080",
        "--api-key",
        "sk-test-123",
    ]
