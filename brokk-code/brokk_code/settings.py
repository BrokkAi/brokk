import json
import logging
import os
import sys
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


def get_global_config_dir() -> Path:
    """Returns the platform-appropriate global configuration directory for Brokk.
    Mirrors ai.brokk.util.BrokkConfigPaths.java.
    """
    home = Path.home()
    if sys.platform == "win32":
        app_data = os.getenv("APPDATA")
        base = Path(app_data) if app_data else home / "AppData" / "Roaming"
    elif sys.platform == "darwin":
        base = home / "Library" / "Application Support"
    else:
        xdg = os.getenv("XDG_CONFIG_HOME")
        base = Path(xdg) if xdg else home / ".config"
    return base / "Brokk"


def settings_dir() -> Path:
    """Returns the legacy .brokk directory in home for settings.json and prompt history."""
    return Path.home() / ".brokk"


def settings_file() -> Path:
    return settings_dir() / "settings.json"


def get_brokk_properties_path() -> Path:
    return get_global_config_dir() / "brokk.properties"


def read_brokk_properties() -> dict[str, str]:
    """Reads brokk.properties into a dictionary."""
    props_path = get_brokk_properties_path()
    if not props_path.exists():
        return {}

    props = {}
    try:
        with props_path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith(("#", "!")):
                    continue
                if "=" in line:
                    key, value = line.split("=", 1)
                    props[key.strip()] = value.strip()
    except Exception as e:
        logger.warning("Failed to read brokk.properties: %s", e)
    return props


def is_openai_codex_connected() -> bool:
    """Checks if OpenAI Codex OAuth is connected via brokk.properties."""
    props = read_brokk_properties()
    return props.get("openAiCodexOauthConnected", "false").lower() == "true"


def write_brokk_api_key(key: str) -> None:
    """Persists the Brokk API key to the global brokk.properties file."""
    write_brokk_properties({"brokkApiKey": key})


def write_brokk_properties(updates: dict[str, Optional[str]]) -> None:
    """Updates brokk.properties while preserving other keys and comments.
    If a value is None or empty, the key is removed.
    """
    props_path = get_brokk_properties_path()
    lines = []
    keys_seen = set()

    if props_path.exists():
        try:
            with props_path.open("r", encoding="utf-8") as f:
                lines = f.readlines()
        except Exception as e:
            logger.warning("Failed to read existing brokk.properties for update: %s", e)

    new_lines = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith(("#", "!")) and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key in updates:
                keys_seen.add(key)
                val = updates[key]
                if val and val.strip():
                    new_lines.append(f"{key}={val.strip()}\n")
                continue  # If None/empty, we skip it (effectively removing)
        new_lines.append(line)

    # Append new keys that weren't in the file
    for key, val in updates.items():
        if key not in keys_seen and val and val.strip():
            if new_lines and not new_lines[-1].endswith("\n"):
                new_lines.append("\n")
            new_lines.append(f"{key}={val.strip()}\n")

    try:
        props_path.parent.mkdir(parents=True, exist_ok=True)
        # Simple atomic write
        temp_file = props_path.with_suffix(".tmp")
        with temp_file.open("w", encoding="utf-8") as f:
            f.writelines(new_lines)
        temp_file.replace(props_path)
    except Exception as e:
        logger.error("Failed to write brokk.properties: %s", e)
        raise


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

    def get_brokk_api_key(self) -> Optional[str]:
        """Returns the Brokk API key.
        Order:
        1. brokk.properties (brokkApiKey)
        2. BROKK_API_KEY environment variable
        """
        # 1. brokk.properties
        props = read_brokk_properties()
        prop_key = props.get("brokkApiKey")
        if prop_key and prop_key.strip():
            return prop_key.strip()

        # 2. Environment variable
        env_key = os.getenv("BROKK_API_KEY")
        if env_key and env_key.strip():
            return env_key.strip()

        return None

    def save(self) -> None:
        """Saves current settings to disk atomically.

        Raises:
            OSError: If the settings file cannot be written.
        """
        try:
            settings_path = settings_file()
            settings_path.parent.mkdir(parents=True, exist_ok=True)
            temp_file = settings_path.with_suffix(".tmp")
            with temp_file.open("w", encoding="utf-8") as f:
                json.dump(asdict(self), f, indent=4)
            temp_file.replace(settings_path)
        except Exception as e:
            logger.error("Failed to save settings to %s: %s", settings_file(), e)
            raise
