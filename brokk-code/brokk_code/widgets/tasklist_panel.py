from copy import deepcopy
from typing import Any, Dict, List, Optional

from rich.text import Text
from textual.app import ComposeResult
from textual.containers import Vertical, VerticalScroll
from textual.widgets import Label, Static


class TaskListPanel(Vertical):
    """
    Displays the current task list status.

    Note: Currently /v1/context does not expose fragment text content.
    Future enhancement: Add an endpoint to fetch fragment content by ID.
    """

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._last_details: Optional[Dict[str, Any]] = None
        self._selected_index: int = 0

    @property
    def has_detailed_info(self) -> bool:
        """Returns True if the panel is currently showing detailed data from /v1/tasklist."""
        return self._last_details is not None

    @property
    def selected_index(self) -> int:
        return self._selected_index

    def selected_task(self) -> Optional[Dict[str, Any]]:
        if not self._last_details:
            return None
        tasks = self._last_details.get("tasks", [])
        if not tasks:
            return None
        if self._selected_index < 0 or self._selected_index >= len(tasks):
            return None
        return tasks[self._selected_index]

    def move_selection(self, delta: int) -> bool:
        """Moves selected task row. Returns True when selection changed."""
        if not self._last_details:
            return False
        tasks = self._last_details.get("tasks", [])
        if not tasks:
            return False
        new_index = min(max(0, self._selected_index + delta), len(tasks) - 1)
        if new_index == self._selected_index:
            return False
        self._selected_index = new_index
        self._render_details()
        return True

    def tasklist_data_for_update(self) -> Optional[Dict[str, Any]]:
        """Returns a mutable copy of detailed tasklist data suitable for CRUD updates."""
        if not self._last_details:
            return None
        data = deepcopy(self._last_details)
        data.setdefault("tasks", [])
        return data

    def compose(self) -> ComposeResult:
        yield Label("Task List", id="tasklist-header")
        with VerticalScroll(id="tasklist-container"):
            yield Static("No task list active", id="tasklist-content")

    def refresh_tasklist(self, context_data: Dict[str, Any]) -> None:
        """Finds the TASK_LIST fragment and updates the display using context overview."""
        fragments = context_data.get("fragments", [])
        task_fragment: Optional[Dict[str, Any]] = next(
            (f for f in fragments if f.get("chipKind") == "TASK_LIST"), None
        )

        if not task_fragment:
            self._last_details = None
            self._selected_index = 0
            self.query_one("#tasklist-content", Static).update(
                Text("No task list active", style="dim")
            )
            return

        # If we have detailed data already, don't clobber it with the summary
        if self._last_details:
            return

        text = Text()
        text.append("[ ] ", style="bold blue")
        text.append("Task list active", style="bold")
        self.query_one("#tasklist-content", Static).update(text)

    def update_tasklist_details(self, tasklist_data: Dict[str, Any]) -> None:
        """Updates the display with detailed task list information from /v1/tasklist."""
        previous_selected_id = ""
        prev = self.selected_task()
        if prev:
            previous_selected_id = str(prev.get("id", ""))
        self._last_details = deepcopy(tasklist_data)
        big_picture = tasklist_data.get("bigPicture")
        tasks = tasklist_data.get("tasks", [])

        content = self.query_one("#tasklist-content", Static)
        if not big_picture and not tasks:
            self._last_details = None
            self._selected_index = 0
            content.update(Text("No task list active", style="dim"))
            return

        # Keep the same selected task by id when possible.
        if previous_selected_id:
            for idx, task in enumerate(tasks):
                if str(task.get("id", "")) == previous_selected_id:
                    self._selected_index = idx
                    break
        if tasks:
            self._selected_index = min(max(0, self._selected_index), len(tasks) - 1)
        else:
            self._selected_index = 0

        self._render_details()

    def _render_details(self) -> None:
        if not self._last_details:
            self.query_one("#tasklist-content", Static).update(
                Text("No task list active", style="dim")
            )
            return
        tasks: List[Dict[str, Any]] = self._last_details.get("tasks", [])
        text = Text()
        for i, task in enumerate(tasks, 1):
            done = task.get("done", False)
            title = task.get("title", f"Task {i}")

            checkbox = "[x]" if done else "[ ]"

            prefix = "> " if i - 1 == self._selected_index else "  "
            text.append(prefix, style="bold")
            text.append(f"{checkbox} ", style="bold green" if done else "bold blue")
            text.append(title, style="bold strike" if done else "bold")
            text.append("\n")

        self.query_one("#tasklist-content", Static).update(text)
