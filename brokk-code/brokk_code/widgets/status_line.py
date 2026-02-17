from typing import Optional

import time
from textual.containers import Horizontal
from textual.widgets import Static, LoadingIndicator
from textual import work
from rich.text import Text


class StatusLine(Static):
    """Composite single-line status bar composed of labelled segments.

    Public API:
      - update_status(mode, model, reasoning, workspace)
      - set_job_running(running: bool)
      - set_timer_text(text: Optional[str])

    Implementation notes:
      - The widget mounts a small Horizontal container with Static children for each
        status segment and a job segment on the right. The layout is single-line
        and relies on app.tcss selectors (#status-line .status-segment etc.) for styling.
      - For now the timer/spinner are placeholders: set_job_running and set_timer_text
        update the job segment content. Integration with actual timers will be done
        in BrokkApp/ChatPanel wiring later.
    """

    DEFAULT_CSS = """
    StatusLine {
        height: 1;
        padding: 0 1;
        background: transparent;
        color: $text-disabled;
    }

    /* Ensure the inner container uses horizontal layout and keeps single-line */
    StatusLine > Horizontal {
        height: 1;
        layout: horizontal;
        content-align: left middle;
    }
    """

    def on_mount(self) -> None:
        # Build child segments
        # Use a Horizontal container to host segments so app.tcss selectors apply.
        self._container = Horizontal(id="status-line-container")
        # Label/value segments
        self._mode_seg = Static("", classes="status-segment")
        self._model_seg = Static("", classes="status-segment")
        self._reasoning_seg = Static("", classes="status-segment")
        self._workspace_seg = Static("", classes="status-segment")
        # Job status: contains a small loading indicator and elapsed timer label
        self._job_seg = Horizontal(classes="status-segment status-job")
        self._job_spinner = LoadingIndicator(id="status-spinner", classes="status-spinner")
        self._job_timer = Static("", id="status-timer", classes="status-timer")

        # Mount children synchronously in on_mount
        self.mount(self._container)
        self._container.mount(self._mode_seg)
        self._container.mount(self._model_seg)
        self._container.mount(self._reasoning_seg)
        self._container.mount(self._workspace_seg)
        self._container.mount(self._job_seg)
        # Mount spinner and timer into job segment
        self._job_seg.mount(self._job_spinner)
        self._job_seg.mount(self._job_timer)

        # Internal state for job/timer
        self._job_running = False
        self._timer_text: Optional[str] = None
        self._job_start_time: Optional[float] = None
        self._timer_interval = None
        self._get_now = time.time

        # Initialize from app state if available
        app = self.app
        if app is None:
            self.update_status(None, None, None, None)
            return

        mode = getattr(app, "current_mode", getattr(app, "agent_mode", "unknown"))
        model = getattr(app, "current_model", None)
        reasoning = getattr(app, "reasoning_level", None)
        workspace = None
        try:
            executor = getattr(app, "executor", None)
            if executor is not None:
                ws = getattr(executor, "workspace_dir", None)
                if ws is not None:
                    workspace = str(ws)
        except Exception:
            workspace = None

        self.update_status(mode=mode, model=model, reasoning=reasoning, workspace=workspace)
        # Start with spinner hidden
        try:
            self._job_spinner.add_class("hidden")
            self._job_timer.update("")
        except Exception:
            pass

    def _seg_text(self, label: str, value: Optional[str]) -> Text:
        """Helper to produce a compact labelled Text for a segment."""
        lab = f"{label}: "
        val = str(value) if value is not None else "unknown"
        t = Text.assemble((lab, "dim"), (val, ""))
        return t

    def update_status(
        self,
        mode: Optional[str] = None,
        model: Optional[str] = None,
        reasoning: Optional[str] = None,
        workspace: Optional[str] = None,
    ) -> None:
        """Update the component segments. Arguments that are None are shown as 'unknown'."""
        try:
            # Prefer explicit values; fall back to existing app attributes if not provided
            app = self.app
            if app is not None:
                if mode is None:
                    mode = getattr(app, "current_mode", getattr(app, "agent_mode", "unknown"))
                if model is None:
                    model = getattr(app, "current_model", None)
                if reasoning is None:
                    reasoning = getattr(app, "reasoning_level", None)
                if workspace is None:
                    try:
                        executor = getattr(app, "executor", None)
                        if executor is not None:
                            ws = getattr(executor, "workspace_dir", None)
                            if ws is not None:
                                workspace = str(ws)
                    except Exception:
                        workspace = None

            # Update children
            self._mode_seg.update(self._seg_text("Mode", mode))
            self._model_seg.update(self._seg_text("Model", model))
            self._reasoning_seg.update(self._seg_text("Reasoning", reasoning))
            # Workspace may be long; present a shortened/plain text
            ws_val = workspace or "unknown"
            if isinstance(ws_val, str) and len(ws_val) > 40:
                ws_val = ws_val[:37] + "..."
            self._workspace_seg.update(self._seg_text("Workspace", ws_val))
        except Exception:
            # Best-effort: do not raise when updating UI that may not be mounted in tests
            return

    def set_job_running(self, running: bool) -> None:
        """Public API to indicate a job is active. Starts/stops an internal elapsed timer
        and shows/hides the spinner and timer visuals."""
        running = bool(running)
        if running:
            if self._job_start_time is None:
                self._job_start_time = self._get_now()
                # Start an interval to update elapsed label periodically
                if self._timer_interval is None:
                    self._timer_interval = self.set_interval(0.2, self._update_elapsed_time_label)
            # Show spinner and timer
            try:
                self._job_spinner.remove_class("hidden")
            except Exception:
                pass
        else:
            # Stop timer and reset label
            self._job_start_time = None
            if self._timer_interval is not None:
                try:
                    self._timer_interval.stop()
                except Exception:
                    pass
                self._timer_interval = None
            try:
                self._job_spinner.add_class("hidden")
                self._job_timer.update("")
            except Exception:
                pass

        self._job_running = running
        self._refresh_job_segment()

    def set_timer_text(self, text: Optional[str]) -> None:
        """Update the timer text shown in the job segment. Example: 'Elapsed: 00:12'."""
        self._timer_text = text
        try:
            if text:
                self._job_timer.update(f" {text}")
            else:
                self._job_timer.update("")
        except Exception:
            pass
        self._refresh_job_segment()

    def _refresh_job_segment(self) -> None:
        """Refresh job segment visual state. Most of the content is handled by
        dedicated child widgets (spinner and timer), so this method is a lightweight no-op
        to keep legacy expectations of updating the job segment container."""
        if not hasattr(self, "_job_seg"):
            return
        # No-op: spinner and timer children manage their own content/visibility.
        return

    # Keep legacy formatter as a utility for tests or external callers that prefer a single string.
    def _update_elapsed_time_label(self) -> None:
        """Internal timer tick to recompute elapsed time and update child label."""
        if self._job_start_time is None:
            return
        elapsed = max(0, int(self._get_now() - self._job_start_time))
        hours, remainder = divmod(elapsed, 3600)
        minutes, seconds = divmod(remainder, 60)

        if hours > 0:
            time_str = f"{hours:02}:{minutes:02}:{seconds:02}"
        else:
            time_str = f"{minutes:02}:{seconds:02}"

        try:
            self._job_timer.update(f" Elapsed: {time_str}")
        except Exception:
            pass

    @staticmethod
    def _format_status(
        mode: Optional[str],
        model: Optional[str],
        reasoning: Optional[str],
        workspace: Optional[str],
    ) -> str:
        mode_s = str(mode or "unknown")
        model_s = str(model or "unknown")
        reasoning_s = str(reasoning or "unknown")
        workspace_s = str(workspace or "unknown")
        return (
            f"Mode: {mode_s} - "
            f"Model: {model_s} (reasoning: {reasoning_s}) - "
            f"Workspace: {workspace_s}"
        )
