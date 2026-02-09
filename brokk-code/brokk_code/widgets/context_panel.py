from typing import Any, Dict, List

from rich.text import Text
from textual.app import ComposeResult
from textual.containers import Vertical
from textual.widgets import Label, ListItem, ListView


class ContextFragmentItem(ListItem):
    """A single fragment entry in the list."""

    def __init__(self, fragment: Dict[str, Any]) -> None:
        super().__init__()
        self.fragment = fragment

    def compose(self) -> ComposeResult:
        chip_kind = self.fragment.get("chip_kind", self.fragment.get("chipKind", "OTHER"))
        description = self.fragment.get("shortDescription", "Unknown")
        pinned = " 📌" if self.fragment.get("pinned") else ""
        tokens = self.fragment.get("tokens", 0)

        # Color mapping based on chipKind
        color = {
            "EDIT": "green",
            "SUMMARY": "blue",
            "HISTORY": "yellow",
            "INVALID": "red",
            "TASK_LIST": "cyan",
        }.get(chip_kind, "default")

        text = Text()
        text.append(f"[{chip_kind}] ", style=f"bold {color}")
        text.append(description)
        if pinned:
            text.append(pinned, style="bold yellow")

        yield Label(text)
        if tokens > 0:
            yield Label(f"  {tokens:,} tokens", variant="dim")


class ContextPanel(Vertical):
    """Displays current context fragments and token usage."""

    def compose(self) -> ComposeResult:
        yield Label("Context (0 tokens)", id="context-header", variant="dim")
        yield ListView(id="context-list")

    def refresh_context(self, context_data: Dict[str, Any]) -> None:
        """Updates the panel with new context data."""
        fragments: List[Dict[str, Any]] = context_data.get("fragments", [])
        used = context_data.get("usedTokens", 0)
        max_tokens = context_data.get("maxTokens", 200_000)

        # Update Header
        header = self.query_one("#context-header", Label)
        header.update(f"Context ({used:,} / {max_tokens:,} tokens)")

        # Update List
        list_view = self.query_one("#context-list", ListView)
        list_view.clear()
        for frag in fragments:
            list_view.append(ContextFragmentItem(frag))
