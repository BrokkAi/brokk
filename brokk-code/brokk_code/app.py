import asyncio
import logging
import time
from pathlib import Path
from typing import Any, Dict, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.widgets import Footer, Header

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.settings import DEFAULT_THEME, Settings, normalize_theme_name
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.context_panel import ContextPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel

logger = logging.getLogger(__name__)


class BrokkApp(App):
    """The main Brokk TUI application."""

    CSS_PATH = "styles/app.tcss"
    BINDINGS = [
        Binding("ctrl+c", "handle_ctrl_c", "Quit", show=True),
        Binding("ctrl+l", "toggle_context", "Context", show=True),
        Binding("ctrl+n", "toggle_notifications", "Notifications", show=True),
        Binding("ctrl+t", "toggle_tasklist", "Tasks", show=True),
        Binding("f2", "cycle_theme", "Cycle Theme", show=True),
    ]

    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = False,
    ) -> None:
        super().__init__()
        self.executor = ExecutorManager(
            workspace_dir or Path.cwd(),
            jar_path,
            executor_version=executor_version,
            executor_snapshot=executor_snapshot,
        )
        self.settings = Settings.load()
        self._set_theme(self.settings.theme)
        self.current_model = "gpt-5.2"
        self.code_model: Optional[str] = "gemini-3-flash-preview"
        self.reasoning_level: Optional[str] = "low"
        self.reasoning_level_code: Optional[str] = "disable"
        self.job_in_progress = False
        self.current_job_id: Optional[str] = None
        self._last_ctrl_c_time: float = 0

    def compose(self) -> ComposeResult:
        yield Header()
        with Horizontal():
            yield ContextPanel(id="side-context")
            yield ChatPanel(id="chat-main")
            yield TaskListPanel(id="side-tasklist")
        yield Footer()

    async def on_mount(self) -> None:
        chat = self.query_one(ChatPanel)
        logger.info("Using workspace directory: %s", self.executor.workspace_dir)
        chat.add_system_message_markup(f"Workspace: [bold]{self.executor.workspace_dir}[/]")
        chat.add_system_message("Starting Brokk executor...")
        self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())

    async def _start_executor(self) -> None:
        chat = self.query_one(ChatPanel)
        try:
            await self.executor.start()
            chat.add_system_message_markup(
                f"Executor started from [bold]{self.executor.resolved_jar_path}[/],\n"
                "initializing session..."
            )
            await self.executor.create_session()

            if await self.executor.wait_ready():
                chat.add_system_message_markup(
                    f"Ready!\n"
                    f"  Model: [bold]{self.current_model}[/]\n"
                    f"  (reasoning: [bold]{self.reasoning_level}[/])\n"
                    f"  Code model: [bold]{self.code_model}[/]\n"
                    f"  (reasoning: [bold]{self.reasoning_level_code}[/])"
                )
                # Initial context load
                self.run_worker(self._refresh_context_panel())
            else:
                chat.add_system_message("Executor failed to become ready (timeout).", level="ERROR")
        except ExecutorError as e:
            chat.add_system_message(str(e), level="ERROR")
        except Exception as e:
            chat.add_system_message(f"Unexpected startup error: {e}", level="ERROR")

    async def _monitor_executor(self) -> None:
        """Background worker to check if the executor dies unexpectedly."""
        while True:
            await asyncio.sleep(2.0)
            if not self.executor.check_alive():
                chat = self.query_one(ChatPanel)
                chat.add_system_message("Executor process crashed unexpectedly.", level="ERROR")
                break

    async def _refresh_context_panel(self) -> None:
        """Fetches latest context and updates context and task list panels."""
        try:
            context_data = await self.executor.get_context()
            self.query_one(ContextPanel).refresh_context(context_data)
            self.query_one(TaskListPanel).refresh_tasklist(context_data)
        except Exception:
            # Silently fail for background refreshes unless panel is visible?
            # For now, just log to avoid spamming the chat while allowing debugging
            logger.debug("Failed to refresh context panel", exc_info=True)

    def on_chat_panel_submitted(self, message: ChatPanel.Submitted) -> None:
        text = message.text.strip()
        if text.startswith("/"):
            self._handle_command(text)
        else:
            chat = self.query_one(ChatPanel)
            chat.add_user_message(text)
            chat.set_response_pending()
            self.run_worker(self._run_job(text))

    async def _run_job(self, task_input: str) -> None:
        if self.job_in_progress:
            self.query_one(ChatPanel).add_system_message("A job is already in progress.")
            return

        self.job_in_progress = True
        chat = self.query_one(ChatPanel)
        try:
            self.current_job_id = await self.executor.submit_job(
                task_input,
                self.current_model,
                code_model=self.code_model,
                reasoning_level=self.reasoning_level,
                reasoning_level_code=self.reasoning_level_code,
            )
            async for event in self.executor.stream_events(self.current_job_id):
                self._handle_event(event)
        except Exception as e:
            chat.add_system_message(f"Job failed or network error: {e}", level="ERROR")
            chat.set_response_finished()
        finally:
            self.job_in_progress = False
            self.current_job_id = None
            chat.set_response_finished()

    def _handle_event(self, event: Dict[str, Any]) -> None:
        event_type = event.get("type")
        data = event.get("data", {})
        chat = self.query_one(ChatPanel)

        if event_type == "LLM_TOKEN":
            chat.append_token(
                token=data.get("token", ""),
                message_type=data.get("messageType", "AI"),
                is_new_message=data.get("isNewMessage", False),
                is_reasoning=data.get("isReasoning", False),
                is_terminal=data.get("isTerminal", False),
            )
        elif event_type == "NOTIFICATION":
            level = data.get("level", "INFO")
            msg = data.get("message", "")
            chat.add_notification(msg, level=level)
        elif event_type == "ERROR":
            msg = data.get("message", "Unknown error")
            chat.add_notification(msg, level="ERROR")
            # Also keep error in chat for visibility in logs
            chat.add_system_message(msg, level="ERROR")
            chat.set_response_finished()
        elif event_type == "STATE_HINT":
            hint_name = data.get("name")
            if hint_name in ("contextHistoryUpdated", "workspaceUpdated"):
                self.run_worker(self._refresh_context_panel())

    def _handle_command(self, cmd: str) -> None:
        chat = self.query_one(ChatPanel)
        parts = cmd.split()
        base = parts[0].lower()

        if base == "/model" and len(parts) > 1:
            self.current_model = parts[1]
            chat.add_system_message_markup(f"Model changed to: [bold]{self.current_model}[/]")
        elif base == "/model-code" and len(parts) > 1:
            self.code_model = parts[1]
            chat.add_system_message_markup(f"Code model changed to: [bold]{self.code_model}[/]")
        elif base == "/reasoning" and len(parts) > 1:
            self.reasoning_level = parts[1]
            chat.add_system_message_markup(
                f"Reasoning level changed to: [bold]{self.reasoning_level}[/]"
            )
        elif base == "/reasoning-code" and len(parts) > 1:
            self.reasoning_level_code = parts[1]
            chat.add_system_message_markup(
                f"Code reasoning level changed to: [bold]{self.reasoning_level_code}[/]"
            )
        elif base == "/theme" and len(parts) > 1:
            theme_val = parts[1].lower()
            if theme_val in self.available_themes:
                self._set_theme(theme_val)
                chat.add_system_message_markup(f"Theme changed to: [bold]{theme_val}[/]")
            else:
                available = ", ".join(sorted(self.available_themes))
                chat.add_system_message(
                    f"Invalid theme '{theme_val}'. Available: {available}", level="ERROR"
                )
        elif base == "/help":
            help_text = (
                "Available commands:\n"
                "  /model <name>         - Change the planner LLM model\n"
                "  /model-code <name>    - Change the code LLM model\n"
                "  /reasoning <level>    - Set reasoning level for planner\n"
                "  /reasoning-code <level> - Set reasoning level for code model\n"
                "  /theme <dark|light>   - Change UI theme\n"
                "  /help                 - Show this help message\n"
                "  /quit, /exit          - Exit the application"
            )
            chat.append_message("System", help_text)
        elif base in ("/quit", "/exit"):
            self.action_quit()
        else:
            chat.append_message("System", f"Unknown command: {base}. Type /help for assistance.")

    def action_toggle_context(self) -> None:
        panel = self.query_one(ContextPanel)
        panel.display = not panel.display
        if panel.display:
            self.run_worker(self._refresh_context_panel())

    def action_toggle_tasklist(self) -> None:
        panel = self.query_one("#side-tasklist")
        panel.display = not panel.display

    def action_toggle_notifications(self) -> None:
        panel = self.query_one("#notification-panel")
        panel.toggle_class("hidden")

    def action_cycle_theme(self) -> None:
        """Cycles forward through available themes."""
        themes = sorted(self.available_themes)
        if not themes:
            return

        try:
            current_index = themes.index(self.theme)
            next_index = (current_index + 1) % len(themes)
        except ValueError:
            next_index = 0

        self._set_theme(themes[next_index])

    def _set_theme(self, theme_name: str) -> None:
        normalized_theme = normalize_theme_name(theme_name)
        resolved_theme = (
            normalized_theme if normalized_theme in self.available_themes else DEFAULT_THEME
        )
        if resolved_theme != normalized_theme:
            logger.warning(
                "Unknown theme '%s'; falling back to '%s'.",
                theme_name,
                DEFAULT_THEME,
            )
        self.theme = resolved_theme
        self.settings.theme = resolved_theme
        self.settings.save()

    async def action_handle_ctrl_c(self) -> None:
        """Handles Ctrl+C: Cancel job first, then double-tap to quit."""
        now = time.time()

        if self.job_in_progress and self.current_job_id:
            self.query_one(ChatPanel).add_system_message("Cancelling job...")
            await self.executor.cancel_job(self.current_job_id)
            # Reset double-tap timer so they don't accidentally quit while cancelling
            self._last_ctrl_c_time = now
            return

        if now - self._last_ctrl_c_time < 2.0:
            await self.action_quit()
        else:
            self.query_one(ChatPanel).add_system_message("Press Ctrl+C again to quit.")
            self._last_ctrl_c_time = now

    async def action_quit(self) -> None:
        chat = self.query_one(ChatPanel)
        chat.add_system_message("Shutting down...")
        await self.executor.stop()
        self.exit()

    async def on_unmount(self) -> None:
        """Ensure cleanup even if app exits via other means."""
        await self.executor.stop()


if __name__ == "__main__":
    app = BrokkApp()
    app.run()
