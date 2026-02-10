import json
from pathlib import Path

from brokk_code.settings import Settings


def test_settings_default_load(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    # File doesn't exist
    settings = Settings.load()
    assert settings.theme == "textual-dark"


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
