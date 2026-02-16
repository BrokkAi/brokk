from datetime import datetime
from typing import Any, Dict, List, Optional

from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.message import Message
from textual.widgets import Label, Static


class SessionItem(Static):
    """A widget representing a single session in the list."""

    can_focus = True

    class Pressed(Message):
        def __init__(self, session_id: str) -> None:
            self.session_id = session_id
            super().__init__()

    def __init__(self, session: Dict[str, Any]) -> None:
        super().__init__(classes="session-item")
        self.session = session

    @property
    def session_id(self) -> str:
        return str(self.session.get("id", ""))

    def on_mount(self) -> None:
        self._update_display()

    def _update_display(self) -> None:
        name = self.session.get("name", "Unnamed")
        short_id = self.session_id[:8] if self.session_id else "?"
        is_current = self.session.get("current", False)
        modified = self.session.get("modified", 0)

        if is_current:
            self.add_class("is-current")

        text = Text()
        if is_current:
            text.append("[CURRENT] ", style="bold green")
        text.append(name, style="bold")
        text.append(f"  ({short_id})", style="dim")

        if modified:
            try:
                dt = datetime.fromtimestamp(modified / 1000.0)
                text.append(f"  {dt.strftime('%Y-%m-%d %H:%M')}", style="dim")
            except (ValueError, OSError):
                pass

        self.update(text)

    def on_click(self) -> None:
        self.post_message(self.Pressed(session_id=self.session_id))

    def set_active(self, active: bool) -> None:
        self.set_class(active, "is-active")


class SessionPanel(Vertical):
    """Session list panel with keyboard-driven actions."""

    BINDINGS = [
        Binding("up", "cursor_prev", "Prev", show=False),
        Binding("down", "cursor_next", "Next", show=False),
        Binding("enter", "switch_session", "Switch", show=False),
        Binding("n", "new_session", "New", show=False),
        Binding("r", "rename_session", "Rename", show=False),
        Binding("d", "delete_session", "Delete", show=False),
    ]

    class ActionRequested(Message):
        def __init__(self, action: str, session_id: str = "", name: str = "") -> None:
            self.action = action
            self.session_id = session_id
            self.name = name
            super().__init__()

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._sessions: List[Dict[str, Any]] = []
        self._items_by_id: Dict[str, SessionItem] = {}
        self._ordered_ids: List[str] = []
        self._cursor_index = -1

    def compose(self) -> ComposeResult:
        yield Label("Sessions", id="session-title")
        yield Label(
            "Enter: Switch  N: New  R: Rename  D: Delete",
            id="session-help",
        )
        with VerticalScroll(id="session-list-scroll"):
            yield Vertical(id="session-list-wrap")

    def refresh_sessions(self, sessions_data: Dict[str, Any]) -> None:
        """Updates the session list from /v1/sessions response."""
        self._sessions = sessions_data.get("sessions", [])
        self._render_sessions()

    def _render_sessions(self) -> None:
        list_wrap = self.query_one("#session-list-wrap", Vertical)
        list_wrap.remove_children()
        self._items_by_id = {}
        self._ordered_ids = []

        if not self._sessions:
            self._cursor_index = -1
            list_wrap.mount(Static("No sessions", classes="session-item session-item-empty"))
            return

        if self._cursor_index < 0:
            self._cursor_index = 0

        for session in self._sessions:
            item = SessionItem(session)
            session_id = item.session_id
            if session_id:
                self._ordered_ids.append(session_id)
                self._items_by_id[session_id] = item
            list_wrap.mount(item)

        if self._ordered_ids:
            self._cursor_index = min(self._cursor_index, len(self._ordered_ids) - 1)
            self._refresh_active_classes()
            self._focus_cursor_item()
        else:
            self._cursor_index = -1

    def on_session_item_pressed(self, message: SessionItem.Pressed) -> None:
        if message.session_id:
            self._set_cursor_by_id(message.session_id)
            self.post_message(self.ActionRequested("switch", session_id=message.session_id))

    def action_cursor_prev(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index - 1) % len(self._ordered_ids)
        self._refresh_active_classes()
        self._focus_cursor_item()

    def action_cursor_next(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index + 1) % len(self._ordered_ids)
        self._refresh_active_classes()
        self._focus_cursor_item()

    def action_switch_session(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self.post_message(self.ActionRequested("switch", session_id=cursor_id))

    def action_new_session(self) -> None:
        self.post_message(self.ActionRequested("new"))

    def action_rename_session(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self.post_message(self.ActionRequested("rename", session_id=cursor_id))

    def action_delete_session(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self.post_message(self.ActionRequested("delete", session_id=cursor_id))

    def _cursor_id(self) -> Optional[str]:
        if self._cursor_index < 0 or self._cursor_index >= len(self._ordered_ids):
            return None
        return self._ordered_ids[self._cursor_index]

    def _set_cursor_by_id(self, session_id: str) -> None:
        try:
            self._cursor_index = self._ordered_ids.index(session_id)
        except ValueError:
            return
        self._refresh_active_classes()
        self._focus_cursor_item()

    def _focus_cursor_item(self) -> None:
        cursor_id = self._cursor_id()
        if not cursor_id:
            return
        item = self._items_by_id.get(cursor_id)
        if item is not None:
            item.focus(scroll_visible=True)

    def _refresh_active_classes(self) -> None:
        cursor_id = self._cursor_id()
        for session_id, item in self._items_by_id.items():
            item.set_active(session_id == cursor_id)
