from typing import Any, Dict, List

from rich.text import Text
from textual.app import ComposeResult
from textual.containers import Horizontal, HorizontalScroll, Vertical
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

        kind_class = f"kind-{str(chip_kind).lower().replace('_', '-') }"
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
    """Horizontal chip panel mirroring the Java workspace chip strip."""

    def compose(self) -> ComposeResult:
        with Horizontal(id="context-header"):
            yield Label("Context", id="context-title")
            yield Label("0 / 200,000 tokens", id="context-token-usage")
        with HorizontalScroll(id="context-chip-scroll"):
            yield Horizontal(id="context-chip-row")

    def refresh_context(self, context_data: Dict[str, Any]) -> None:
        """Updates token usage and fragment chips from /v1/context."""
        fragments: List[Dict[str, Any]] = context_data.get("fragments", [])
        used = context_data.get("usedTokens", 0)
        max_tokens = context_data.get("maxTokens", 200_000)

        token_label = self.query_one("#context-token-usage", Label)
        token_label.update(f"{used:,} / {max_tokens:,} tokens")

        chip_row = self.query_one("#context-chip-row", Horizontal)
        chip_row.remove_children()

        if not fragments:
            chip_row.mount(
                Static("No context fragments", classes="context-chip context-chip-empty")
            )
            return

        for fragment in fragments:
            chip_row.mount(ContextFragmentItem(fragment))
