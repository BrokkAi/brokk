from typing import Any, Dict, List, Set

from rich.text import Text
from textual import events
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.message import Message
from textual.widgets import Label, Static


class ContextFragmentItem(Static):
    """A compact chip-like widget representing a single context fragment."""

    can_focus = True

    class Pressed(Message):
        def __init__(self, fragment_id: str, ctrl: bool, shift: bool) -> None:
            self.fragment_id = fragment_id
            self.ctrl = ctrl
            self.shift = shift
            super().__init__()

    class Hovered(Message):
        def __init__(self, fragment_id: str, entered: bool) -> None:
            self.fragment_id = fragment_id
            self.entered = entered
            super().__init__()

    def __init__(self, fragment: Dict[str, Any]) -> None:
        super().__init__(classes="context-chip")
        self.fragment = fragment

    @property
    def fragment_id(self) -> str:
        return str(self.fragment.get("id", ""))

    def on_mount(self) -> None:
        self._update_chip_text()

    def _update_chip_text(self) -> None:
        chip_kind = self.fragment.get("chip_kind", self.fragment.get("chipKind", "OTHER"))
        description = self.fragment.get("shortDescription", "Unknown")
        tokens = self.fragment.get("tokens", 0)

        kind_class = f"kind-{str(chip_kind).lower().replace('_', '-')}"
        self.add_class(kind_class)
        if self.fragment.get("pinned"):
            self.add_class("is-pinned")

        text = Text()
        if self.has_class("is-selected"):
            text.append("[SELECTED] ", style="bold")
        if self.has_class("is-active"):
            text.append("[ACTIVE] ", style="bold")
        text.append(f"{chip_kind} ", style="bold")
        text.append(description)
        if tokens > 0:
            text.append(f"  {tokens:,}t", style="dim")
        if self.fragment.get("pinned"):
            text.append("  PIN", style="bold")

        self.update(text)

    def on_click(self, event: events.Click) -> None:
        self.post_message(
            self.Pressed(
                fragment_id=self.fragment_id,
                ctrl=event.ctrl,
                shift=event.shift,
            )
        )

    def on_enter(self, event: events.Enter) -> None:
        self.post_message(self.Hovered(fragment_id=self.fragment_id, entered=True))

    def on_leave(self, event: events.Leave) -> None:
        self.post_message(self.Hovered(fragment_id=self.fragment_id, entered=False))

    def set_selected(self, selected: bool) -> None:
        self.set_class(selected, "is-selected")
        self._update_chip_text()

    def set_active(self, active: bool) -> None:
        self.set_class(active, "is-active")
        self._update_chip_text()


