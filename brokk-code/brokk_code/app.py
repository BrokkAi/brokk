import asyncio
import logging
import random
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from textual.app import App, ComposeResult, ScreenStackError
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Footer, Header, ListItem, ListView, Static
from textual.widgets._footer import FooterKey

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.prompt_history import append_prompt, clear_history, load_history
from brokk_code.settings import DEFAULT_THEME, Settings, normalize_theme_name
from brokk_code.widgets.chat_panel import ChatInput, ChatPanel
from brokk_code.widgets.context_panel import ContextPanel
from brokk_code.widgets.status_line import StatusLine
from brokk_code.widgets.tasklist_panel import TaskListPanel
from brokk_code.workspace import resolve_workspace_dir

logger = logging.getLogger(__name__)


class OrderedFooter(Footer):
    def compose(self) -> ComposeResult:
        if not self._bindings_ready:
            return

        active_bindings = self.screen.active_bindings

        def first_binding_for_action(action: str) -> tuple[Binding, bool, str] | None:
            for _key, binding, enabled, tooltip in active_bindings.values():
                if binding.action == action:
                    return binding, enabled, tooltip
            return None

        order = [
            ("toggle_mode", "Mode"),
            ("select_model", "Model"),
            ("select_reasoning", "Reasoning"),
            ("toggle_context", "Context"),
            ("toggle_tasklist", "Tasks"),
            ("toggle_notifications", "Notifications"),
            ("toggle_statusline", "Status"),
        ]

        for action, fallback_desc in order:
            found = first_binding_for_action(action)
            if not found:
                continue
            binding, enabled, tooltip = found
            yield FooterKey(
                binding.key,
                self.app.get_key_display(binding),
                binding.description or fallback_desc,
                binding.action,
                disabled=not enabled,
                tooltip=tooltip or binding.description,
            ).data_bind(compact=Footer.compact)

        # Always render Ctrl+P as "Settings", regardless of Textual's internal "palette" label.
        try:
            _node, binding, enabled, tooltip = active_bindings[self.app.COMMAND_PALETTE_BINDING]
        except KeyError:
            return
        yield FooterKey(
            binding.key,
            self.app.get_key_display(binding),
            "Settings",
            binding.action,
            disabled=not enabled,
            tooltip=tooltip or "Open settings",
            classes="-command-palette",
        ).data_bind(compact=Footer.compact)


class ContextModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the context panel."""

    BINDINGS = [
        Binding("escape", "close_context", "Close", show=False),
        Binding("ctrl+l", "close_context", "Close", show=False),
    ]

    def __init__(self, on_close: Callable[[], None]) -> None:
        super().__init__()
        self._on_close = on_close

    def compose(self) -> ComposeResult:
        with Vertical(id="context-modal-container"):
            yield ContextPanel(id="context-panel")

    def on_mount(self) -> None:
        self.query_one(ContextPanel).focus()

    def action_close_context(self) -> None:
        self._on_close()
        self.dismiss(None)


class ModelSelectModal(ModalScreen[str]):
    """A modal for selecting from available models."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self, models: List[str]) -> None:
        super().__init__()
        self.models = models
        self._item_id_to_model: Dict[str, str] = {
            f"model-{idx}": model for idx, model in enumerate(models)
        }

    def compose(self) -> ComposeResult:
        with Vertical(id="model-select-container"):
            yield Static("Select Model", id="model-select-title")
            with VerticalScroll(id="model-select-list-wrap"):
                yield ListView(
                    *[
                        ListItem(Static(model_name), id=item_id)
                        for item_id, model_name in self._item_id_to_model.items()
                    ],
                    id="model-select-list",
                )

    def on_mount(self) -> None:
        self.query_one("#model-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return
        model_name = self._item_id_to_model.get(message.item.id)
        if model_name:
            self.dismiss(model_name)


class ReasoningSelectModal(ModalScreen[str]):
    """A modal for selecting the reasoning level."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self, levels: List[str], current: str) -> None:
        super().__init__()
        self.levels = levels
        self.current = current
        self._item_id_to_level: Dict[str, str] = {
            f"level-{idx}": level for idx, level in enumerate(levels)
        }

    def compose(self) -> ComposeResult:
        with Vertical(id="reasoning-select-container"):
            yield Static("Select Reasoning", id="reasoning-select-title")
            with VerticalScroll(id="reasoning-select-list-wrap"):
                yield ListView(
                    *[
                        ListItem(
                            Static(f"{'[x]' if level == self.current else '[ ]'} {level}"),
                            id=item_id,
                        )
                        for item_id, level in self._item_id_to_level.items()
                    ],
                    id="reasoning-select-list",
                )

    def on_mount(self) -> None:
        self.query_one("#reasoning-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return
        level = self._item_id_to_level.get(message.item.id)
        if level:
            self.dismiss(level)


class BrokkApp(App):
    """The main Brokk TUI application."""

    CSS_PATH = "styles/app.tcss"
    COMMAND_PALETTE_DISPLAY = "Settings"
    BINDINGS = [
        # Footer/help-bar ordering: Mode, Model, Reasoning, Context,
        # Tasks, Notifications, Settings
        Binding("ctrl+g", "toggle_mode", "Mode", show=True),
        Binding("ctrl+c", "handle_ctrl_c", "Quit", show=True),
        Binding("ctrl+u", "select_model", "Model", show=True),
        Binding("ctrl+e", "select_reasoning", "Reasoning", show=True),
        Binding("ctrl+l", "toggle_context", "Context", show=True),
        Binding("ctrl+n", "toggle_notifications", "Notifications", show=True),
        Binding("ctrl+t", "toggle_tasklist", "Tasks", show=True),
        Binding("ctrl+s", "toggle_statusline", "Status", show=True),
        Binding("ctrl+p", "command_palette", "Settings", show=True),
        Binding("ctrl+j", "task_next", "Task Next", show=False),
        Binding("ctrl+k", "task_prev", "Task Prev", show=False),
        Binding("ctrl+space", "task_toggle", "Task Toggle", show=False),
        Binding("f3", "toggle_mode", "Mode", show=False),
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
        vendor: Optional[str] = None,
    ) -> None:
        super().__init__()
        if executor:
            self.executor = executor
            if workspace_dir:
                self.executor.workspace_dir = resolve_workspace_dir(workspace_dir)
            if vendor is not None:
                self.executor.vendor = vendor
        else:
            self.executor = ExecutorManager(
                resolve_workspace_dir(workspace_dir or Path.cwd()),
                jar_path,
                executor_version=executor_version,
                executor_snapshot=executor_snapshot,
                vendor=vendor,
            )
        self.requested_session_id = session_id
        self.resume_session = resume_session
        self.settings = Settings.load()
        self._set_theme(self.settings.theme)
        self.agent_mode = "LUTZ"
        self.sub_title = f"Mode: {self.agent_mode}"

        # Initialize model and reasoning settings from persisted Settings if present,
        # otherwise fall back to safe defaults.
        # We accept persisted values as-is at startup; validation against the
        # executor model catalog can occur later if needed.
        self.current_model = (
            str(self.settings.last_model).strip() if self.settings.last_model else "gpt-5.2"
        )
        self.code_model = (
            str(self.settings.last_code_model).strip()
            if self.settings.last_code_model
            else "gemini-3-flash-preview"
        )
        self.reasoning_level = (
            str(self.settings.last_reasoning_level).strip()
            if self.settings.last_reasoning_level
            else "low"
        )
        self.reasoning_level_code = (
            str(self.settings.last_code_reasoning_level).strip()
            if self.settings.last_code_reasoning_level
            else "disable"
        )
        self.auto_commit = (
            bool(self.settings.last_auto_commit)
            if isinstance(self.settings.last_auto_commit, bool)
            else True
        )
        self.current_branch = "unknown"
        self.job_in_progress = False
        self.current_job_id: Optional[str] = None
        self._pending_prompt: Optional[str] = None
        self._pending_updated_at: float = 0
        self._pending_generation: int = 0
        self._pending_min_wait_until: float = 0.0
        self._resubmit_grace_s: float = 0.2
        self._last_ctrl_c_time: float = 0
        self._executor_ready: bool = False
        self._refresh_context_lock = asyncio.Lock()
        self._reported_refresh_errors: set[str] = set()

        # Shutdown coordination flags and lock
        self._shutting_down: bool = False
        self._shutdown_completed: bool = False
        self._session_exported: bool = False
        self._shutdown_lock = asyncio.Lock()

    @property
    def current_mode(self) -> str:
        """Alias for agent_mode used by tests and for unified access."""
        return self.agent_mode

    @current_mode.setter
    def current_mode(self, value: str) -> None:
        self.agent_mode = value

    def _maybe_chat(self) -> Optional[ChatPanel]:
        """Safely attempt to get the ChatPanel, returning None if the UI isn't mounted."""
        try:
            return self.query_one(ChatPanel)
        except (ScreenStackError, Exception):
            return None

    def _maybe_statusline(self) -> Optional[StatusLine]:
        """Safely attempt to get the StatusLine, returning None if the UI isn't mounted."""
        try:
            chat = self._maybe_chat()
            if chat:
                return chat.query_one(StatusLine)
            return self.query_one(StatusLine)
        except (ScreenStackError, Exception):
            return None

    def _update_statusline(self) -> None:
        """Collect current state and update the mounted StatusLine (best-effort)."""
        chat = self._maybe_chat()
        if not chat:
            return
        try:
            status = chat.query_one("#status-line", StatusLine)
        except Exception:
            return
        if not status:
            return
        try:
            workspace = None
            try:
                if getattr(self, "executor", None) is not None:
                    ws = getattr(self.executor, "workspace_dir", None)
                    if ws is not None:
                        workspace = str(ws)
            except Exception:
                workspace = None

            status.update_status(
                mode=getattr(self, "current_mode", getattr(self, "agent_mode", "unknown")),
                model=getattr(self, "current_model", None),
                reasoning=getattr(self, "reasoning_level", None),
                workspace=workspace,
                branch=getattr(self, "current_branch", "unknown"),
            )
        except Exception:
            # Swallow all errors when updating UI that's possibly not mounted in tests.
            return

    def compose(self) -> ComposeResult:
        yield Header()
        with Horizontal():
            yield ChatPanel(id="chat-main")
            yield TaskListPanel(id="side-tasklist")
        yield OrderedFooter(show_command_palette=False)

    async def on_mount(self) -> None:
        chat = self._maybe_chat()
        logger.info("Using workspace directory: %s", self.executor.workspace_dir)
        if chat:
            chat.set_token_bar_visible(True)
            chat.add_system_message("Starting Brokk executor...")

            # Load initial prompt history for arrow-key navigation
            history = load_history(self.executor.workspace_dir)
            chat.set_history(history)

        self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())
        self.run_worker(self._poll_tasklist())
        self.run_worker(self._poll_context())

    async def _start_executor(self) -> None:
        chat = self._maybe_chat()
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
                msg = f"Connected to executor {eid} (version: {version}, protocol: {proto})"
                if chat:
                    chat.add_system_message(msg)
                else:
                    logger.info(msg)
            except Exception:
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
                        msg = f"Resuming session {session_to_resume}..."
                        if chat:
                            chat.add_system_message(msg)
                        else:
                            logger.info(msg)
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
                if chat:
                    chat.add_system_message("Ready!")
                else:
                    logger.info("Executor ready")
                # Initial context load
                self.run_worker(self._refresh_context_panel())
            else:
                msg = "Executor failed to become ready (timeout)."
                if chat:
                    chat.add_system_message(msg, level="ERROR")
                else:
                    logger.error(msg)
        except ExecutorError as e:
            if chat:
                chat.add_system_message(str(e), level="ERROR")
            else:
                logger.error(str(e))
        except Exception as e:
            msg = f"Unexpected startup error: {e}"
            if chat:
                chat.add_system_message(msg, level="ERROR")
            else:
                logger.error(msg)

    async def _monitor_executor(self) -> None:
        """Background worker to check if the executor dies unexpectedly."""
        while True:
            await asyncio.sleep(2.0)
            if not self.executor.check_alive():
                msg = "Executor process crashed unexpectedly."
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message(msg, level="ERROR")
                else:
                    logger.error(msg)
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

        async with self._refresh_context_lock:
            try:
                context_data = await self.executor.get_context()
                self.current_branch = context_data.get("branch", "unknown")

                # UI updates are best-effort if screen is not on stack
                try:
                    if isinstance(self.screen, ContextModalScreen):
                        self.screen.query_one(ContextPanel).refresh_context(context_data)
                    else:
                        self.query_one(ContextPanel).refresh_context(context_data)
                except (ScreenStackError, Exception):
                    pass
                try:
                    task_list = self.query_one(TaskListPanel)
                    if not task_list.has_detailed_info:
                        task_list.refresh_tasklist(context_data)
                except (ScreenStackError, Exception):
                    pass

                # Update token usage in ChatPanel
                chat = self._maybe_chat()
                if chat:
                    used = context_data.get("usedTokens", 0)
                    max_tokens = context_data.get("maxTokens")
                    fragments = context_data.get("fragments")
                    chat.set_token_usage(used, max_tokens, fragments)

                self._update_statusline()

                # Clear error tracking on success
                self._reported_refresh_errors.clear()
            except Exception as e:
                # Rate-limit notifications to once per unique exception type per session
                err_key = type(e).__name__
                if err_key not in self._reported_refresh_errors:
                    msg = f"Context refresh failed: {e}"
                    chat = self._maybe_chat()
                    if chat:
                        chat.add_system_message(msg, level="ERROR")
                    else:
                        logger.error(msg)
                    self._reported_refresh_errors.add(err_key)
                logger.debug("Failed to refresh context panel", exc_info=True)

    def on_context_panel_action_requested(self, message: ContextPanel.ActionRequested) -> None:
        self.run_worker(self._execute_context_action(message))

    async def _execute_context_action(self, message: ContextPanel.ActionRequested) -> None:
        if not self._executor_ready:
            return

        chat = self._maybe_chat()
        if isinstance(self.screen, ContextModalScreen):
            panel = self.screen.query_one(ContextPanel)
        else:
            panel = self.query_one(ContextPanel)
        selected_fragments = panel.selected_fragments
        try:
            match message.action:
                case "drop_selected":
                    await self.executor.drop_context_fragments(message.fragment_ids)
                    if chat:
                        chat.add_system_message(f"Dropped {len(message.fragment_ids)} fragment(s).")
                case "drop_all":
                    await self.executor.drop_all_context()
                    if chat:
                        chat.add_system_message("Dropped all context fragments.")
                case "toggle_pin_selected":
                    updates = self._collect_pin_updates(selected_fragments)
                    for fragment_id, pinned in updates:
                        await self.executor.set_context_fragment_pinned(fragment_id, pinned)
                    if chat and updates:
                        chat.add_system_message(
                            f"Updated pin state for {len(updates)} fragment(s)."
                        )
                case "toggle_readonly_selected":
                    updates = self._collect_readonly_updates(selected_fragments)
                    for fragment_id, readonly in updates:
                        await self.executor.set_context_fragment_readonly(fragment_id, readonly)
                    if chat and updates:
                        chat.add_system_message(
                            f"Updated read-only state for {len(updates)} editable fragment(s)."
                        )
                case "compress_history":
                    await self.executor.compress_context_history()
                    if chat:
                        chat.add_system_message("Compressing history...")
                case "clear_history":
                    await self.executor.clear_context_history()
                    if chat:
                        chat.add_system_message("Cleared history.")
                case _:
                    logger.warning("Unknown context action requested: %s", message.action)
                    return

            await self._refresh_context_panel()
        except Exception as e:
            if chat:
                chat.add_system_message(f"Context action failed: {e}", level="ERROR")
            else:
                logger.error("Context action failed: %s", e)

    @staticmethod
    def _collect_pin_updates(selected_fragments: List[Dict[str, Any]]) -> List[tuple[str, bool]]:
        updates: List[tuple[str, bool]] = []
        for fragment in selected_fragments:
            fragment_id = str(fragment.get("id", "")).strip()
            if not fragment_id:
                continue
            current = bool(fragment.get("pinned", False))
            updates.append((fragment_id, not current))
        return updates

    @staticmethod
    def _collect_readonly_updates(
        selected_fragments: List[Dict[str, Any]],
    ) -> List[tuple[str, bool]]:
        updates: List[tuple[str, bool]] = []
        for fragment in selected_fragments:
            if not bool(fragment.get("editable", False)):
                continue
            fragment_id = str(fragment.get("id", "")).strip()
            if not fragment_id:
                continue
            current = bool(fragment.get("readonly", False))
            updates.append((fragment_id, not current))
        return updates

    async def _ensure_tasklist_data(self) -> Optional[Dict[str, Any]]:
        panel = self.query_one(TaskListPanel)
        data = panel.tasklist_data_for_update()
        if data is not None:
            return data
        data = await self.executor.get_tasklist()
        panel.update_tasklist_details(data)
        return panel.tasklist_data_for_update()

    async def _persist_tasklist(self, data: Dict[str, Any]) -> Dict[str, Any]:
        saved = await self.executor.set_tasklist(data)
        self.query_one(TaskListPanel).update_tasklist_details(saved)
        return saved

    async def _toggle_selected_task(self) -> None:
        chat = self.query_one(ChatPanel)
        panel = self.query_one(TaskListPanel)
        selected = panel.selected_task()
        if not selected:
            chat.add_system_message("No task selected.")
            return

        task_id = str(selected.get("id", "")).strip()
        if not task_id:
            chat.add_system_message("Selected task has no ID and cannot be updated.", level="ERROR")
            return

        try:
            data = await self._ensure_tasklist_data()
            if not data:
                chat.add_system_message("No task list active.")
                return
            tasks = data.get("tasks", [])
            for task in tasks:
                if str(task.get("id", "")).strip() == task_id:
                    task["done"] = not bool(task.get("done", False))
                    break
            await self._persist_tasklist(data)
        except Exception as e:
            chat.add_system_message(f"Failed to toggle task: {e}", level="ERROR")

    async def _delete_selected_task(self) -> None:
        chat = self.query_one(ChatPanel)
        panel = self.query_one(TaskListPanel)
        selected = panel.selected_task()
        if not selected:
            chat.add_system_message("No task selected.")
            return

        task_id = str(selected.get("id", "")).strip()
        if not task_id:
            chat.add_system_message("Selected task has no ID and cannot be deleted.", level="ERROR")
            return

        try:
            data = await self._ensure_tasklist_data()
            if not data:
                chat.add_system_message("No task list active.")
                return
            before = len(data.get("tasks", []))
            data["tasks"] = [
                task for task in data.get("tasks", []) if str(task.get("id", "")).strip() != task_id
            ]
            if len(data["tasks"]) == before:
                chat.add_system_message("Selected task no longer exists.")
                return
            await self._persist_tasklist(data)
        except Exception as e:
            chat.add_system_message(f"Failed to delete task: {e}", level="ERROR")

    async def _add_task(self, title: str) -> None:
        chat = self.query_one(ChatPanel)
        normalized_title = title.strip()
        if not normalized_title:
            chat.add_system_message("Task title cannot be blank.", level="ERROR")
            return
        try:
            data = await self._ensure_tasklist_data()
            if not data:
                data = {"bigPicture": None, "tasks": []}
            tasks = data.get("tasks", [])
            tasks.append({"title": normalized_title, "text": normalized_title, "done": False})
            data["tasks"] = tasks
            await self._persist_tasklist(data)
        except Exception as e:
            chat.add_system_message(f"Failed to add task: {e}", level="ERROR")

    async def _edit_selected_task(self, title: str) -> None:
        chat = self.query_one(ChatPanel)
        panel = self.query_one(TaskListPanel)
        selected = panel.selected_task()
        if not selected:
            chat.add_system_message("No task selected.")
            return

        task_id = str(selected.get("id", "")).strip()
        if not task_id:
            chat.add_system_message("Selected task has no ID and cannot be updated.", level="ERROR")
            return

        normalized_title = title.strip()
        if not normalized_title:
            chat.add_system_message("Task title cannot be blank.", level="ERROR")
            return

        try:
            data = await self._ensure_tasklist_data()
            if not data:
                chat.add_system_message("No task list active.")
                return
            updated = False
            for task in data.get("tasks", []):
                if str(task.get("id", "")).strip() == task_id:
                    task["title"] = normalized_title
                    if not str(task.get("text", "")).strip():
                        task["text"] = normalized_title
                    updated = True
                    break
            if not updated:
                chat.add_system_message("Selected task no longer exists.")
                return
            await self._persist_tasklist(data)
        except Exception as e:
            chat.add_system_message(f"Failed to edit task: {e}", level="ERROR")

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
        raw_text = message.text
        check_text = raw_text.strip()

        if check_text.startswith("/"):
            self._handle_command(check_text)
        elif check_text:
            # Record in history regardless of routing
            append_prompt(
                self.executor.workspace_dir, raw_text, max_history=self.settings.prompt_history_size
            )
            chat = self.query_one(ChatPanel)
            chat.add_history_entry(raw_text)

            chat.add_user_message(raw_text)
            if self.job_in_progress and self.current_job_id:
                self._pending_prompt = raw_text
                now = time.monotonic()
                self._pending_updated_at = now
                self._pending_generation += 1
                self._pending_min_wait_until = max(
                    self._pending_min_wait_until, now + self._resubmit_grace_s
                )
                # Avoid redundant cancellation messages if already pending
                if self._pending_generation == 1:
                    chat.add_system_message("Interrupting current job to start new request...")
                self.run_worker(self.executor.cancel_job(self.current_job_id))
            else:
                self.run_worker(self._run_job(raw_text))

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
                auto_commit=self.auto_commit,
            )
            async for event in self.executor.stream_events(self.current_job_id):
                self._handle_event(event)
        except Exception as e:
            chat.add_system_message(f"Job failed or network error: {e}", level="ERROR")
        finally:
            chat.set_response_finished()

            # Yield to the event loop to allow any rapid subsequent submissions
            # triggered by the cancellation to be processed before we check _pending_prompt.
            await asyncio.sleep(0)

            if self._pending_prompt:
                # Wait for both the grace window (since cancellation)
                # and the stability debounce (since last keystroke/submit).
                debounce_window = 0.05  # 50ms
                while True:
                    now = time.monotonic()
                    current_gen = self._pending_generation
                    elapsed_since_update = now - self._pending_updated_at

                    # We must be past the absolute grace timestamp AND stable
                    # for the debounce window
                    if (
                        now >= self._pending_min_wait_until
                        and elapsed_since_update >= debounce_window
                        and self._pending_generation == current_gen
                    ):
                        break
                    await asyncio.sleep(0.01)

                next_prompt = self._pending_prompt
                self._pending_prompt = None
                self._pending_updated_at = 0
                self._pending_generation = 0
                self._pending_min_wait_until = 0.0

                # Recurse within the same worker context to prevent
                # the app from flickering to 'idle' and allowing race-condition submits.
                # We keep job_in_progress = True during this transition.
                if next_prompt:
                    await self._run_job(next_prompt)
                else:
                    self.job_in_progress = False
                    self.current_job_id = None
                    chat = self._maybe_chat()
                    if chat:
                        chat.set_job_running(False)
            else:
                # Only mark idle once we are sure no more prompts are queued
                self.job_in_progress = False
                self.current_job_id = None
                chat = self._maybe_chat()
                if chat:
                    chat.set_job_running(False)

    def _handle_event(self, event: Dict[str, Any]) -> None:
        event_type = event.get("type")
        data = event.get("data", {})
        chat = self.query_one(ChatPanel)

        if event_type == "LLM_TOKEN":
            chat.append_token(
                token=data.get("token", ""),
                message_type=data.get("messageType", "AI"),
                is_new_message=bool(data.get("isNewMessage", False)),
                is_reasoning=bool(data.get("isReasoning", False)),
                is_terminal=bool(data.get("isTerminal", False)),
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
        # Update statusline if present
        self._update_statusline()
        if announce:
            msg_markup = f"Mode changed to: [bold]{self.agent_mode}[/]"
            chat = self._maybe_chat()
            if chat:
                chat.add_system_message_markup(msg_markup, level="WARNING")
            else:
                logger.info("Mode changed to %s", self.agent_mode)

    def _render_info(self) -> None:
        """Renders current status and configuration info to the chat."""
        chat = self.query_one(ChatPanel)
        status = (
            "[bold green]Ready[/]" if self._executor_ready else "[bold yellow]Initializing...[/]"
        )
        jar_path = self.executor.resolved_jar_path or "Unknown"

        planner_info = (
            f"Planner Model: [bold]{self.current_model}[/] "
            f"(reasoning: [bold]{self.reasoning_level}[/])"
        )
        code_info = (
            f"Code Model: [bold]{self.code_model}[/] "
            f"(reasoning: [bold]{self.reasoning_level_code}[/])"
        )
        info_markup = (
            f"Status: {status}\n"
            f"Workspace: [bold]{self.executor.workspace_dir}[/]\n"
            f"Executor JAR: [bold]{jar_path}[/]\n"
            f"Mode: [bold]{self.agent_mode}[/]\n"
            f"Auto-commit: [bold]{'ON' if self.auto_commit else 'OFF'}[/]\n"
            f"{planner_info}\n"
            f"{code_info}"
        )
        chat.add_system_message_markup(info_markup)

    def _handle_command(self, cmd: str) -> None:
        chat = self.query_one(ChatPanel)
        parts = cmd.split()
        base = parts[0].lower()

        if base == "/model" and len(parts) > 1:
            self.current_model = parts[1]
            # Persist the last-used planner model for subsequent runs
            try:
                self.settings.last_model = self.current_model
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist last_model setting")
            chat.add_system_message_markup(f"Model changed to: [bold]{self.current_model}[/]")
            self._update_statusline()
        elif base == "/model-code" and len(parts) > 1:
            self.code_model = parts[1]
            # Persist the last-used code model
            try:
                self.settings.last_code_model = self.code_model
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist last_code_model setting")
            chat.add_system_message_markup(f"Code model changed to: [bold]{self.code_model}[/]")
        elif base == "/reasoning" and len(parts) > 1:
            self.reasoning_level = parts[1]
            # Persist planner reasoning preference
            try:
                self.settings.last_reasoning_level = self.reasoning_level
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist last_reasoning_level setting")
            chat.add_system_message_markup(
                f"Reasoning level changed to: [bold]{self.reasoning_level}[/]"
            )
            self._update_statusline()
        elif base == "/reasoning-code" and len(parts) > 1:
            self.reasoning_level_code = parts[1]
            # Persist code reasoning preference
            try:
                self.settings.last_code_reasoning_level = self.reasoning_level_code
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist last_code_reasoning_level setting")
            chat.add_system_message_markup(
                f"Code reasoning level changed to: [bold]{self.reasoning_level_code}[/]"
            )
        elif base == "/autocommit":
            if len(parts) == 1:
                state = "ON" if self.auto_commit else "OFF"
                chat.add_system_message_markup(
                    f"Auto-commit mode: [bold]{state}[/]\nUsage: /autocommit on|off|toggle",
                    level="WARNING",
                )
                return

            if len(parts) != 2:
                chat.add_system_message(
                    "Usage: /autocommit on|off|toggle (or true/false/1/0/yes/no)",
                    level="ERROR",
                )
                return

            arg = parts[1].strip().lower()
            truthy = {"on", "true", "1", "yes"}
            falsy = {"off", "false", "0", "no"}
            if arg in truthy:
                new_value = True
            elif arg in falsy:
                new_value = False
            elif arg == "toggle":
                new_value = not self.auto_commit
            else:
                chat.add_system_message(
                    "Usage: /autocommit on|off|toggle (or true/false/1/0/yes/no)",
                    level="ERROR",
                )
                return

            self.auto_commit = new_value
            try:
                self.settings.last_auto_commit = self.auto_commit
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist last_auto_commit setting")

            if self.auto_commit:
                chat.add_system_message_markup("Auto-commit mode: [bold]ON[/]")
            else:
                chat.add_system_message_markup(
                    "Auto-commit mode: [bold]OFF[/] (changes will not be committed automatically)",
                    level="WARNING",
                )
        elif base == "/settings":
            if len(parts) > 1:
                chat.add_system_message("Settings opens from /settings with no arguments.")
            self.action_command_palette()
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
        elif base == "/task":
            panel = self.query_one(TaskListPanel)
            if len(parts) == 1:
                selected = panel.selected_task()
                if not selected:
                    chat.add_system_message(
                        "Task commands: /task next | prev | toggle | delete | "
                        "add <title> | edit <title>"
                    )
                else:
                    done = "[x]" if bool(selected.get("done", False)) else "[ ]"
                    title = str(selected.get("title", "Task")).strip() or "Task"
                    chat.add_system_message(f"Selected task: {done} {title}")
            elif len(parts) >= 2:
                action = parts[1].lower()
                if action == "next":
                    if not panel.move_selection(1):
                        chat.add_system_message("No next task.")
                elif action == "prev":
                    if not panel.move_selection(-1):
                        chat.add_system_message("No previous task.")
                elif action == "toggle":
                    self.run_worker(self._toggle_selected_task())
                elif action == "delete":
                    self.run_worker(self._delete_selected_task())
                elif action == "add":
                    if len(parts) < 3:
                        chat.add_system_message("Usage: /task add <title>")
                    else:
                        self.run_worker(self._add_task(" ".join(parts[2:])))
                elif action == "edit":
                    if len(parts) < 3:
                        chat.add_system_message("Usage: /task edit <title>")
                    else:
                        self.run_worker(self._edit_selected_task(" ".join(parts[2:])))
                else:
                    chat.add_system_message(
                        "Unknown /task command. Use: next, prev, toggle, delete, add, edit."
                    )
        elif base == "/help":
            help_text = (
                "Available commands:\n"
                "  /ask                  - Set mode to ASK (questions only)\n"
                "  /search               - Set mode to SEARCH (read-only code search)\n"
                "  /lutz                 - Set mode to LUTZ (default; full agent access)\n"
                "  /model <name>         - Change the planner LLM model (Shortcut: Ctrl+U)\n"
                "  /model-code <name>    - Change the code LLM model\n"
                "  /reasoning <level>    - Set reasoning level for planner (Shortcut: Ctrl+E)\n"
                "  /reasoning-code <level> - Set reasoning level for code model\n"
                "  /autocommit [on|off|toggle] - Toggle auto-commit for submitted jobs\n"
                "  /settings             - Open settings\n"
                "  /history              - Show recent prompt history\n"
                "  /history-clear        - Clear prompt history\n"
                "  /task                 - Show selected task info / task command help\n"
                "  /task next|prev       - Navigate selected task\n"
                "  /task toggle          - Toggle selected task done state\n"
                "  /task add <title>     - Add a task\n"
                "  /task edit <title>    - Edit selected task title\n"
                "  /task delete          - Delete selected task\n"
                "  /info                 - Show current configuration and status\n"
                "  /help                 - Show this help message\n"
                "  /quit, /exit          - Exit the application"
            )
            chat.append_message("System", help_text)
        elif base in ("/quit", "/exit"):
            self.action_quit()
        else:
            chat.append_message("System", f"Unknown command: {base}. Type /help for assistance.")

    async def action_select_model(self) -> None:
        chat = self._maybe_chat()
        if not self._executor_ready:
            if chat:
                chat.add_system_message(
                    "Executor is not ready. Cannot select model.", level="ERROR"
                )
            return

        try:
            models_data = await self.executor.get_models()
            raw_models = models_data.get("models", [])
            if not isinstance(raw_models, list):
                raw_models = []
            available_models: List[str] = []
            for model in raw_models:
                if isinstance(model, str):
                    name = model.strip()
                elif isinstance(model, dict):
                    name = str(model.get("name", "")).strip()
                else:
                    name = ""
                if name:
                    available_models.append(name)
            if not available_models:
                if chat:
                    chat.add_system_message("No models available from executor.", level="ERROR")
                return

            def update_model(model_id: str | None) -> None:
                if model_id:
                    self.current_model = model_id
                    # Persist choice so subsequent runs reuse it
                    try:
                        self.settings.last_model = model_id
                        self.settings.save()
                    except Exception:
                        logger.exception("Failed to persist selected model")
                    if chat:
                        chat.add_system_message_markup(f"Model changed to: [bold]{model_id}[/]")
                    # Update statusline (best-effort)
                    try:
                        self._update_statusline()
                    except Exception:
                        pass

            self.push_screen(ModelSelectModal(available_models), update_model)
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to fetch models: {e}", level="ERROR")

    async def action_select_reasoning(self) -> None:
        chat = self._maybe_chat()
        levels = ["disable", "low", "medium", "high"]
        current = str(self.reasoning_level or "low").strip() or "low"
        if current not in levels:
            current = "low"

        def update_level(level: str | None) -> None:
            if level:
                self.reasoning_level = level
                # Persist the user's reasoning preference
                try:
                    self.settings.last_reasoning_level = level
                    self.settings.save()
                except Exception:
                    logger.exception("Failed to persist reasoning level")
                if chat:
                    chat.add_system_message_markup(f"Reasoning level changed to: [bold]{level}[/]")
                # Update statusline (best-effort)
                try:
                    self._update_statusline()
                except Exception:
                    pass

        self.push_screen(ReasoningSelectModal(levels, current), update_level)

    def action_toggle_context(self) -> None:
        if isinstance(self.screen, ContextModalScreen):
            self._show_chat_token_bar()
            self.screen.dismiss(None)
            return

        chat = self._maybe_chat()
        if chat:
            chat.set_token_bar_visible(False)

        self.push_screen(ContextModalScreen(on_close=self._show_chat_token_bar))

        if self._executor_ready:
            self.run_worker(self._refresh_context_panel())

    def _show_chat_token_bar(self) -> None:
        chat = self._maybe_chat()
        if chat:
            chat.set_token_bar_visible(True)

    def action_toggle_tasklist(self) -> None:
        panel = self.query_one("#side-tasklist")
        panel.display = not panel.display
        if panel.display:
            try:
                panel.focus()
            except Exception:
                pass
        else:
            chat = self._maybe_chat()
            if chat:
                try:
                    chat.query_one("#chat-input").focus()
                except Exception:
                    pass

    def action_task_next(self) -> None:
        panel = self.query_one(TaskListPanel)
        panel.move_selection(1)

    def action_task_prev(self) -> None:
        panel = self.query_one(TaskListPanel)
        panel.move_selection(-1)

    def action_task_toggle(self) -> None:
        self.run_worker(self._toggle_selected_task())

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

    def action_toggle_statusline(self) -> None:
        """Toggle visibility of the status line (best-effort)."""
        try:
            # Look in ChatPanel since it's nested there
            panel = self.query_one(ChatPanel).query_one("#status-line")
            panel.toggle_class("hidden")
        except Exception:
            # If not mounted, ignore
            pass

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
        """Handles Ctrl+C: Clear input, cancel job, or double-tap to quit."""
        chat_panel = self._maybe_chat()
        if chat_panel:
            chat_inputs = self.query("#chat-input").results(ChatInput)
            chat_input = next(chat_inputs, None)
            if chat_input and chat_input.text.strip():
                chat_panel.clear_input()
                return

        now = time.time()

        if self.job_in_progress and self.current_job_id:
            self._pending_prompt = None  # Clear any pending prompt on manual cancel
            self._pending_updated_at = 0
            self._pending_generation = 0
            self._pending_min_wait_until = 0.0
            if chat_panel:
                chat_panel.add_system_message("Cancelling job...")
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
        """Best-effort export of the current session zip to workspace cache.

        This method is idempotent and will only attempt export once per app lifetime.
        """
        # Avoid re-exporting multiple times
        if self._session_exported:
            return

        # Only require a session_id here; availability is checked by the caller.
        if not self.executor.session_id:
            return

        from brokk_code.session_persistence import get_session_zip_path

        try:
            session_id = self.executor.session_id
            assert session_id is not None
            zip_bytes = await self.executor.download_session_zip(session_id)
            zip_path = get_session_zip_path(self.executor.workspace_dir, session_id)
            zip_path.write_bytes(zip_bytes)
            logger.info("Session %s exported to %s", session_id, zip_path)
            self._session_exported = True
        except ExecutorError as ee:
            # Treat intentional "Executor not started" after a controlled stop as benign.
            msg = str(ee)
            if "Executor not started" in msg:
                logger.debug(
                    "Session export skipped because executor is not started "
                    "(expected during shutdown)."
                )
            else:
                logger.warning("Failed to export session zip on shutdown: %s", ee)
        except Exception as e:
            logger.warning("Failed to export session zip on shutdown: %s", e)

    async def _shutdown_once(self, *, show_message: bool = True) -> None:
        """Perform shutdown actions once completed. Concurrency-safe with retry on stop failure."""
        # Fast path
        async with self._shutdown_lock:
            if self._shutdown_completed or self._shutting_down:
                return
            self._shutting_down = True

            # Notify user once
            if show_message:
                msg = "Shutting down..."
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message(msg)
                else:
                    logger.info(msg)

            # Attempt to export the session before we stop the executor.
            # Only attempt export if executor reports ready or alive.
            try:
                can_export = False
                if self.executor.session_id:
                    # Prefer _executor_ready, but if it is false we still attempt
                    # export only if the process is alive.
                    if self._executor_ready or self.executor.check_alive():
                        can_export = True

                if can_export:
                    await self._export_session()
                else:
                    logger.debug("Skipping session export: no session or executor not available.")
            except Exception:
                logger.debug("Export step failed during shutdown", exc_info=True)

            # Mark executor not ready immediately so any concurrent refresh/export short-circuits.
            self._executor_ready = False

            # Stop executor (best-effort). Multiple calls are safe because
            # ExecutorManager.stop is idempotent.
            try:
                await self.executor.stop()
            except Exception:
                logger.debug("Executor.stop encountered an error during shutdown", exc_info=True)
                self._shutting_down = False
                return

            self._shutdown_completed = True

    async def action_quit(self) -> None:
        # Centralized shutdown; show_message True to surface to user via chat/logs.
        await self._shutdown_once(show_message=True)
        # After clean shutdown, exit the app
        try:
            self.exit()
        except Exception:
            # exit() may raise in some test harnesses; ignore to avoid double-shutdown.
            pass

    async def on_unmount(self) -> None:
        """Ensure cleanup even if app exits via other means."""
        # on_unmount is a fallback shutdown path; avoid double-running shutdown logic.
        await self._shutdown_once(show_message=False)


if __name__ == "__main__":
    app = BrokkApp()
    app.run()
