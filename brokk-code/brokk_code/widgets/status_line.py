import time
from typing import Optional

from textual.app import ComposeResult
from textual.containers import Horizontal
from textual.widgets import LoadingIndicator, Static


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
    #status-progress {
        width: auto;
        layout: horizontal;
    }
    #status-progress.hidden {
        display: none;
    }
    #status-spinner {
        height: 1;
        width: auto;
        min-width: 4;
        color: $accent;
        background: transparent;
        margin-right: 1;
    }
    #status-timer {
        width: auto;
        color: $text;
    }
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._job_start_time: Optional[float] = None
        self._timer_interval = None
        self._get_now = time.time

    def compose(self) -> ComposeResult:
        yield Static(id="status-metadata")
        with Horizontal(id="status-progress", classes="hidden"):
            yield LoadingIndicator(id="status-spinner")
            yield Static(id="status-timer")

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

    def set_job_running(self, running: bool) -> None:
        """Start or stop the job progress indicator and timer."""
        progress = self.query_one("#status-progress", Horizontal)
        if running:
            if self._job_start_time is None:
                self._job_start_time = self._get_now()
                self._update_timer()
                if self._timer_interval is None:
                    self._timer_interval = self.set_interval(0.2, self._update_timer)
            progress.remove_class("hidden")
        else:
            self._job_start_time = None
            if self._timer_interval is not None:
                self._timer_interval.stop()
                self._timer_interval = None
            progress.add_class("hidden")
            try:
                self.query_one("#status-timer", Static).update("")
            except Exception:
                pass

    def _update_timer(self) -> None:
        if self._job_start_time is None:
            return
        elapsed = max(0, int(self._get_now() - self._job_start_time))
        hours, remainder = divmod(elapsed, 3600)
        minutes, seconds = divmod(remainder, 60)
        time_str = (
            f"{hours:02}:{minutes:02}:{seconds:02}" if hours > 0 else f"{minutes:02}:{seconds:02}"
        )
        try:
            self.query_one("#status-timer", Static).update(f"Elapsed: {time_str}")
        except Exception:
            pass
