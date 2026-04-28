import json
import stat
import sys
from pathlib import Path

import pytest

from brokk_code.rust_acp_install import RustAcpPaths
from brokk_code.zed_config import ExistingBrokkCodeEntryError, configure_zed_acp_settings


def test_configure_zed_acp_settings_creates_file(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"

    written_path = configure_zed_acp_settings(settings_path=settings_path)

    assert written_path == settings_path
    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["agent_servers"]["Brokk Code"]["command"] == "uvx"
    assert data["agent_servers"]["Brokk Code"]["args"] == ["brokk", "acp"]
    # Ensure no stale --ide flags are injected
    assert "--ide" not in data["agent_servers"]["Brokk Code"]["args"]


def test_configure_zed_acp_settings_merges_existing_values(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        json.dumps(
            {
                "theme": "One Dark",
                "agent_servers": {
                    "Other": {
                        "type": "custom",
                        "command": "other-agent",
                    }
                },
            }
        ),
        encoding="utf-8",
    )

    configure_zed_acp_settings(settings_path=settings_path)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["theme"] == "One Dark"
    assert "Other" in data["agent_servers"]
    assert "Brokk Code" in data["agent_servers"]


def test_configure_zed_acp_settings_rejects_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
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
        configure_zed_acp_settings(settings_path=settings_path)


def test_configure_zed_acp_settings_force_overwrites_existing_brokk_code(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
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

    configure_zed_acp_settings(settings_path=settings_path, force=True)

    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["agent_servers"]["Brokk Code"]["command"] == "uvx"
    assert data["agent_servers"]["Brokk Code"]["args"] == ["brokk", "acp"]


def test_configure_zed_acp_settings_preserves_existing_permissions(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(json.dumps({"theme": "One Dark"}), encoding="utf-8")
    settings_path.chmod(0o640)
    expected_mode = stat.S_IMODE(settings_path.stat().st_mode)

    configure_zed_acp_settings(settings_path=settings_path)

    mode = stat.S_IMODE(settings_path.stat().st_mode)
    assert mode == expected_mode


def test_configure_zed_acp_settings_parses_jsonc(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        """// Zed settings
//
// Minimal example with comments and trailing commas
{
  "telemetry": {
    "diagnostics": false,
    "metrics": false,
  },
  "theme": {
    "mode": "dark",
    "light": "One Light",
    "dark": "Gruvbox Dark",
  },
}
""",
        encoding="utf-8",
    )

    configure_zed_acp_settings(settings_path=settings_path)

    updated_text = settings_path.read_text(encoding="utf-8")
    assert updated_text.startswith("// Zed settings")

    json_start = updated_text.find("{")
    assert json_start != -1
    data = json.loads(updated_text[json_start:])
    assert data["telemetry"]["diagnostics"] is False
    assert data["theme"]["dark"] == "Gruvbox Dark"
    assert data["agent_servers"]["Brokk Code"]["command"] == "uvx"


def test_configure_zed_acp_settings_default_path_linux(monkeypatch, tmp_path) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    assert written_path == fake_home / ".config" / "zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_darwin(monkeypatch, tmp_path) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "darwin")
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    # macOS now uses XDG-style path for Zed settings
    assert written_path == fake_home / ".config" / "zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_with_appdata(
    monkeypatch, tmp_path
) -> None:
    fake_appdata = tmp_path / "AppData" / "Roaming"
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setenv("APPDATA", str(fake_appdata))
    monkeypatch.setattr(Path, "home", lambda: tmp_path / "home")
    written_path = configure_zed_acp_settings()
    assert written_path == fake_appdata / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_no_appdata(monkeypatch, tmp_path) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.delenv("APPDATA", raising=False)
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    assert written_path == fake_home / "AppData" / "Roaming" / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_blank_appdata(
    monkeypatch, tmp_path
) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setenv("APPDATA", "")
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    # Should fall back to home-based path when APPDATA is blank
    assert written_path == fake_home / "AppData" / "Roaming" / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_whitespace_appdata(
    monkeypatch, tmp_path
) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setenv("APPDATA", "   ")
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    # Should fall back to home-based path when APPDATA is whitespace
    assert written_path == fake_home / "AppData" / "Roaming" / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_relative_appdata(
    monkeypatch, tmp_path
) -> None:
    fake_home = tmp_path / "home"
    monkeypatch.setattr(sys, "platform", "win32")
    # Relative path should be ignored
    monkeypatch.setenv("APPDATA", "Relative/Path")
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    written_path = configure_zed_acp_settings()
    # Should fall back to home-based path when APPDATA is relative
    assert written_path == fake_home / "AppData" / "Roaming" / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_default_path_windows_untrimmed_appdata(
    monkeypatch, tmp_path
) -> None:
    fake_appdata = tmp_path / "AppData" / "Roaming"
    monkeypatch.setattr(sys, "platform", "win32")
    # Path with whitespace should be trimmed and used if absolute
    monkeypatch.setenv("APPDATA", f"  {fake_appdata}  ")
    monkeypatch.setattr(Path, "home", lambda: tmp_path / "home")
    written_path = configure_zed_acp_settings()
    assert written_path == fake_appdata / "Zed" / "settings.json"
    assert written_path.exists()


def test_configure_zed_acp_settings_rust_paths_minimal(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    brokk_acp = Path("/home/u/.brokk/bin/brokk-acp")
    bifrost = Path("/home/u/.brokk/bin/bifrost")
    rust_paths = RustAcpPaths(
        brokk_acp=brokk_acp,
        bifrost=bifrost,
        model="qwen2.5-coder:7b",
    )

    configure_zed_acp_settings(settings_path=settings_path, rust_paths=rust_paths)

    entry = json.loads(settings_path.read_text(encoding="utf-8"))["agent_servers"]["Brokk Code"]
    assert entry["command"] == str(brokk_acp)
    assert entry["args"] == [
        "--default-model",
        "qwen2.5-coder:7b",
        "--bifrost-binary",
        str(bifrost),
    ]
    assert entry["favorite_config_option_values"]["model"] == ["qwen2.5-coder:7b"]


def test_configure_zed_acp_settings_rust_paths_with_custom_endpoint(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    brokk_acp = Path("/opt/brokk-acp")
    bifrost = Path("/opt/bifrost")
    rust_paths = RustAcpPaths(
        brokk_acp=brokk_acp,
        bifrost=bifrost,
        model="claude-haiku-4-5",
        endpoint_url="http://example.invalid:8080",
        api_key="sk-test-123",
    )

    configure_zed_acp_settings(settings_path=settings_path, rust_paths=rust_paths)

    args = json.loads(settings_path.read_text(encoding="utf-8"))["agent_servers"]["Brokk Code"][
        "args"
    ]
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
