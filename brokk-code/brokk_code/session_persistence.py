import json
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def get_state_dir(workspace_dir: Path) -> Path:
    """Returns the workspace-local state directory."""
    return workspace_dir / ".brokk"


def get_last_session_file(workspace_dir: Path) -> Path:
    """Returns the path to the last session metadata file."""
    return get_state_dir(workspace_dir) / "last_session.json"


def get_session_zip_path(workspace_dir: Path, session_id: str) -> Path:
    """Returns the path for a session ZIP file and ensures parent directories exist."""
    sessions_dir = get_state_dir(workspace_dir) / "sessions"
    sessions_dir.mkdir(parents=True, exist_ok=True)
    return sessions_dir / f"{session_id}.zip"


def save_last_session_id(workspace_dir: Path, session_id: str) -> None:
    """Saves the last used session ID to the workspace."""
    file_path = get_last_session_file(workspace_dir)
    try:
        file_path.parent.mkdir(parents=True, exist_ok=True)
        temp_file = file_path.with_suffix(".tmp")
        with temp_file.open("w", encoding="utf-8") as f:
            json.dump({"sessionId": session_id}, f, indent=4)
        temp_file.replace(file_path)
    except Exception as e:
        logger.error("Failed to save last session ID to %s: %s", file_path, e)


def load_last_session_id(workspace_dir: Path) -> Optional[str]:
    """Loads the last used session ID from the workspace. Returns None if missing/corrupt."""
    file_path = get_last_session_file(workspace_dir)
    if not file_path.exists():
        return None

    try:
        with file_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, dict):
                return data.get("sessionId")
    except Exception as e:
        logger.warning("Failed to load last session ID from %s: %s. Ignoring.", file_path, e)

    return None
