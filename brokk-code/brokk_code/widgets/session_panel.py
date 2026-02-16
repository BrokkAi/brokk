from datetime import datetime
from typing import Any, Dict, List, Optional

from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.message import Message
from textual.widgets import Input, Label, Static


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
        full_id = self.session_id
        is_current = self.session.get("current", False)
        modified = self.session.get("modified", 0)

        if is_current:
            self.add_class("is-current")

        text = Text()
        if is_current:
            text.append("* ", style="bold green")
        text.append(name, style="bold")
        text.append(f"  {full_id}", style="dim")

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
        self._input_mode: Optional[str] = None  # "new" or "rename"
        self._input_session_id: str = ""

    def compose(self) -> ComposeResult:
        with Horizontal(id="session-header"):
            yield Label("Sessions", id="session-title")
            yield Label("", id="session-count")
        yield Label(
            "Enter: Switch  N: New  R: Rename  D: Delete  Esc/Ctrl+S: Close",
            id="session-help",
        )
        yield Label("", id="session-active-status")
        yield Input(placeholder="Enter session name...", id="session-name-input", classes="hidden")
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

        count_label = self.query_one("#session-count", Label)
        count_label.update(f"  ({len(self._sessions)})")

        if not self._sessions:
            self._cursor_index = -1
            list_wrap.mount(Static("No sessions", classes="session-item session-item-empty"))
            self._update_active_status()
            return

        if self._cursor_index < 0:
            # Start cursor on the current session
            self._cursor_index = 0
            for i, s in enumerate(self._sessions):
                if s.get("current"):
                    self._cursor_index = i
                    break

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
        self._update_active_status()

    def _update_active_status(self) -> None:
        label = self.query_one("#session-active-status", Label)
        cursor_id = self._cursor_id()
        if not cursor_id:
            label.update("")
            return
        session = next(
            (s for s in self._sessions if str(s.get("id", "")) == cursor_id), None
        )
        if session:
            name = session.get("name", "Unnamed")
            current = " (current)" if session.get("current") else ""
            label.update(f"Selected: {name}{current}")

    def on_session_item_pressed(self, message: SessionItem.Pressed) -> None:
        if message.session_id:
            self._set_cursor_by_id(message.session_id)
            self.post_message(self.ActionRequested("switch", session_id=message.session_id))

    def on_input_submitted(self, message: Input.Submitted) -> None:
        """Handle name input for new/rename actions."""
        name = message.value.strip()
        input_widget = self.query_one("#session-name-input", Input)
        input_widget.add_class("hidden")
        input_widget.value = ""

        if not name:
            self._input_mode = None
            self._input_session_id = ""
            # Re-focus the list
            self._focus_cursor_item()
            return

        if self._input_mode == "new":
            self.post_message(self.ActionRequested("new", name=name))
        elif self._input_mode == "rename":
            self.post_message(
                self.ActionRequested("rename", session_id=self._input_session_id, name=name)
            )

        self._input_mode = None
        self._input_session_id = ""

    def _show_name_input(self, mode: str, placeholder: str, session_id: str = "") -> None:
        self._input_mode = mode
        self._input_session_id = session_id
        input_widget = self.query_one("#session-name-input", Input)
        input_widget.placeholder = placeholder
        input_widget.value = ""
        input_widget.remove_class("hidden")
        input_widget.focus()

    def action_cursor_prev(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index - 1) % len(self._ordered_ids)
        self._refresh_active_classes()
        self._focus_cursor_item()
        self._update_active_status()

    def action_cursor_next(self) -> None:
        if not self._ordered_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        else:
            self._cursor_index = (self._cursor_index + 1) % len(self._ordered_ids)
        self._refresh_active_classes()
        self._focus_cursor_item()
        self._update_active_status()

    def action_switch_session(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            self.post_message(self.ActionRequested("switch", session_id=cursor_id))

    def action_new_session(self) -> None:
        self._show_name_input("new", "Enter name for new session...")

    def action_rename_session(self) -> None:
        cursor_id = self._cursor_id()
        if cursor_id:
            session = next(
                (s for s in self._sessions if str(s.get("id", "")) == cursor_id), None
            )
            current_name = session.get("name", "") if session else ""
            self._show_name_input("rename", f"Rename session (was: {current_name})...", cursor_id)

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
        self._update_active_status()

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
