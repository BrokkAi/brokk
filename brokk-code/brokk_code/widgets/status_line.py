from __future__ import annotations

from pathlib import Path

from textual.widgets import Static


class StatusLine(Static):
    """Compact single-line status bar for mode, model, reasoning, and workspace dir."""

    def update_status(
        self,
        *,
        mode: str,
        model: str,
        reasoning: str = "",
        directory: str,
    ) -> None:
        """Update the rendered status text.

        Parameters
        ----------
        mode:
            Current agent mode label (e.g. "LUTZ", "ASK", "SEARCH").
        model:
            Current planner / chat model identifier.
        reasoning:
            Current reasoning level; empty/whitespace will be rendered as "-".
        directory:
            Workspace directory path as a string. May be trimmed for display.
        """
        mode_display = mode.strip() or "?"
        model_display = model.strip() or "?"
        reasoning_display = reasoning.strip() or "-"
        directory_display = self._shorten_dir(directory)

        line = (
            f"Mode: {mode_display}"
            f" | Model: {model_display}"
            f" | Reasoning: {reasoning_display}"
            f" | Dir: {directory_display}"
        )
        self.update(line)

    def _shorten_dir(self, directory: str) -> str:
        """Return a compact representation of a directory string.

        Keeps the last ~40 characters by default, prefixing with "..." if truncated.
        """
        raw = directory.strip()
        if not raw:
            return "-"

        try:
            path_str = str(Path(raw))
        except Exception:
            path_str = raw

        max_len = 40
        if len(path_str) <= max_len:
            return path_str
        return "..." + path_str[-max_len:]
