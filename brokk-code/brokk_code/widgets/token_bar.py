from __future__ import annotations

from typing import Optional

from textual.widgets import Static
from rich.text import Text


class TokenBar(Static):
    """A compact horizontal token usage bar.

    Public API:
      - set_usage(used: int, max_tokens: Optional[int] = None)

    Rendering:
      - When used <= 0: shows a concise "No context" message.
      - When used > 0 and max_tokens provided: shows a proportional bar and a compact
        token count (e.g. 950, 1.2K, 1.0M).
      - When used > 0 and max_tokens is None: shows a simple token count.

    Implementation notes:
      - Uses a fixed character-width bar (20 cells) to keep layout stable in the TUI.
      - Colors are simple and rely on the widget CSS for overall color; inline
        color names (green/yellow/red/grey) are used for the filled portion so the
        bar remains legible across themes.
    """

    DEFAULT_CSS = """
    TokenBar {
        margin: 1 2 0 2;
        width: 1fr;
        height: 1;
        padding: 0 1;
        background: $panel;
        color: $text-disabled;
    }
    """

    def __init__(self, *, id: Optional[str] = None) -> None:
        super().__init__("", id=id)
        self._used: int = 0
        self._max: Optional[int] = None

    def set_usage(self, used: int, max_tokens: Optional[int] = None) -> None:
        """Public setter to update usage. Use non-negative used; max_tokens may be None."""
        try:
            self._used = max(0, int(used or 0))
        except Exception:
            self._used = 0
        if max_tokens is None:
            self._max = None
        else:
            try:
                self._max = max(1, int(max_tokens))
            except Exception:
                self._max = None
        self.update(self._render())

    def _render(self) -> Text:
        if self._used <= 0:
            t = Text("No context yet", style="dim")
            return t

        label = self._format_tokens(self._used)

        if self._max is not None and self._max > 0:
            effective_max = max(self._max, self._used)
            ratio = max(0.0, min(1.0, self._used / float(effective_max)))
            bar_len = 20
            filled = int(round(bar_len * ratio))
            empty = max(0, bar_len - filled)

            if ratio >= 1.0:
                fill_color = "red"
            elif ratio >= 0.75:
                fill_color = "yellow"
            else:
                fill_color = "green"

            filled_part = "#" * filled
            empty_part = "-" * empty

            text = Text.assemble(
                ("[", "dim"),
                (filled_part, f"bold {fill_color}"),
                (empty_part, "dim"),
                ("] ", "dim"),
                (f"{label}", "bold"),
                (" / ", "dim"),
                (f"{self._max:,}", "dim"),
            )
            return text

        return Text(f"Tokens: {label}", style="bold")

    @staticmethod
    def _format_tokens(tokens: int) -> str:
        if tokens < 1000:
            return f"{tokens:,}"
        if tokens < 1_000_000:
            return f"{tokens/1000.0:.1f}K"
        return f"{tokens/1_000_000.0:.1f}M"
