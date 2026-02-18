import pathlib
import time
from pathlib import Path
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

    SEPARATOR = " • "

    DEFAULT_CSS = """
    StatusLine {
        height: 1;
        padding: 0 1;
        color: $text-disabled;
        layout: horizontal;
    }
    #status-metadata {
        width: 1fr;
        min-width: 10;
    }
    #status-progress {
        width: auto;
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

    def _get_display_workspace(self, workspace: str) -> str:
        if workspace == "unknown":
            return workspace
        try:
            # Normalize backslashes immediately for consistent behavior across platforms
            norm_workspace = workspace.replace("\\", "/")
            # Use module-level Path for construction so it can be monkeypatched by tests
            path = Path(norm_workspace)
            # Use pathlib.Path.home() for home detection so it works even if Path
            # is patched to a non-class
            home = pathlib.Path.home()

            if str(path) == str(home) or path == home:
                return "~"

            try:
                # is_relative_to handles path comparison without filesystem access
                if path.is_relative_to(home):
                    rel = path.relative_to(home).as_posix()
                    return f"~/{rel}"
            except (ValueError, TypeError):
                pass

            return path.as_posix()
        except Exception:
            pass
        return workspace.replace("\\", "/")

    def _render_status_text(self) -> None:
        # Compact format: {mode} • {model} ({reasoning}) • {workspace} • {branch}
        workspace_display = self._get_display_workspace(self._workspace)
        text = self.SEPARATOR.join(
            [
                self._mode,
                f"{self._model} ({self._reasoning})",
                workspace_display,
                self._branch,
            ]
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
        """Update the metadata text segment. Only non-None values are updated."""
        if mode is not None:
            self._mode = str(mode)
        if model is not None:
            self._model = str(model)
        if reasoning is not None:
            self._reasoning = str(reasoning)
        if workspace is not None:
            self._workspace = str(workspace)
        if branch is not None:
            self._branch = str(branch)
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
        """Update internal job state."""
        if running:
            if self._job_start_time is None:
                self._job_start_time = self._get_now()
        else:
            self._job_start_time = None
