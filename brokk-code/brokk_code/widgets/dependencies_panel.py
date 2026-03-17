from typing import Any, Dict, List, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.message import Message
from textual.widget import Widget
from textual.widgets import ListItem, ListView, Static


class DependenciesPanel(Widget):
    """Panel for managing project dependencies."""

    class ActionRequested(Message):
        """Message emitted when user requests an action."""

        def __init__(self, action: str, dependency_name: Optional[str] = None) -> None:
            self.action = action
            self.dependency_name = dependency_name
            super().__init__()

    BINDINGS = [
        Binding("space", "toggle_live", "Toggle Live", show=False),
        Binding("enter", "toggle_live", "Toggle Live", show=False),
        Binding("a", "add_dependency", "Add", show=False),
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
            "[b]A[/b] Add  "
            "[b]Space/Enter[/b] Toggle Live  "
            "[b]U[/b] Update  "
            "[b]D/X[/b] Delete  "
            "[b]R[/b] Refresh"
        )

    def refresh_dependencies(self, data: List[Dict[str, Any]]) -> None:
        """Update the panel with new dependency data."""
        self._dependencies = data
        self._rebuild_list()

    @staticmethod
    def _dep_label(dep: Dict[str, Any]) -> str:
        name = dep.get("name", "")
        display_name = dep.get("displayName", name)
        is_live = dep.get("isLive", False)
        file_count = dep.get("fileCount", 0)
        marker = "[x]" if is_live else "[ ]"
        return f"{marker} {display_name} ({file_count} files)"

    def _rebuild_list(self) -> None:
        """Rebuild the ListView with current dependencies, avoiding clear() to preserve focus."""
        list_view = self.query_one("#dependencies-list", ListView)
        prev_index = list_view.index
        children = list(list_view.children)
        old_count = len(children)
        new_count = len(self._dependencies)

        # Remove excess items from the end
        for child in children[new_count:]:
            child.remove()

        # Update existing items in-place
        for child, dep in zip(children[:new_count], self._dependencies):
            child.query_one(Static).update(self._dep_label(dep))

        # Append any new items
        for dep in self._dependencies[old_count:]:
            list_view.append(ListItem(Static(self._dep_label(dep), markup=False)))

        # Restore highlight position
        if new_count > 0:
            list_view.index = min(prev_index, new_count - 1) if prev_index is not None else 0

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

    def on_focus(self) -> None:
        """Delegate focus to the inner ListView so arrow keys work."""
        self.query_one("#dependencies-list", ListView).focus()

    def action_add_dependency(self) -> None:
        self.post_message(self.ActionRequested("add"))

    def action_refresh(self) -> None:
        self.post_message(self.ActionRequested("refresh"))
