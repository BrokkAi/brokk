import json
import logging
import zipfile
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

MAX_CONTEXTS_JSONL_BYTES = 1024 * 1024  # 1MB
MAX_CONTEXTS_JSONL_LINES = 1000


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


def _is_task_line(line_bytes: bytes) -> bool:
    """Return True if this JSONL line contains at least one qualifying task entry.

    Parsing rules:
    - Ignore blank lines and malformed JSON.
    - Require a 'tasks' list.
    - Within tasks, require a dict that has at least one of the meta fields:
      taskType, primaryModelName, primaryModelReasoning, AND a numeric 'sequence'.
    """
    line = line_bytes.strip()
    if not line:
        return False
    try:
        context_data = json.loads(line)
        tasks = context_data.get("tasks")
        if not isinstance(tasks, list):
            return False

        for task in tasks:
            if not isinstance(task, dict):
                continue

            has_meta = any(
                task.get(k) is not None
                for k in [
                    "taskType",
                    "primaryModelName",
                    "primaryModelReasoning",
                ]
            )
            sequence = task.get("sequence")
            if has_meta and isinstance(sequence, (int, float)):
                return True
    except (json.JSONDecodeError, TypeError):
        # Tolerate malformed JSON and type issues
        return False
    return False


def has_tasks(zip_path: Path) -> bool:
    """
    Checks if the session zip contains any history tasks in contexts.jsonl.
    A task counts if it has meta fields (taskType, primaryModelName, or primaryModelReasoning)
    and a valid sequence number.
    Tolerant of missing or corrupt zips; returns False in those cases.
    """
    if not zip_path.exists():
        return False

    try:
        with zipfile.ZipFile(zip_path, "r") as z:
            try:
                info = z.getinfo("contexts.jsonl")
            except KeyError:
                return False

            bytes_read = 0
            lines_read = 0
            remainder = b""

            with z.open(info) as f:
                while (
                    bytes_read < MAX_CONTEXTS_JSONL_BYTES and lines_read < MAX_CONTEXTS_JSONL_LINES
                ):
                    chunk_size = min(4096, MAX_CONTEXTS_JSONL_BYTES - bytes_read)
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    bytes_read += len(chunk)

                    lines = (remainder + chunk).splitlines(keepends=True)
                    # If the chunk didn't end with a newline, keep the last partial line
                    # for next chunk
                    if lines and not lines[-1].endswith((b"\r", b"\n")):
                        remainder = lines.pop()
                    else:
                        remainder = b""

                    for line_bytes in lines:
                        if lines_read >= MAX_CONTEXTS_JSONL_LINES:
                            break
                        lines_read += 1
                        if _is_task_line(line_bytes):
                            return True

                # After finishing chunk reads, try parsing a final partial line
                # if it exists and we have not yet hit the line limit.
                if remainder and lines_read < MAX_CONTEXTS_JSONL_LINES:
                    if _is_task_line(remainder):
                        return True
    except (zipfile.BadZipFile, OSError) as e:
        logger.debug("Failed to inspect session zip %s: %s", zip_path, e)

    return False


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
