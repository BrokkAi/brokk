import asyncio
import logging
import random
import time
from pathlib import Path
from typing import Any, Dict, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.widgets import Footer, Header

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.prompt_history import append_prompt, clear_history, load_history
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
        Binding("ctrl+m", "toggle_mode", "Mode", show=True),
        Binding("ctrl+shift+m", "toggle_mode", "Mode", show=False),
        Binding("f3", "toggle_mode", "Mode", show=True),
        Binding("f2", "change_theme", "Theme Palette", show=True),
    ]

    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = True,
        executor: Optional[ExecutorManager] = None,
        session_id: Optional[str] = None,
        resume_session: bool = True,
    ) -> None:
        super().__init__()
        self.executor = executor or ExecutorManager(
            workspace_dir or Path.cwd(),
            jar_path,
            executor_version=executor_version,
            executor_snapshot=executor_snapshot,
        )
        self.requested_session_id = session_id
        self.resume_session = resume_session
        self.settings = Settings.load()
        self._set_theme(self.settings.theme)
        self.agent_mode = "LUTZ"
        self.sub_title = f"Mode: {self.agent_mode}"
        self.current_model = "gpt-5.2"
        self.code_model: Optional[str] = "gemini-3-flash-preview"
        self.reasoning_level: Optional[str] = "low"
        self.reasoning_level_code: Optional[str] = "disable"
        self.job_in_progress = False
        self.current_job_id: Optional[str] = None
        self._pending_prompt: Optional[str] = None
        self._last_ctrl_c_time: float = 0
        self._executor_ready: bool = False
        self._reported_refresh_errors: set[str] = set()

    @property
    def current_mode(self) -> str:
        """Alias for agent_mode used by tests and for unified access."""
        return self.agent_mode

    @current_mode.setter
    def current_mode(self, value: str) -> None:
        self.agent_mode = value

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
        chat.add_system_message("Starting Brokk executor...")

        # Load initial prompt history for arrow-key navigation
        history = load_history(self.executor.workspace_dir)
        chat.set_history(history)

        self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())
        self.run_worker(self._poll_tasklist())
        self.run_worker(self._poll_context())

    async def _start_executor(self) -> None:
        chat = self.query_one(ChatPanel)
        try:
            from brokk_code.session_persistence import (
                get_session_zip_path,
                load_last_session_id,
                save_last_session_id,
            )

            await self.executor.start()

            # Fetch and display effective build hint immediately
            try:
                live_info = await self.executor.get_health_live()
                version = live_info.get("version", "unknown")
                proto = live_info.get("protocolVersion", "unknown")
                eid = live_info.get("execId", "unknown")
                chat.add_system_message(
                    f"Connected to executor {eid} (version: {version}, protocol: {proto})"
                )
            except Exception as e:
                logger.debug("Failed to fetch health/live info", exc_info=True)

            # Session Management Logic
            session_to_resume = self.requested_session_id
            if not session_to_resume and self.resume_session:
                session_to_resume = load_last_session_id(self.executor.workspace_dir)

            resumed = False
            if session_to_resume:
                zip_path = get_session_zip_path(self.executor.workspace_dir, session_to_resume)
                if zip_path.exists():
                    try:
                        chat.add_system_message(f"Resuming session {session_to_resume}...")
                        zip_bytes = zip_path.read_bytes()
                        await self.executor.import_session_zip(
                            zip_bytes, session_id=session_to_resume
                        )
                        resumed = True
                    except Exception as e:
                        logger.warning("Failed to resume session %s: %s", session_to_resume, e)

            if not resumed:
                await self.executor.create_session()

            if self.executor.session_id:
                save_last_session_id(self.executor.workspace_dir, self.executor.session_id)

            if await self.executor.wait_ready():
                self._executor_ready = True
                chat.add_system_message("Ready!")
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

    async def _poll_tasklist(self) -> None:
        """Periodically refreshes the task list details."""
        while True:
            if self._executor_ready:
                # We poll even if a job is running, as /v1/tasklist is low impact
                try:
                    tasklist_data = await self.executor.get_tasklist()
                    self.query_one(TaskListPanel).update_tasklist_details(tasklist_data)
                except Exception:
                    logger.debug("Periodic tasklist poll failed", exc_info=True)
            await asyncio.sleep(15.0)

    async def _poll_context(self) -> None:
        """Periodically refreshes the context panel."""
        while True:
            if self._executor_ready:
                # refresh_context_panel handles both ContextPanel and TaskListPanel overview
                await self._refresh_context_panel()
            # Sleep 10-15s with jitter
            await asyncio.sleep(random.uniform(10.0, 15.0))

    async def _refresh_context_panel(self) -> None:
        """Fetches latest context and updates context, task list, and chat panels."""
        if not self._executor_ready:
            return
        try:
            context_data = await self.executor.get_context()
            self.query_one(ContextPanel).refresh_context(context_data)
            self.query_one(TaskListPanel).refresh_tasklist(context_data)

            # Update token usage in ChatPanel
            used = context_data.get("usedTokens", 0)
            max_tokens = context_data.get("maxTokens")
            self.query_one(ChatPanel).set_token_usage(used, max_tokens)

            # Clear error tracking on success
            self._reported_refresh_errors.clear()
        except Exception as e:
            # Rate-limit notifications to once per unique exception type per session
            err_key = type(e).__name__
            if err_key not in self._reported_refresh_errors:
                chat = self.query_one(ChatPanel)
                chat.add_system_message(f"Context refresh failed: {e}", level="ERROR")
                self._reported_refresh_errors.add(err_key)
            logger.debug("Failed to refresh context panel", exc_info=True)

    def on_chat_panel_submitted(self, message: ChatPanel.Submitted) -> None:
        """
        Handles user input from the chat panel.

        Persistence Policy:
        - Only non-command prompts (text not starting with '/') are recorded.
        - Prompts are recorded at the moment of submission, regardless of whether
          they trigger a cancellation or are later aborted.
        - History is stored in the project-specific directory:
          `self.executor.workspace_dir / ".brokk" / "prompts.json"`
        """
        text = message.text.strip()
        if text.startswith("/"):
            self._handle_command(text)
        elif text:
            append_prompt(
                self.executor.workspace_dir, text, max_history=self.settings.prompt_history_size
            )
            chat = self.query_one(ChatPanel)
            chat.add_history_entry(text)
            chat.add_user_message(text)

            if self.job_in_progress and self.current_job_id:
                self._pending_prompt = text
                chat.add_system_message("Interrupting current job to start new request...")
                self.run_worker(self.executor.cancel_job(self.current_job_id))
            else:
                self.run_worker(self._run_job(text))

    async def _run_job(self, task_input: str) -> None:
        self.job_in_progress = True
        chat = self.query_one(ChatPanel)
        chat.set_job_running(True)
        chat.set_response_pending()
        try:
            self.current_job_id = await self.executor.submit_job(
                task_input,
                self.current_model,
                code_model=self.code_model,
                reasoning_level=self.reasoning_level,
                reasoning_level_code=self.reasoning_level_code,
                mode=self.current_mode,
            )
            async for event in self.executor.stream_events(self.current_job_id):
                self._handle_event(event)
        except Exception as e:
            chat.add_system_message(f"Job failed or network error: {e}", level="ERROR")
        finally:
            self.job_in_progress = False
            self.current_job_id = None
            chat.set_response_finished()

            if self._pending_prompt:
                next_prompt = self._pending_prompt
                self._pending_prompt = None
                # Small delay to ensure UI/state settles before restarting
                await asyncio.sleep(0.05)
                self.run_worker(self._run_job(next_prompt))
            else:
                chat.set_job_running(False)

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
            # Note: set_job_running(False) happens in _run_job finally block
        elif event_type == "STATE_HINT":
            hint_name = data.get("name")
            if hint_name in ("contextHistoryUpdated", "workspaceUpdated"):
                self.run_worker(self._refresh_context_panel())

    def _set_mode(self, new_mode: str, *, announce: bool = True) -> None:
        """Sets the agent mode, updates the subtitle, and optionally announces to chat."""
        self.agent_mode = new_mode
        self.sub_title = f"Mode: {self.agent_mode}"
        if announce:
            chat = self.query_one(ChatPanel)
            chat.add_system_message_markup(
                f"Mode changed to: [bold]{self.agent_mode}[/]", level="WARNING"
            )

    def _render_info(self) -> None:
        """Renders current status and configuration info to the chat."""
        chat = self.query_one(ChatPanel)
        status = (
            "[bold green]Ready[/]" if self._executor_ready else "[bold yellow]Initializing...[/]"
        )
        jar_path = self.executor.resolved_jar_path or "Unknown"

        info_markup = (
            f"Status: {status}\n"
            f"Workspace: [bold]{self.executor.workspace_dir}[/]\n"
            f"Executor JAR: [bold]{jar_path}[/]\n"
            f"Mode: [bold]{self.agent_mode}[/]\n"
            f"Planner Model: [bold]{self.current_model}[/] (reasoning: [bold]{self.reasoning_level}[/])\n"
            f"Code Model: [bold]{self.code_model}[/] (reasoning: [bold]{self.reasoning_level_code}[/])"
        )
        chat.add_system_message_markup(info_markup)

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
        elif base in ("/theme", "/palette"):
            if len(parts) > 1:
                chat.add_system_message(
                    "Theme selection now uses the theme palette. Use /theme with no arguments."
                )
            self.action_change_theme()
        elif base in ("/ask", "/search", "/lutz"):
            self._set_mode(base[1:].upper())
        elif base == "/info":
            self._render_info()
        elif base == "/history":
            history = load_history(self.executor.workspace_dir)
            if not history:
                chat.add_system_message("Prompt history is empty.")
            else:
                formatted = "\n".join(f"{i + 1}. {p}" for i, p in enumerate(history))
                chat.append_message("System", f"Recent Prompts:\n{formatted}")
        elif base == "/history-clear":
            clear_history(self.executor.workspace_dir)
            chat.set_history([])
            chat.add_system_message("Prompt history cleared.")
        elif base == "/help":
            help_text = (
                "Available commands:\n"
                "  /ask                  - Set mode to ASK (questions only)\n"
                "  /search               - Set mode to SEARCH (read-only code search)\n"
                "  /lutz                 - Set mode to LUTZ (default; full agent access)\n"
                "  /model <name>         - Change the planner LLM model\n"
                "  /model-code <name>    - Change the code LLM model\n"
                "  /reasoning <level>    - Set reasoning level for planner\n"
                "  /reasoning-code <level> - Set reasoning level for code model\n"
                "  /theme, /palette      - Open the theme palette\n"
                "  /history              - Show recent prompt history\n"
                "  /history-clear        - Clear prompt history\n"
                "  /info                 - Show current configuration and status\n"
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
        if panel.display and self._executor_ready:
            self.run_worker(self._refresh_context_panel())

    def action_toggle_tasklist(self) -> None:
        panel = self.query_one("#side-tasklist")
        panel.display = not panel.display

    def action_toggle_mode(self) -> None:
        """Cycles through agent modes: LUTZ -> ASK -> SEARCH -> LUTZ."""
        modes = ["LUTZ", "ASK", "SEARCH"]
        try:
            current_index = modes.index(self.agent_mode)
        except ValueError:
            current_index = 0

        next_index = (current_index + 1) % len(modes)
        self._set_mode(modes[next_index])

    def action_toggle_notifications(self) -> None:
        panel = self.query_one("#notification-panel")
        panel.toggle_class("hidden")

    def _set_theme(self, theme_name: str) -> None:
        normalized_theme = normalize_theme_name(theme_name.lower())
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

    def watch_theme(self, old_theme: str, new_theme: str) -> None:
        """Persist any theme changes, including those from the Textual theme palette."""
        if old_theme == new_theme:
            return
        self.settings.theme = new_theme
        self.settings.save()

    async def action_handle_ctrl_c(self) -> None:
        """Handles Ctrl+C: Cancel job first, then double-tap to quit."""
        now = time.time()

        if self.job_in_progress and self.current_job_id:
            self._pending_prompt = None  # Clear any pending prompt on manual cancel
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

    async def _export_session(self) -> None:
        """Best-effort export of the current session zip to workspace cache."""
        if not self.executor.session_id or not self._executor_ready:
            return

        from brokk_code.session_persistence import get_session_zip_path

        try:
            session_id = self.executor.session_id
            zip_bytes = await self.executor.download_session_zip(session_id)
            zip_path = get_session_zip_path(self.executor.workspace_dir, session_id)
            zip_path.write_bytes(zip_bytes)
            logger.info("Session %s exported to %s", session_id, zip_path)
        except Exception as e:
            logger.warning("Failed to export session zip on shutdown: %s", e)

    async def action_quit(self) -> None:
        chat = self.query_one(ChatPanel)
        chat.add_system_message("Shutting down...")
        await self._export_session()
        await self.executor.stop()
        self.exit()

    async def on_unmount(self) -> None:
        """Ensure cleanup even if app exits via other means."""
        # Note: action_quit already calls _export_session.
        # on_unmount is a fallback for other exit paths.
        await self._export_session()
        await self.executor.stop()


if __name__ == "__main__":
    app = BrokkApp()
    app.run()
