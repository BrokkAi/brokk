import json
import stat

import pytest

from brokk_code.zed_config import ExistingBrokkCodeEntryError, configure_zed_acp_settings


def test_configure_zed_acp_settings_creates_file(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"

    written_path = configure_zed_acp_settings(settings_path=settings_path)

    assert written_path == settings_path
    data = json.loads(settings_path.read_text(encoding="utf-8"))
    assert data["agent_servers"]["Brokk Code"]["command"] == "brokk-code"
    assert data["agent_servers"]["Brokk Code"]["args"] == ["acp", "--ide", "zed"]


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
    assert data["agent_servers"]["Brokk Code"]["command"] == "brokk-code"
    assert data["agent_servers"]["Brokk Code"]["args"] == ["acp", "--ide", "zed"]


def test_configure_zed_acp_settings_preserves_existing_permissions(tmp_path) -> None:
    settings_path = tmp_path / ".config" / "zed" / "settings.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(json.dumps({"theme": "One Dark"}), encoding="utf-8")
    settings_path.chmod(0o640)
    expected_mode = stat.S_IMODE(settings_path.stat().st_mode)

    configure_zed_acp_settings(settings_path=settings_path)

    mode = stat.S_IMODE(settings_path.stat().st_mode)
    assert mode == expected_mode
