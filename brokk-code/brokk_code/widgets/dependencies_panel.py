from typing import Any, Dict, List, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.message import Message
from textual.widget import Widget
from textual.widgets import ListView, ListItem, Static


class DependenciesPanel(Widget):
    """Panel for managing project dependencies."""

    can_focus = True

    class ActionRequested(Message):
        """Message emitted when user requests an action."""

        def __init__(self, action: str, dependency_name: Optional[str] = None) -> None:
            self.action = action
            self.dependency_name = dependency_name
            super().__init__()

    BINDINGS = [
        Binding("space", "toggle_live", "Toggle Live", show=False),
        Binding("enter", "toggle_live", "Toggle Live", show=False),
        Binding("u", "update_dependency", "Update", show=False),
        Binding("d", "delete_dependency", "Delete", show=False),
        Binding("x", "delete_dependency", "Delete", show=False),
        Binding("r", "refresh", "Refresh", show=False),
    ]

    def __init__(self, id: Optional[str] = None) -> None:
        super().__init__(id=id)
        self._dependencies: List[Dict[str, Any]] = []
        self._selected_index: int = 0

    def compose(self) -> ComposeResult:
        with Vertical(id="dependencies-container"):
            yield Static("Dependencies", id="dependencies-title")
            yield Static(self._get_shortcuts_text(), id="dependencies-help-line")
            with VerticalScroll(id="dependencies-list-scroll"):
                yield ListView(id="dependencies-list")

    def _get_shortcuts_text(self) -> str:
        """Derive a concise help line from BINDINGS."""
        return (
            "[bold bright_magenta]Esc[/] Close  "
            "[b]Space/Enter[/b] Toggle Live  "
            "[b]U[/b] Update  "
            "[b]D/X[/b] Delete  "
            "[b]R[/b] Refresh"
        )

    def refresh_dependencies(self, data: List[Dict[str, Any]]) -> None:
        """Update the panel with new dependency data."""
        self._dependencies = data
        self._rebuild_list()

    def _rebuild_list(self) -> None:
        """Rebuild the ListView with current dependencies."""
        list_view = self.query_one("#dependencies-list", ListView)
        list_view.clear()
        for dep in self._dependencies:
            name = dep.get("name", "")
            display_name = dep.get("displayName", name)
            is_live = dep.get("isLive", False)
            file_count = dep.get("fileCount", 0)
            marker = "[x]" if is_live else "[ ]"
            label = f"{marker} {display_name} ({file_count} files)"
            list_view.append(ListItem(Static(label, markup=False), id=f"dep-{name}"))

    def selected_dependency(self) -> Optional[Dict[str, Any]]:
        """Returns the currently selected dependency."""
        list_view = self.query_one("#dependencies-list", ListView)
        if list_view.highlighted_child and list_view.index is not None:
            if 0 <= list_view.index < len(self._dependencies):
                return self._dependencies[list_view.index]
        return None

    def action_toggle_live(self) -> None:
        dep = self.selected_dependency()
        if dep:
            self.post_message(self.ActionRequested("toggle_live", dep.get("name")))

    def action_update_dependency(self) -> None:
        dep = self.selected_dependency()
        if dep:
            self.post_message(self.ActionRequested("update", dep.get("name")))

    def action_delete_dependency(self) -> None:
        dep = self.selected_dependency()
        if dep:
            self.post_message(self.ActionRequested("delete", dep.get("name")))

    def action_refresh(self) -> None:
        self.post_message(self.ActionRequested("refresh"))
