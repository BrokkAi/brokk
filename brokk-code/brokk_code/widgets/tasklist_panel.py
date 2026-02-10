from typing import Any, Dict, Optional

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

        content = self.query_one("#tasklist-content", Static)

        if task_fragment:
            desc = task_fragment.get("shortDescription", "Active task list")
            text = Text()
            text.append("Task list active\n\n", style="bold green")
            text.append(desc, style="italic")
            content.update(text)
        else:
            content.update(Text("No task list active", style="dim"))

    def update_tasklist_details(self, tasklist_data: Dict[str, Any]) -> None:
        """Updates the display with detailed task list information from /v1/tasklist."""
        big_picture = tasklist_data.get("bigPicture")
        tasks = tasklist_data.get("tasks", [])

        if not big_picture and not tasks:
            return

        content = self.query_one("#tasklist-content", Static)
        text = Text()

        if big_picture:
            text.append("Goal: ", style="bold")
            text.append(f"{big_picture}\n\n")

        for i, task in enumerate(tasks, 1):
            done = task.get("done", False)
            title = task.get("title", f"Task {i}")
            marker = " [bold green]OK[/] " if done else " [bold yellow]..[/] "
            
            text.append(marker)
            text.append(title, style="strike" if done else "")
            text.append("\n")

        content.update(text)
