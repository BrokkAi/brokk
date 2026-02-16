import json
import logging
import re
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

# Simple UUID filename matcher (matches canonical UUIDs)
_UUID_FILENAME_RE = re.compile(r"^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\.zip$")


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


def list_session_zips(workspace_dir: Path) -> List[Dict]:
    """
    Enumerate session zip files under .brokk/sessions.

    Returns a list of dicts with keys:
      - session_id: str
      - zip_path: Path
      - modified_time: float (seconds since epoch)
      - size: int (bytes)

    Only files named like '<uuid>.zip' are considered. Non-matching files are ignored.
    The returned list is sorted by modified_time descending (newest first).
    """
    sessions_dir = get_state_dir(workspace_dir) / "sessions"
    if not sessions_dir.exists() or not sessions_dir.is_dir():
        return []

    entries = []
    try:
        for p in sessions_dir.iterdir():
            if not p.is_file():
                continue
            m = _UUID_FILENAME_RE.match(p.name)
            if not m:
                # ignore files not matching the expected <uuid>.zip pattern
                continue
            session_id = m.group(1)
            try:
                stat = p.stat()
                entries.append(
                    {
                        "session_id": session_id,
                        "zip_path": p,
                        "modified_time": stat.st_mtime,
                        "size": stat.st_size,
                    }
                )
            except Exception as e:
                logger.debug("Failed to stat session zip %s: %s", p, e)
                # skip unreadable entries
                continue
    except Exception as e:
        logger.error("Failed to list sessions in %s: %s", sessions_dir, e)
        return []

    # newest first
    entries.sort(key=lambda e: e["modified_time"], reverse=True)
    return entries


def recover_last_session_if_present(workspace_dir: Path) -> Optional[str]:
    """
    Convenience helper: if last_session.json points to a session that still exists on disk,
    return that session id. Otherwise return None.

    Does not modify last_session.json.
    """
    last = load_last_session_id(workspace_dir)
    if not last:
        return None
    zip_path = get_session_zip_path(workspace_dir, last)
    return last if zip_path.exists() else None
