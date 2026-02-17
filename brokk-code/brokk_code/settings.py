import json
import logging
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

DEFAULT_THEME = "textual-dark"
DEFAULT_PROMPT_HISTORY_SIZE = 50
_LEGACY_THEME_ALIASES = {
    "builtin:dark": "textual-dark",
    "builtin:light": "textual-light",
    "dark": "textual-dark",
    "light": "textual-light",
    "brokk-dark": "textual-dark",
    "brokk-light": "textual-light",
}


def normalize_theme_name(theme: str) -> str:
    return _LEGACY_THEME_ALIASES.get(theme, theme)


def settings_dir() -> Path:
    return Path.home() / ".brokk"


def settings_file() -> Path:
    return settings_dir() / "settings.json"


@dataclass
class Settings:
    theme: str = DEFAULT_THEME
    prompt_history_size: int = DEFAULT_PROMPT_HISTORY_SIZE

    # New optional fields for remembering last used models and reasoning settings.
    last_model: Optional[str] = None
    last_code_model: Optional[str] = None
    last_reasoning_level: Optional[str] = None
    last_code_reasoning_level: Optional[str] = None
    last_auto_commit: Optional[bool] = None

    @classmethod
    def load(cls) -> "Settings":
        """Loads settings from disk, returning defaults if file is missing or corrupt.

        This is backward-compatible with older settings.json files that omit the new
        model/reasoning keys: the dataclass defaults (None) are used in that case.
        """
        settings_path = settings_file()
        if not settings_path.exists():
            return cls()

        try:
            with settings_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                # Only pass known fields to the dataclass to avoid issues if the file
                # contains unexpected keys. Build kwargs from fields present in data.
                # We rely on dataclass defaults for any missing new fields.
                valid_keys = {field.name for field in cls.__dataclass_fields__.values()}
                filtered = {k: v for k, v in (data or {}).items() if k in valid_keys}
                settings = cls(**filtered)
                settings.theme = normalize_theme_name(settings.theme)
                return settings
        except Exception as e:
            logger.warning("Failed to load settings from %s: %s. Using defaults.", settings_path, e)
            return cls()

    def save(self) -> None:
        """Saves current settings to disk atomically."""
        try:
            settings_path = settings_file()
            settings_path.parent.mkdir(parents=True, exist_ok=True)
            temp_file = settings_path.with_suffix(".tmp")
            with temp_file.open("w", encoding="utf-8") as f:
                json.dump(asdict(self), f, indent=4)
            temp_file.replace(settings_path)
        except Exception as e:
            logger.error("Failed to save settings to %s: %s", settings_file(), e)
