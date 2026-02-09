import asyncio
import time
from pathlib import Path
from typing import Any, Dict, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.widgets import Header, Footer

from brokk_tui.executor import ExecutorError, ExecutorManager
from brokk_tui.widgets.chat_panel import ChatPanel
from brokk_tui.widgets.context_panel import ContextPanel
from brokk_tui.widgets.tasklist_panel import TaskListPanel

class BrokkApp(App):
    """The main Brokk TUI application."""

    CSS_PATH = "styles/app.tcss"
    BINDINGS = [
        Binding("ctrl+c", "handle_ctrl_c", "Quit", show=True),
        Binding("ctrl+l", "toggle_context", "Context", show=True),
        Binding("ctrl+r", "toggle_tasklist", "Tasks", show=True),
    ]

    def __init__(self, workspace_dir: Optional[Path] = None, jar_path: Optional[Path] = None) -> None:
        super().__init__()
        self.executor = ExecutorManager(workspace_dir or Path.cwd(), jar_path)
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
        chat.add_system_message("Starting Brokk executor...")
        self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())

    async def _start_executor(self) -> None:
        chat = self.query_one(ChatPanel)
        try:
            await self.executor.start()
            chat.add_system_message("Executor process started, initializing session...")
            await self.executor.create_session()
            
            if await self.executor.wait_ready():
                chat.add_system_message(f"Ready! Using model: [bold]{self.current_model}[/]")
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
            if self.executor._process and not self.executor.check_alive():
                chat = self.query_one(ChatPanel)
                chat.add_system_message("Executor process crashed unexpectedly.", level="ERROR")
                break

    async def _refresh_context_panel(self) -> None:
        """Fetches latest context and updates context and task list panels."""
        try:
            context_data = await self.executor.get_context()
            self.query_one(ContextPanel).refresh_context(context_data)
            self.query_one(TaskListPanel).refresh_tasklist(context_data)
        except Exception as e:
            # Silently fail for background refreshes unless panel is visible?
            # For now, just log or ignore to avoid spamming the chat
            pass

    def on_chat_panel_submitted(self, message: ChatPanel.Submitted) -> None:
        text = message.text.strip()
        if text.startswith("/"):
            self._handle_command(text)
        else:
            self.query_one(ChatPanel).add_user_message(text)
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
                reasoning_level_code=self.reasoning_level_code
            )
            async for event in self.executor.stream_events(self.current_job_id):
                self._handle_event(event)
        except Exception as e:
            chat.add_system_message(f"Job failed or network error: {e}", level="ERROR")
        finally:
            self.job_in_progress = False
            self.current_job_id = None

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
                is_terminal=data.get("isTerminal", False)
            )
        elif event_type == "NOTIFICATION":
            level = data.get("level", "INFO")
            msg = data.get("message", "")
            chat.add_system_message(msg, level=level)
        elif event_type == "ERROR":
            msg = data.get("message", "Unknown error")
            chat.add_system_message(msg, level="ERROR")
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
            chat.add_system_message(f"Model changed to: [bold]{self.current_model}[/]")
        elif base == "/model-code" and len(parts) > 1:
            self.code_model = parts[1]
            chat.add_system_message(f"Code model changed to: [bold]{self.code_model}[/]")
        elif base == "/reasoning" and len(parts) > 1:
            self.reasoning_level = parts[1]
            chat.add_system_message(f"Reasoning level changed to: [bold]{self.reasoning_level}[/]")
        elif base == "/reasoning-code" and len(parts) > 1:
            self.reasoning_level_code = parts[1]
            chat.add_system_message(f"Code reasoning level changed to: [bold]{self.reasoning_level_code}[/]")
        elif base == "/help":
            help_text = (
                "Available commands:\n"
                "  /model <name>         - Change the planner LLM model\n"
                "  /model-code <name>    - Change the code LLM model\n"
                "  /reasoning <level>    - Set reasoning level for planner\n"
                "  /reasoning-code <level> - Set reasoning level for code model\n"
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
