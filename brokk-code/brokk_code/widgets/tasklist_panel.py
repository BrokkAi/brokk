from typing import Any, Dict, Optional
from textual.app import ComposeResult
from textual.widgets import Static, Label
from textual.containers import Vertical, VerticalScroll
from rich.text import Text

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
        """Finds the TASK_LIST fragment and updates the display."""
        fragments = context_data.get("fragments", [])
        task_fragment: Optional[Dict[str, Any]] = next(
            (f for f in fragments if f.get("chipKind") == "TASK_LIST"), 
            None
        )

        content = self.query_one("#tasklist-content", Static)
        
        if task_fragment:
            desc = task_fragment.get("shortDescription", "Active task list")
            text = Text()
            text.append("Task list active\n\n", style="bold green")
            text.append(desc, style="italic")
            text.append("\n\n(Content not available via API)", style="dim")
            content.update(text)
        else:
            content.update(Text("No task list active", style="dim"))
