from typing import Optional

from textual.app import ComposeResult
from textual.containers import Horizontal
from textual.widgets import Static


class StatusLine(Horizontal):
    """A compact status bar containing metadata and job progress.

    Displays:
      - mode, model, reasoning, workspace (via MetadataLabel)
      - spinner and elapsed timer (via JobProgress)
    """

    DEFAULT_CSS = """
    StatusLine {
        height: 1;
        padding: 0 1;
        background: $panel;
        color: $text-disabled;
        layout: horizontal;
    }
    #status-metadata {
        width: 1fr;
    }
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)

    def compose(self) -> ComposeResult:
        yield Static(id="status-metadata")

    def on_mount(self) -> None:
        app = self.app
        if app is None:
            self.update_status()
            return

        mode = getattr(app, "current_mode", getattr(app, "agent_mode", "unknown"))
        model = getattr(app, "current_model", "unknown")
        reasoning = getattr(app, "reasoning_level", "unknown")
        workspace = "unknown"
        try:
            executor = getattr(app, "executor", None)
            if executor is not None:
                ws = getattr(executor, "workspace_dir", None)
                if ws is not None:
                    workspace = str(ws)
        except Exception:
            pass

        self.update_status(mode, model, reasoning, workspace)

    def update_status(
        self,
        mode: Optional[str] = None,
        model: Optional[str] = None,
        reasoning: Optional[str] = None,
        workspace: Optional[str] = None,
    ) -> None:
        """Update the metadata text segment."""
        mode_s = str(mode or "unknown")
        model_s = str(model or "unknown")
        reasoning_s = str(reasoning or "unknown")
        workspace_s = str(workspace or "unknown")
        text = (
            f"Mode: {mode_s} - "
            f"Model: {model_s} (reasoning: {reasoning_s}) - "
            f"Workspace: {workspace_s}"
        )
        try:
            self.query_one("#status-metadata", Static).update(text)
        except Exception:
            pass
