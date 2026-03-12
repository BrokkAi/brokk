import json
from pathlib import Path

from brokk_code.settings import Settings


def test_settings_default_load(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    # File doesn't exist
    settings = Settings.load()
    assert settings.theme == "textual-dark"
    assert settings.prompt_history_size == 50
    # New fields default to None
    assert settings.last_model is None
    assert settings.last_code_model is None
    assert settings.last_reasoning_level is None
    assert settings.last_code_reasoning_level is None
    assert settings.last_auto_commit is None


def test_settings_prompt_history_persistence(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings = Settings(prompt_history_size=100)
    settings.save()

    loaded = Settings.load()
    assert loaded.prompt_history_size == 100


def test_settings_save_and_load(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings = Settings(theme="textual-light")
    settings.save()

    # Verify file exists where expected
    settings_file = tmp_path / ".brokk" / "settings.json"
    assert settings_file.exists()

    # Load back
    loaded = Settings.load()
    assert loaded.theme == "textual-light"


def test_settings_malformed_json_fallback(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings_dir = tmp_path / ".brokk"
    settings_dir.mkdir()
    settings_file = settings_dir / "settings.json"
    settings_file.write_text("not valid json")

    settings = Settings.load()
    assert settings.theme == "textual-dark"


def test_settings_atomic_save(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings = Settings(theme="textual-light")
    settings.save()

    settings_file = tmp_path / ".brokk" / "settings.json"
    with settings_file.open("r") as f:
        data = json.load(f)
    assert data["theme"] == "textual-light"


def test_settings_legacy_theme_aliases(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings_dir = tmp_path / ".brokk"
    settings_dir.mkdir()
    settings_file = settings_dir / "settings.json"
    settings_file.write_text('{"theme":"builtin:dark"}')

    settings = Settings.load()
    assert settings.theme == "textual-dark"


def test_settings_models_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings = Settings(
        theme="textual-light",
        prompt_history_size=25,
        last_model="gpt-5.2",
        last_code_model="gemini-3-flash-preview",
        last_reasoning_level="medium",
        last_code_reasoning_level="disable",
        last_auto_commit=False,
    )
    settings.save()

    loaded = Settings.load()
    assert loaded.theme == "textual-light"
    assert loaded.prompt_history_size == 25
    assert loaded.last_model == "gpt-5.2"
    assert loaded.last_code_model == "gemini-3-flash-preview"
    assert loaded.last_reasoning_level == "medium"
    assert loaded.last_code_reasoning_level == "disable"
    assert loaded.last_auto_commit is False


def test_settings_load_from_older_json_without_new_keys(tmp_path, monkeypatch):
    """
    Simulate an older settings.json that only contains legacy keys
    (theme/prompt_history_size).
    """
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings_dir = tmp_path / ".brokk"
    settings_dir.mkdir()
    settings_file = settings_dir / "settings.json"
    settings_file.write_text('{"theme":"textual-dark","prompt_history_size":42}')

    loaded = Settings.load()
    assert loaded.theme == "textual-dark"
    assert loaded.prompt_history_size == 42
    # New fields should be present and default to None
    assert loaded.last_model is None
    assert loaded.last_code_model is None
    assert loaded.last_reasoning_level is None
    assert loaded.last_code_reasoning_level is None
    assert loaded.last_auto_commit is None


def test_app_initializes_with_defaults_when_settings_empty(tmp_path, monkeypatch):
    """Verify BrokkApp uses hardcoded fallbacks when settings fields are None or blank."""
    from unittest.mock import MagicMock

    from brokk_code.app import BrokkApp

    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # 1. Test None/Missing
    app = BrokkApp(executor=MagicMock())
    assert app.current_model == "gpt-5.2"
    assert app.reasoning_level == "low"

    # 2. Test Blank strings in settings
    settings_dir = tmp_path / ".brokk"
    settings_dir.mkdir(exist_ok=True)
    settings_file = settings_dir / "settings.json"
    settings_file.write_text('{"last_model": "  ", "last_reasoning_level": ""}')

    app_blank = BrokkApp(executor=MagicMock())
    assert app_blank.current_model == "gpt-5.2"
    assert app_blank.reasoning_level == "low"


def test_settings_save_raises_on_failure(tmp_path, monkeypatch):
    """Verify Settings.save() propagates exceptions."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    settings = Settings()

    def fail_replace(self, target):
        raise OSError("simulated save failure")

    monkeypatch.setattr(Path, "replace", fail_replace)

    import pytest

    with pytest.raises(OSError, match="simulated save failure"):
        settings.save()


def test_get_global_config_dir_platforms(tmp_path, monkeypatch):
    from brokk_code.settings import get_global_config_dir

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setenv("APPDATA", str(tmp_path / "AppData"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))

    import sys

    # Test Linux/default logic
    monkeypatch.setattr(sys, "platform", "linux")
    assert get_global_config_dir() == tmp_path / "xdg" / "Brokk"

    monkeypatch.delenv("XDG_CONFIG_HOME")
    assert get_global_config_dir() == tmp_path / ".config" / "Brokk"

    # Test Mac
    monkeypatch.setattr(sys, "platform", "darwin")
    assert get_global_config_dir() == tmp_path / "Library" / "Application Support" / "Brokk"

    # Test Windows
    monkeypatch.setattr(sys, "platform", "win32")
    assert get_global_config_dir() == tmp_path / "AppData" / "Brokk"


def test_brokk_properties_read_write(tmp_path, monkeypatch):
    from brokk_code.settings import (
        get_brokk_properties_path,
        read_brokk_properties,
        write_brokk_properties,
    )

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    # Ensure a clean slate for Linux-style path
    import sys

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)

    props_path = get_brokk_properties_path()

    # Write initial props
    props_path.parent.mkdir(parents=True)
    props_path.write_text("# Comment\nfoo=bar\nbrokkApiKey=old-key\n")

    assert read_brokk_properties() == {"foo": "bar", "brokkApiKey": "old-key"}

    # Update key and remove another
    write_brokk_properties({"brokkApiKey": "new-key", "foo": None, "newKey": "val"})

    updated = read_brokk_properties()
    assert updated == {"brokkApiKey": "new-key", "newKey": "val"}
    assert "foo" not in updated

    # Verify formatting/comments preservation (best effort)
    content = props_path.read_text()
    assert "# Comment" in content
    assert "brokkApiKey=new-key" in content
    assert "newKey=val" in content


def test_settings_api_key_ordering(tmp_path, monkeypatch):
    from brokk_code.settings import get_brokk_properties_path

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    import sys

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    monkeypatch.delenv("BROKK_API_KEY", raising=False)

    settings = Settings()

    # Initially, nothing is configured
    assert settings.get_brokk_api_key() is None

    # Env var is used when properties file is missing
    monkeypatch.setenv("BROKK_API_KEY", "env-key")
    assert settings.get_brokk_api_key() == "env-key"

    # brokk.properties takes precedence over env var
    props_path = get_brokk_properties_path()
    props_path.parent.mkdir(parents=True, exist_ok=True)
    props_path.write_text("brokkApiKey=properties-key\n")

    assert settings.get_brokk_api_key() == "properties-key"


def test_get_github_token(tmp_path, monkeypatch):
    from brokk_code.settings import get_brokk_properties_path

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    import sys

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    settings = Settings()

    assert settings.get_github_token() is None

    monkeypatch.setenv("GITHUB_TOKEN", "env-gh-token")
    assert settings.get_github_token() == "env-gh-token"

    props_path = get_brokk_properties_path()
    props_path.parent.mkdir(parents=True, exist_ok=True)
    props_path.write_text("githubToken=props-gh-token\n")

    assert settings.get_github_token() == "props-gh-token"


def test_settings_save_to_properties(tmp_path, monkeypatch):
    from brokk_code.settings import read_brokk_properties, write_brokk_api_key

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    import sys

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))

    write_brokk_api_key("sync-test-key")

    # Verify brokk.properties has it
    assert read_brokk_properties().get("brokkApiKey") == "sync-test-key"
