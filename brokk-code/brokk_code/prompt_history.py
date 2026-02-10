import json
import logging
from pathlib import Path
from typing import List

logger = logging.getLogger(__name__)

DEFAULT_MAX_HISTORY = 100


def get_history_file(workspace_dir: Path) -> Path:
    """Returns the path to the prompt history file in the workspace."""
    return workspace_dir / ".brokk" / "prompts.json"


def append_prompt(workspace_dir: Path, prompt: str, max_history: int = DEFAULT_MAX_HISTORY) -> None:
    """Appends a prompt to the history and trims to the last N entries."""
    if not prompt:
        return
    history = load_history(workspace_dir)
    history.append(prompt)
    if len(history) > max_history:
        history = history[-max_history:]
    save_history(workspace_dir, history)


def load_history(workspace_dir: Path) -> List[str]:
    """Loads the prompt history from the workspace."""
    history_file = get_history_file(workspace_dir)
    if not history_file.exists():
        return []

    try:
        with history_file.open("r", encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, list):
                return [str(item) for item in data]
            return []
    except Exception as e:
        logger.warning(
            "Failed to load prompt history from %s: %s. Starting fresh.", history_file, e
        )
        return []


def save_history(workspace_dir: Path, history: List[str]) -> None:
    """Saves the prompt history to the workspace atomically."""
    history_file = get_history_file(workspace_dir)
    try:
        history_file.parent.mkdir(parents=True, exist_ok=True)
        temp_file = history_file.with_suffix(".tmp")
        with temp_file.open("w", encoding="utf-8") as f:
            json.dump(history, f, indent=4)
        temp_file.replace(history_file)
    except Exception as e:
        logger.error("Failed to save prompt history to %s: %s", history_file, e)


def clear_history(workspace_dir: Path) -> None:
    """Deletes the prompt history file."""
    history_file = get_history_file(workspace_dir)
    try:
        if history_file.exists():
            history_file.unlink()
    except Exception as e:
        logger.error("Failed to clear prompt history at %s: %s", history_file, e)
