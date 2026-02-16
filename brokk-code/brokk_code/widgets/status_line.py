from typing import Optional

from textual.widgets import Static


class StatusLine(Static):
    """A compact single-line status summary.

    Displays:
      - mode (current_mode / agent_mode)
      - planner model (current_model)
      - reasoning level (reasoning_level)
      - workspace dir (executor.workspace_dir)

    The widget reads initial values from the App instance on_mount and provides a
    small API to update its displayed values at runtime.
    """

    DEFAULT_CSS = """
    StatusLine {
        height: 1;
        padding: 0 1;
        background: $panel;
        color: $text-disabled;
    }
    """

    def on_mount(self) -> None:
        # Defer to the running App for initial state. The app may not be typed here,
        # so use getattr with sensible fallbacks.
        app = self.app
        if app is None:
            self.update(self._format_status(None, None, None, None))
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
            workspace = "unknown"

        self.update(self._format_status(mode, model, reasoning, workspace))

    def update_status(
        self,
        mode: Optional[str] = None,
        model: Optional[str] = None,
        reasoning: Optional[str] = None,
        workspace: Optional[str] = None,
    ) -> None:
        """Public API to update the status line. Any argument left as None will be
        treated as 'unknown' or left as-is by formatting logic."""
        try:
            self.update(self._format_status(mode, model, reasoning, workspace))
        except Exception:
            # Best-effort: avoid raising when the widget is not mounted or when the
            # provided values are unexpected.
            return

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
        return f"Mode: {mode_s} | Model: {model_s} (reasoning: {reasoning_s}) | Workspace: {workspace_s}"
