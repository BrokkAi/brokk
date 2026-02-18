from typing import Optional

from textual.app import ComposeResult
from textual.containers import Horizontal
from textual.widgets import Static

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
        self._fragment_description: Optional[str] = None
        self._fragment_size: Optional[int] = None
        self._metadata: Optional[Static] = None

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

    def _render_status_text(self) -> None:
        workspace_label = self._workspace_label(self._workspace)
        text = (
            f"Mode: {self._mode} - "
            f"Model: {self._model} (reasoning: {self._reasoning}) - "
            f"Workspace: {workspace_label}"
        )
        if self._fragment_description is not None and self._fragment_size is not None:
            size_text = format_token_count(self._fragment_size)
            text = f"Fragment: {self._fragment_description} ({size_text} tokens)"

        self._set_status_metadata(text)

    @staticmethod
    def _workspace_label(workspace: str) -> str:
        trimmed = workspace.strip()
        if not trimmed:
            return "unknown"
        normalized = trimmed.rstrip("/\\")
        for sep in ("/", "\\"):
            if sep in normalized:
                tail = normalized.rsplit(sep, 1)[-1]
                return tail or normalized
        return normalized

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
    ) -> None:
        """Update the metadata text segment."""
        self._mode = str(mode or "unknown")
        self._model = str(model or "unknown")
        self._reasoning = str(reasoning or "unknown")
        self._workspace = str(workspace or "unknown")
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
