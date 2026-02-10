import json
import logging
from dataclasses import asdict, dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

SETTINGS_DIR = Path.home() / ".brokk"
SETTINGS_FILE = SETTINGS_DIR / "settings.json"


@dataclass
class Settings:
    theme: str = "builtin:dark"

    @classmethod
    def load(cls) -> "Settings":
        """Loads settings from disk, returning defaults if file is missing or corrupt."""
        if not SETTINGS_FILE.exists():
            return cls()

        try:
            with SETTINGS_FILE.open("r", encoding="utf-8") as f:
                data = json.load(f)
                return cls(**data)
        except Exception as e:
            logger.warning("Failed to load settings from %s: %s. Using defaults.", SETTINGS_FILE, e)
            return cls()

    def save(self) -> None:
        """Saves current settings to disk atomically."""
        try:
            SETTINGS_DIR.mkdir(parents=True, exist_ok=True)
            temp_file = SETTINGS_FILE.with_suffix(".tmp")
            with temp_file.open("w", encoding="utf-8") as f:
                json.dump(asdict(self), f, indent=4)
            temp_file.replace(SETTINGS_FILE)
        except Exception as e:
            logger.error("Failed to save settings to %s: %s", SETTINGS_FILE, e)
