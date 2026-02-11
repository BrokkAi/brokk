from typing import Any, Dict, List

from rich.text import Text
from textual import events
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Label, Static


class ContextFragmentItem(Static):
    """A compact chip-like widget representing a single context fragment."""

    def __init__(self, fragment: Dict[str, Any]) -> None:
        super().__init__(classes="context-chip")
        self.fragment = fragment

    def on_mount(self) -> None:
        chip_kind = self.fragment.get("chip_kind", self.fragment.get("chipKind", "OTHER"))
        description = self.fragment.get("shortDescription", "Unknown")
        tokens = self.fragment.get("tokens", 0)

        kind_class = f"kind-{str(chip_kind).lower().replace('_', '-')}"
        self.add_class(kind_class)
        if self.fragment.get("pinned"):
            self.add_class("is-pinned")

        text = Text()
        text.append(f"{chip_kind} ", style="bold")
        text.append(description)
        if tokens > 0:
            text.append(f"  {tokens:,}t", style="dim")
        if self.fragment.get("pinned"):
            text.append("  PIN", style="bold")

        self.update(text)


class ContextPanel(Vertical):
    """Context chip panel with width-aware wrapping."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._fragments: List[Dict[str, Any]] = []

    def compose(self) -> ComposeResult:
        with Horizontal(id="context-header"):
            yield Label("Context", id="context-title")
            yield Label("0 / 200,000 tokens", id="context-token-usage")
        with VerticalScroll(id="context-chip-scroll"):
            yield Vertical(id="context-chip-wrap")

    def refresh_context(self, context_data: Dict[str, Any]) -> None:
        """Updates token usage and fragment chips from /v1/context."""
        fragments: List[Dict[str, Any]] = context_data.get("fragments", [])
        self._fragments = fragments
        used = context_data.get("usedTokens", 0)
        max_tokens = context_data.get("maxTokens", 200_000)

        token_label = self.query_one("#context-token-usage", Label)
        token_label.update(f"{used:,} / {max_tokens:,} tokens")
        self._render_fragments()

    def on_resize(self, event: events.Resize) -> None:
        if self._fragments:
            self._render_fragments()

    def _render_fragments(self) -> None:
        chip_wrap = self.query_one("#context-chip-wrap", Vertical)
        chip_wrap.remove_children()

        if not self._fragments:
            chip_wrap.mount(
                Static("No context fragments", classes="context-chip context-chip-empty")
            )
            return

        max_width = self._chip_wrap_width()
        rows: List[List[Dict[str, Any]]] = []
        current_row: List[Dict[str, Any]] = []
        current_width = 0

        for fragment in self._fragments:
            chip_width = self._estimate_chip_width(fragment)
            # Account for the right margin in .context-chip
            chip_total = chip_width + 1
            if current_row and (current_width + chip_total) > max_width:
                rows.append(current_row)
                current_row = [fragment]
                current_width = chip_total
            else:
                current_row.append(fragment)
                current_width += chip_total

        if current_row:
            rows.append(current_row)

        for row_fragments in rows:
            row = Horizontal(
                *(ContextFragmentItem(fragment) for fragment in row_fragments),
                classes="context-chip-row",
            )
            chip_wrap.mount(row)

    def _chip_wrap_width(self) -> int:
        width = self.query_one("#context-chip-scroll", VerticalScroll).size.width
        if width <= 0:
            width = self.size.width
        return max(20, width - 2)

    @staticmethod
    def _estimate_chip_width(fragment: Dict[str, Any]) -> int:
        chip_kind = str(fragment.get("chip_kind", fragment.get("chipKind", "OTHER")))
        description = str(fragment.get("shortDescription", "Unknown"))
        text = f"{chip_kind} {description}"

        tokens = fragment.get("tokens", 0)
        if isinstance(tokens, int) and tokens > 0:
            text += f"  {tokens:,}t"
        if fragment.get("pinned"):
            text += "  PIN"

        # Account for left/right padding and rounded border.
        return len(text) + 4