class ContextPanel(Vertical):
    """Context chip panel with selection and keyboard-driven actions."""

    BINDINGS = [
        Binding("left,up", "cursor_prev", "Prev", show=False),
        Binding("right,down", "cursor_next", "Next", show=False),
        Binding("enter", "select_only_cursor", "Select", show=False),
        Binding("space", "toggle_cursor_selection", "Toggle", show=False),
        Binding("ctrl+a", "select_all", "Select All", show=False),
        Binding("u", "clear_selection", "Unselect", show=False),
        Binding("d", "drop_selected", "Drop", show=False),
        Binding("shift+d", "drop_all", "Drop All", show=False),
        Binding("p", "toggle_pin_selected", "Pin", show=False),
        Binding("r", "toggle_readonly_selected", "Readonly", show=False),
        Binding("h", "compress_history", "Compress History", show=False),
        Binding("x", "clear_history", "Clear History", show=False),
    ]

    class ActionRequested(Message):
        def __init__(self, action: str, fragment_ids: List[str]) -> None:
            self.action = action
            self.fragment_ids = fragment_ids
            super().__init__()

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._fragments: List[Dict[str, Any]] = []
        self._items_by_id: Dict[str, ContextFragmentItem] = {}
        self._ordered_ids: List[str] = []
        self._selected_ids: Set[str] = set()
        self._cursor_index = -1
        self._last_wrap_width = -1
        self._active_id: str | None = None
        self._hovered_id: str | None = None

    def compose(self) -> ComposeResult:
        with Horizontal(id="context-header"):
            yield Label("Context", id="context-title")
            yield Label("0 / 200,000 tokens", id="context-token-usage")
        yield Label("Selected: 0", id="context-selection-status")
        yield Label("Active: none", id="context-active-status")
        yield Label(
            "Arrows: Move  Enter: Select  Space: Toggle  D: Drop  Shift+D: Drop All  "
            "P: Pin  R: Readonly  H: Compress History  X: Clear History",
            id="context-help",
        )
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
            width = self._panel_wrap_width()
            if width == self._last_wrap_width:
                return
            self._render_fragments()

    def _render_fragments(self) -> None:
        chip_wrap = self.query_one("#context-chip-wrap", Vertical)
        chip_wrap.remove_children()
        self._items_by_id = {}
        self._ordered_ids = []
        self._selected_ids = {
            fragment_id
            for fragment_id in self._selected_ids
            if any(str(f.get("id", "")) == fragment_id for f in self._fragments)
        }
        if (
            self._hovered_id
            and self._hovered_id not in self._selected_ids
            and not any(str(f.get("id", "")) == self._hovered_id for f in self._fragments)
        ):
            self._hovered_id = None
        if self._active_id and not any(
            str(f.get("id", "")) == self._active_id for f in self._fragments
        ):
            self._active_id = None

        if not self._fragments:
            self._cursor_index = -1
            self._active_id = None
            chip_wrap.mount(
                Static("No context fragments", classes="context-chip context-chip-empty")
            )
            self._update_selection_status()
            self._update_active_status()
            return

        if self._cursor_index < 0:
            self._cursor_index = 0

        max_width = self._panel_wrap_width()
        self._last_wrap_width = max_width
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
            items: List[ContextFragmentItem] = []
            for fragment in row_fragments:
                item = ContextFragmentItem(fragment)
                fragment_id = item.fragment_id
                if fragment_id:
                    self._ordered_ids.append(fragment_id)
                    self._items_by_id[fragment_id] = item
                    item.set_selected(fragment_id in self._selected_ids)
                items.append(item)
            row = Horizontal(
                *items,
                classes="context-chip-row",
            )
            chip_wrap.mount(row)

        if self._ordered_ids:
            self._cursor_index = min(self._cursor_index, len(self._ordered_ids) - 1)
            if self._active_id is None:
                self._active_id = self._cursor_id()
            self._refresh_active_classes()
            self._focus_cursor_item()
        else:
            self._cursor_index = -1
            self._active_id = None
        self._update_selection_status()
        self._update_active_status()

    def on_context_fragment_item_pressed(self, message: ContextFragmentItem.Pressed) -> None:
        if not message.fragment_id:
            return
        if message.ctrl:
            self._toggle_selected(message.fragment_id)
            self._set_cursor_by_id(message.fragment_id, focus=True)
            return
        self._select_only(message.fragment_id)
        self._set_cursor_by_id(message.fragment_id, focus=True)

    def on_context_fragment_item_hovered(self, message: ContextFragmentItem.Hovered) -> None:
        if not message.fragment_id:
            return
        if message.entered:
            self._hovered_id = message.fragment_id
            self._active_id = message.fragment_id
        elif self._hovered_id == message.fragment_id:
            self._hovered_id = None
            self._active_id = self._cursor_id()
        self._refresh_active_classes()
        self._update_active_status()

    def _panel_wrap_width(self) -> int:
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

    @property
    def selected_fragments(self) -> List[Dict[str, Any]]:
        if not self._selected_ids:
            return []
        selected = []
        ids = self._selected_ids
        for fragment in self._fragments:
            if str(fragment.get("id", "")) in ids:
                selected.append(fragment)
        return selected

    def action_cursor_prev(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index - 1) % len(self._ordered_ids)
        if self._hovered_id is None:
            self._active_id = self._cursor_id()
            self._refresh_active_classes()
            self._update_active_status()
        self._focus_cursor_item()

    def action_cursor_next(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index + 1) % len(self._ordered_ids)
        if self._hovered_id is None:
            self._active_id = self._cursor_id()
            self._refresh_active_classes()
            self._update_active_status()
        self._focus_cursor_item()

    def action_select_only_cursor(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self._select_only(cursor_id)

    def action_toggle_cursor_selection(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self._toggle_selected(cursor_id)

    def action_select_all(self) -> None:
        self._selected_ids = set(self._ordered_ids)
        self._refresh_selection_classes()

    def action_clear_selection(self) -> None:
        self._selected_ids = set()
        self._refresh_selection_classes()

    def action_drop_selected(self) -> None:
        fragment_ids = self._selected_fragment_ids()
        if fragment_ids:
            self.post_message(self.ActionRequested("drop_selected", fragment_ids))

    def action_drop_all(self) -> None:
        self.post_message(self.ActionRequested("drop_all", []))

    def action_toggle_pin_selected(self) -> None:
        fragment_ids = self._selected_fragment_ids()
        if fragment_ids:
            self.post_message(self.ActionRequested("toggle_pin_selected", fragment_ids))

    def action_toggle_readonly_selected(self) -> None:
        fragment_ids = self._selected_fragment_ids()
        if fragment_ids:
            self.post_message(self.ActionRequested("toggle_readonly_selected", fragment_ids))

    def action_compress_history(self) -> None:
        self.post_message(self.ActionRequested("compress_history", []))

    def action_clear_history(self) -> None:
        self.post_message(self.ActionRequested("clear_history", []))

    def _cursor_id(self) -> str | None:
        if self._cursor_index < 0 or self._cursor_index >= len(self._ordered_ids):
            return None
        return self._ordered_ids[self._cursor_index]

    def _set_cursor_by_id(self, fragment_id: str, focus: bool) -> None:
        try:
            self._cursor_index = self._ordered_ids.index(fragment_id)
        except ValueError:
            return
        if self._hovered_id is None:
            self._active_id = fragment_id
            self._refresh_active_classes()
            self._update_active_status()
        if focus:
            self._focus_cursor_item()

    def _focus_cursor_item(self) -> None:
        cursor_id = self._cursor_id()
        if not cursor_id:
            return
        item = self._items_by_id.get(cursor_id)
        if item is not None:
            item.focus(scroll_visible=True)

    def _selected_fragment_ids(self) -> List[str]:
        if not self._selected_ids:
            return []
        return [frag_id for frag_id in self._ordered_ids if frag_id in self._selected_ids]

    def _select_only(self, fragment_id: str) -> None:
        self._selected_ids = {fragment_id}
        self._refresh_selection_classes()

    def _toggle_selected(self, fragment_id: str) -> None:
        if fragment_id in self._selected_ids:
            self._selected_ids.remove(fragment_id)
        else:
            self._selected_ids.add(fragment_id)
        self._refresh_selection_classes()

    def _refresh_selection_classes(self) -> None:
        for frag_id, item in self._items_by_id.items():
            item.set_selected(frag_id in self._selected_ids)
        self._update_selection_status()
        self._refresh_active_classes()
        self._update_active_status()

    def _update_selection_status(self) -> None:
        label = self.query_one("#context-selection-status", Label)
        count = len(self._selected_ids)
        if count > 0:
            label.update(f"Selected: {count}")
            label.add_class("has-selection")
        else:
            label.update("Selected: 0")
            label.remove_class("has-selection")

    def _refresh_active_classes(self) -> None:
        active_id = self._active_id
        for frag_id, item in self._items_by_id.items():
            item.set_active(active_id == frag_id)

    def _update_active_status(self) -> None:
        label = self.query_one("#context-active-status", Label)
        active_id = self._active_id
        if not active_id:
            label.update("Active: none")
            label.remove_class("has-active")
            return

        fragment = next(
            (f for f in self._fragments if str(f.get("id", "")) == active_id),
            None,
        )
        if fragment is None:
            label.update("Active: none")
            label.remove_class("has-active")
            return

        short_desc = str(fragment.get("shortDescription", "Unknown"))
        label.update(f"Active: {short_desc}")
        label.add_class("has-active")
