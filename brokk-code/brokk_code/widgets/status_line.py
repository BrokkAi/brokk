import time
from typing import Optional

from textual.app import ComposeResult
from textual.containers import Horizontal
from textual.widgets import LoadingIndicator, Static

from brokk_code.token_format import format_token_count


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
        self._mode = "unknown"
        self._model = "unknown"
        self._reasoning = "unknown"
        self._workspace = "unknown"
        self._branch = "unknown"
        self._fragment_description: Optional[str] = None
        self._fragment_size: Optional[int] = None
        self._metadata: Optional[Static] = None

        self._get_now = time.time
        self._job_start_time: Optional[float] = None
        self._timer_interval = None

    def compose(self) -> ComposeResult:
        yield Static(id="status-metadata")
        with Horizontal(id="status-progress", classes="hidden"):
            yield LoadingIndicator(id="status-spinner")
            yield Static(id="status-timer")

    def on_mount(self) -> None:
        try:
            self._metadata = self.query_one("#status-metadata", Static)
        except Exception:
            self._metadata = None
        app = self.app
        if app is None:
            self._render_status_text()
            return

        mode = getattr(app, "current_mode", getattr(app, "agent_mode", "unknown"))
        model = getattr(app, "current_model", "unknown")
        reasoning = getattr(app, "reasoning_level", "unknown")
        branch = getattr(app, "current_branch", "unknown")
        workspace = "unknown"
        try:
            executor = getattr(app, "executor", None)
            if executor is not None:
                ws = getattr(executor, "workspace_dir", None)
                if ws is not None:
                    workspace = str(ws)
        except Exception:
            pass

        self.update_status(mode, model, reasoning, workspace, branch)

    def _render_status_text(self) -> None:
        # Compact format: {branch} - {mode} - {model} ({reasoning}) - {workspace}
        text = (
            f"{self._branch} - {self._mode} - {self._model} ({self._reasoning}) - {self._workspace}"
        )
        if self._fragment_description is not None and self._fragment_size is not None:
            size_text = format_token_count(self._fragment_size)
            # Label-free fragment: {description} ({tokens} tokens)
            text = f"{self._fragment_description} ({size_text} tokens)"

        self._set_status_metadata(text)

    def _set_status_metadata(self, text: str) -> None:
        metadata = self._metadata
        if metadata is None:
            return
        try:
            metadata.update(text)
        except Exception:
            pass

    def update_status(
        self,
        mode: Optional[str] = None,
        model: Optional[str] = None,
        reasoning: Optional[str] = None,
        workspace: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> None:
        """Update the metadata text segment."""
        self._mode = str(mode or "unknown")
        self._model = str(model or "unknown")
        self._reasoning = str(reasoning or "unknown")
        self._workspace = str(workspace or "unknown")
        self._branch = str(branch or "unknown")
        self._render_status_text()

    def set_fragment_info(self, description: Optional[str], size: Optional[int]) -> None:
        description = (description or "").strip()
        self._fragment_description = description or None
        self._fragment_size = size if size is not None and size >= 0 else None
        self._render_status_text()

    def clear_fragment_info(self) -> None:
        self._fragment_description = None
        self._fragment_size = None
        self._render_status_text()

    def set_job_running(self, running: bool) -> None:
        """Start or stop the job progress indicator and timer."""
        try:
            progress = self.query_one("#status-progress", Horizontal)
        except Exception:
            return

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
