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
    assert settings.brokk_api_key is None


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


def test_settings_api_key_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    settings = Settings(brokk_api_key="sk-test-key-123")
    settings.save()

    loaded = Settings.load()
    assert loaded.brokk_api_key == "sk-test-key-123"


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
    assert loaded.brokk_api_key is None


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
