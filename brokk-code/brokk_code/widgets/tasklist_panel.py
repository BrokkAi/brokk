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

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._last_details: Optional[Dict[str, Any]] = None

    @property
    def has_detailed_info(self) -> bool:
        """Returns True if the panel is currently showing detailed data from /v1/tasklist."""
        return self._last_details is not None

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
            self.query_one("#tasklist-content", Static).update(
                Text("No task list active", style="dim")
            )
            return

        # If we have detailed data already, don't clobber it with the summary
        if self._last_details:
            return

        desc = task_fragment.get("shortDescription", "Active task list")
        text = Text()
        text.append("Task list active\n\n", style="bold green")
        text.append(desc, style="italic")
        self.query_one("#tasklist-content", Static).update(text)

    def update_tasklist_details(self, tasklist_data: Dict[str, Any]) -> None:
        """Updates the display with detailed task list information from /v1/tasklist."""
        self._last_details = tasklist_data
        big_picture = tasklist_data.get("bigPicture")
        tasks = tasklist_data.get("tasks", [])

        content = self.query_one("#tasklist-content", Static)
        if not big_picture and not tasks:
            self._last_details = None
            content.update(Text("No task list active", style="dim"))
            return

        text = Text()
        text.append("Task List Active\n\n", style="bold green")

        if big_picture:
            text.append("Goal: ", style="bold")
            text.append(f"{big_picture}\n\n")

        for i, task in enumerate(tasks, 1):
            done = task.get("done", False)
            title = task.get("title", f"Task {i}")
            instruction = task.get("text", "")

            checkbox = "[x]" if done else "[ ]"
            status = "done" if done else "todo"

            text.append(f" {checkbox} ", style="bold green" if done else "bold blue")
            text.append(title, style="bold strike" if done else "bold")
            text.append(f" ({status})", style="dim")

            if instruction:
                # Add a short snippet of the instructions
                snippet = instruction.split("\n")[0]
                if len(snippet) > 60:
                    snippet = snippet[:57] + "..."
                text.append(f"\n      {snippet}", style="dim italic")

            text.append("\n\n")

        content.update(text)
