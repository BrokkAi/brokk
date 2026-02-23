import asyncio
import logging
import random
import re
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from textual.app import App, ComposeResult, ScreenStackError
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Input, ListItem, ListView, Static

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.prompt_history import append_prompt, clear_history, load_history
from brokk_code.settings import DEFAULT_THEME, Settings, normalize_theme_name
from brokk_code.welcome import build_welcome_message, get_braille_icon
from brokk_code.widgets.chat_panel import ChatInput, ChatPanel
from brokk_code.widgets.context_panel import ContextPanel
from brokk_code.widgets.status_line import StatusLine
from brokk_code.widgets.tasklist_panel import TaskListPanel
from brokk_code.workspace import resolve_workspace_dir

logger = logging.getLogger(__name__)


class ContextModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the context panel."""

    BINDINGS = [
        Binding("escape", "close_context", "Close", show=False),
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


class TaskListModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the task list panel."""

    BINDINGS = [
        Binding("escape", "close_tasklist", "Close", show=False),
    ]

    def __init__(self, on_close: Callable[[], None]) -> None:
        super().__init__()
        self._on_close = on_close

    def compose(self) -> ComposeResult:
        with Vertical(id="tasklist-modal-container"):
            yield TaskListPanel(id="tasklist-panel")

    def on_mount(self) -> None:
        self.query_one(TaskListPanel).focus()

    def action_close_tasklist(self) -> None:
        self._on_close()
        self.dismiss(None)


class TaskTitleModalScreen(ModalScreen[Optional[str]]):
    """Small modal to prompt for a task title (add/edit)."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self, title: str, *, initial: str = "") -> None:
        super().__init__()
        self._title = title
        self._initial = initial

    def compose(self) -> ComposeResult:
        with Vertical(id="task-title-modal-container"):
            yield Static(self._title, id="task-title-modal-title")
            yield Input(value=self._initial, placeholder="Task title", id="task-title-input")

    def on_mount(self) -> None:
        inp = self.query_one("#task-title-input", Input)
        inp.focus()
        inp.cursor_position = len(inp.value)

    def on_input_submitted(self, event: Input.Submitted) -> None:
        value = str(event.value or "").strip()
        self.dismiss(value if value else None)


class BrokkApiKeyModalScreen(ModalScreen[str]):
    """Modal to prompt for the Brokk API key."""

    def __init__(self, message: str = "Enter Brokk API Key") -> None:
        super().__init__()
        self._message = message

    def compose(self) -> ComposeResult:
        with Vertical(id="api-key-modal-container"):
            yield Static(self._message, id="api-key-modal-title")
            yield Input(password=True, placeholder="API Key (sk-...)", id="api-key-input")

    def on_mount(self) -> None:
        self.query_one("#api-key-input", Input).focus()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        value = str(event.value or "").strip()
        if not value:
            self.query_one("#api-key-modal-title", Static).update(
                "[bold red]API Key is required[/]"
            )
            return
        self.dismiss(value)


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
                            Static(
                                f"{'[x]' if level == self.current else '[ ]'} {level}",
                                markup=False,
                            ),
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


class ModeSelectModal(ModalScreen[str]):
    """A modal for selecting the agent mode."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self, modes: List[str], current: str) -> None:
        super().__init__()
        self.modes = modes
        self.current = current
        self._item_id_to_mode: Dict[str, str] = {
            f"mode-{idx}": mode for idx, mode in enumerate(modes)
        }

    def compose(self) -> ComposeResult:
        with Vertical(id="mode-select-container"):
            yield Static("Select Mode", id="mode-select-title")
            with VerticalScroll(id="mode-select-list-wrap"):
                yield ListView(
                    *[
                        ListItem(
                            Static(
                                f"{'[x]' if mode == self.current else '[ ]'} {mode}",
                                markup=False,
                            ),
                            id=item_id,
                        )
                        for item_id, mode in self._item_id_to_mode.items()
                    ],
                    id="mode-select-list",
                )

    def on_mount(self) -> None:
        self.query_one("#mode-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return
        mode = self._item_id_to_mode.get(message.item.id)
        if mode:
            self.dismiss(mode)


class ModelReasoningSelectModal(ModalScreen[tuple[str, str]]):
    """A combined modal for selecting both model and reasoning level side-by-side."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self, models: List[str], current_model: str, current_reasoning: str) -> None:
        super().__init__()
        self.models = models
        self.selected_model = current_model
        self.selected_reasoning = current_reasoning
        self.reasoning_levels = ["disable", "low", "medium", "high"]

    def compose(self) -> ComposeResult:
        with Horizontal(id="model-reasoning-combined-container"):
            with Vertical(classes="selection-pane"):
                yield Static("Model", id="model-select-title")
                with VerticalScroll(id="model-select-list-wrap"):
                    items = []
                    for idx, m in enumerate(self.models):
                        label = f"{'[x]' if m == self.selected_model else '[ ]'} {m}"
                        items.append(ListItem(Static(label, markup=False), id=f"m-{idx}"))
                    yield ListView(*items, id="model-select-list")

            with Vertical(classes="selection-pane"):
                yield Static("Reasoning", id="reasoning-select-title")
                with VerticalScroll(id="reasoning-select-list-wrap"):
                    items = []
                    for idx, r in enumerate(self.reasoning_levels):
                        label = f"{'[x]' if r == self.selected_reasoning else '[ ]'} {r}"
                        items.append(ListItem(Static(label, markup=False), id=f"r-{idx}"))
                    yield ListView(*items, id="reasoning-select-list")

    def on_mount(self) -> None:
        # Sync model list highlight
        try:
            m_list = self.query_one("#model-select-list", ListView)
            m_idx = self.models.index(self.selected_model)
            m_list.index = m_idx
        except (ValueError, Exception):
            pass

        # Sync reasoning list highlight
        try:
            r_list = self.query_one("#reasoning-select-list", ListView)
            r_idx = self.reasoning_levels.index(self.selected_reasoning)
            r_list.index = r_idx
        except (ValueError, Exception):
            pass

        # Focus the model list by default
        self.query_one("#model-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return

        try:
            if message.list_view.id == "model-select-list":
                # IDs are 'm-0', 'm-1', etc.
                idx_str = message.item.id.split("-")[-1]
                idx = int(idx_str)
                self.selected_model = self.models[idx]
                # Update markers in model list
                for i, item in enumerate(message.list_view.query(ListItem)):
                    marker = "[x]" if i == idx else "[ ]"
                    # The Static widget was created with markup=False in compose()
                    item.query_one(Static).update(f"{marker} {self.models[i]}")

                # Sync and focus reasoning list
                r_list = self.query_one("#reasoning-select-list", ListView)
                try:
                    r_idx = self.reasoning_levels.index(self.selected_reasoning)
                    r_list.index = r_idx
                except (ValueError, Exception):
                    pass
                r_list.focus()

            elif message.list_view.id == "reasoning-select-list":
                # IDs are 'r-0', 'r-1', etc.
                idx_str = message.item.id.split("-")[-1]
                idx = int(idx_str)
                self.selected_reasoning = self.reasoning_levels[idx]
                # Dismiss immediately upon reasoning selection
                self.dismiss((self.selected_model, self.selected_reasoning))
        except (ValueError, IndexError):
            logger.error("Failed to parse index from ListItem id: %s", message.item.id)


class BrokkApp(App):
    """The main Brokk TUI application.

    Task list UI policy:
    - The task list is accessed via a full-screen modal (TaskListModalScreen).
    - The side task list panel remains mounted for layout stability and potential future use,
      but it is not toggled by /task.
    """

    CSS_PATH = "styles/app.tcss"
    COMMAND_PALETTE_DISPLAY = "Settings"
    BINDINGS = [
        # Footer/help-bar ordering: Context, Tasks, Settings
        Binding("ctrl+c", "handle_ctrl_c", "Quit", show=True),
        Binding("ctrl+p", "command_palette", "Settings", show=True),
        Binding("shift+tab", "toggle_mode", "Toggle mode", show=False, priority=True),
    ]

    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = True,
        executor: Optional[ExecutorManager] = None,
        session_id: Optional[str] = None,
        resume_session: bool = False,
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
                brokk_api_key=self.settings.get_brokk_api_key(),
            )
        self.requested_session_id = session_id
        self.resume_session = resume_session
        self.settings = Settings.load()
        self._set_theme(self.settings.theme)
        self.agent_mode = "LUTZ"

        # Initialize model and reasoning settings from persisted Settings if present,
        # otherwise fall back to safe defaults.
        # We accept persisted values as-is at startup; validation against the
        # executor model catalog can occur later if needed.
        self.current_model = (
            str(self.settings.last_model).strip()
            if self.settings.last_model and str(self.settings.last_model).strip()
            else "gpt-5.2"
        )
        self.code_model = (
            str(self.settings.last_code_model).strip()
            if self.settings.last_code_model and str(self.settings.last_code_model).strip()
            else "gemini-3-flash-preview"
        )
        self.reasoning_level = (
            str(self.settings.last_reasoning_level).strip()
            if self.settings.last_reasoning_level
            and str(self.settings.last_reasoning_level).strip()
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
        self._reasoning_target: str = "planner"

        self._tasklist_restore_focus_widget: Any | None = None

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

    def _show_welcome_message(self) -> None:
        """Constructs and displays the branded welcome message in the ChatPanel."""
        chat = self._maybe_chat()
        if not chat:
            return

        chat.add_welcome(get_braille_icon(), build_welcome_message(self.get_slash_commands()))

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
        with Horizontal():
            yield ChatPanel(id="chat-main")
            yield TaskListPanel(id="side-tasklist")

    async def on_mount(self) -> None:
        chat = self._maybe_chat()
        logger.info("Using workspace directory: %s", self.executor.workspace_dir)
        if chat:
            chat.set_token_bar_visible(True)

            # Load initial prompt history for arrow-key navigation
            history = load_history(self.executor.workspace_dir)
            chat.set_history(history)

            self._show_welcome_message()

        # Check for API key before starting executor
        if not self.settings.get_brokk_api_key():
            if chat:
                chat.add_system_message("Brokk API key missing. Please enter it to continue.")

            def on_key_entered(key: str) -> None:
                self.settings.brokk_api_key = key
                self.settings.save()
                self.executor.brokk_api_key = key
                if chat:
                    chat.add_system_message("API key saved. Starting Brokk executor...")
                self.run_worker(self._start_executor())

            self.push_screen(BrokkApiKeyModalScreen(), on_key_entered)
        else:
            if chat:
                chat.add_system_message("Starting Brokk executor...")
            self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())
        self.run_worker(self._poll_tasklist())
        self.run_worker(self._poll_context())
        self._update_statusline()

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
                # Initial context load
                self.run_worker(self._refresh_context_panel())
            else:
                msg = "Executor failed to become ready (timeout)."
                if chat:
                    chat.add_system_message(msg, level="ERROR")
                else:
                    logger.error(msg)
        except ExecutorError as e:
            msg = str(e)
            if "jbang" in msg.lower():
                msg += (
                    "\n\nHint: Install jbang from https://jbang.dev "
                    "or provide a local JAR with --jar."
                )
            if chat:
                chat.add_system_message(msg, level="ERROR")
            else:
                logger.error(msg)
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
                    self._update_tasklist_details_all(tasklist_data)
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
                    for task_list in self._tasklist_panels():
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
                case "drop_others":
                    to_drop = self._compute_drop_others(
                        panel._fragments, selected_fragments, panel._active_id
                    )
                    if to_drop:
                        await self.executor.drop_context_fragments(to_drop)
                        if chat:
                            chat.add_system_message(f"Dropped {len(to_drop)} other fragment(s).")
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
    def _compute_drop_others(
        all_fragments: List[Dict[str, Any]],
        selected_fragments: List[Dict[str, Any]],
        active_id: Optional[str],
    ) -> List[str]:
        protected_ids = {str(f.get("id", "")) for f in selected_fragments}
        if active_id:
            protected_ids.add(active_id)

        to_drop = []
        for f in all_fragments:
            f_id = str(f.get("id", ""))
            if not f_id or f_id in protected_ids:
                continue
            if bool(f.get("pinned", False)):
                continue
            chip_kind = str(f.get("chip_kind", f.get("chipKind", ""))).upper()
            if chip_kind == "HISTORY":
                continue
            to_drop.append(f_id)
        return to_drop

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

    def _tasklist_panels(self) -> List[TaskListPanel]:
        """Return all mounted task list panels (side + modal if present)."""
        panels: List[TaskListPanel] = []
        try:
            panels.append(self.query_one("#side-tasklist", TaskListPanel))
        except Exception:
            pass

        try:
            if isinstance(self.screen, TaskListModalScreen):
                panels.append(self.screen.query_one(TaskListPanel))
        except Exception:
            pass

        return panels

    def _active_tasklist_panel(self) -> TaskListPanel:
        """Return the panel that should receive task actions."""
        try:
            current_screen = self.screen
        except ScreenStackError:
            current_screen = None

        if isinstance(current_screen, TaskListModalScreen):
            try:
                return current_screen.query_one(TaskListPanel)
            except Exception:
                # Modal may be current but its contents not mounted yet; fall back to side panel.
                return self.query_one("#side-tasklist", TaskListPanel)

        return self.query_one("#side-tasklist", TaskListPanel)

    def _update_tasklist_details_all(self, tasklist_data: Dict[str, Any]) -> None:
        for panel in self._tasklist_panels():
            panel.update_tasklist_details(tasklist_data)

    async def _ensure_tasklist_data(self) -> Optional[Dict[str, Any]]:
        panel = self._active_tasklist_panel()
        data = panel.tasklist_data_for_update()
        if data is not None:
            return data
        data = await self.executor.get_tasklist()
        self._update_tasklist_details_all(data)
        return panel.tasklist_data_for_update()

    async def _persist_tasklist(self, data: Dict[str, Any]) -> Dict[str, Any]:
        saved = await self.executor.set_tasklist(data)
        self._update_tasklist_details_all(saved)
        return saved

    async def _toggle_selected_task(self) -> None:
        chat = self.query_one(ChatPanel)
        panel = self._active_tasklist_panel()
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
        panel = self._active_tasklist_panel()
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
        panel = self._active_tasklist_panel()
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

    def on_chat_panel_mode_selected(self, message: ChatPanel.ModeSelected) -> None:
        self._set_mode(message.mode.upper())

    def on_chat_panel_reasoning_level_selected(
        self, message: ChatPanel.ReasoningLevelSelected
    ) -> None:
        chat = self._maybe_chat()
        if self._reasoning_target == "code":
            self.reasoning_level_code = message.level
            try:
                self.settings.last_code_reasoning_level = message.level
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist code reasoning level")
            if chat:
                chat.add_system_message_markup(
                    f"Code reasoning level changed to: [bold]{message.level}[/]"
                )
        else:
            self.reasoning_level = message.level
            try:
                self.settings.last_reasoning_level = message.level
                self.settings.save()
            except Exception:
                logger.exception("Failed to persist reasoning level")
            if chat:
                chat.add_system_message_markup(
                    f"Reasoning level changed to: [bold]{message.level}[/]"
                )

        self._update_statusline()

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

    @staticmethod
    def _extract_at_mentions(task_input: str) -> List[str]:
        """Extracts whitespace-delimited @mention tokens from prompt text."""
        tokens = re.findall(r"(?<!\S)@([^\s@]+)", task_input)
        unique_tokens: List[str] = []
        seen = set()
        for token in tokens:
            norm = token.strip()
            if not norm or norm in seen:
                continue
            seen.add(norm)
            unique_tokens.append(norm)
        return unique_tokens

    @staticmethod
    def _extract_fragment_ids_from_add_context_response(resp: Any) -> List[str]:
        if not isinstance(resp, dict):
            return []

        added = resp.get("added")
        if not isinstance(added, list):
            return []

        ids: List[str] = []
        for item in added:
            if not isinstance(item, dict):
                continue
            raw_id = item.get("id", item.get("fragmentId"))
            if raw_id is None:
                continue
            frag_id = str(raw_id).strip()
            if frag_id:
                ids.append(frag_id)

        return list(dict.fromkeys(ids))

    async def _attach_mentions_to_context(self, task_input: str) -> List[str]:
        """Resolves @mentions and attaches matching entities to context before job submission.

        Returns fragment IDs for any newly-attached context fragments when the executor's
        add_context_* endpoints include them in their payload. If the executor does not
        return fragment IDs, rollback-on-submit-failure is not possible.
        """
        mentions = self._extract_at_mentions(task_input)
        if not mentions:
            return []
        if not hasattr(self.executor, "get_completions"):
            return []

        file_paths: List[str] = []
        class_names: List[str] = []
        method_names: List[str] = []

        for mention in mentions:
            try:
                completion_data = await self.executor.get_completions(mention, limit=20)
            except Exception:
                logger.exception("Failed resolving @mention '%s' via completions", mention)
                continue

            raw_items = completion_data.get("completions", [])
            if not isinstance(raw_items, list):
                continue

            selected: Optional[Dict[str, str]] = None
            mention_lower = mention.lower()
            for raw in raw_items:
                if not isinstance(raw, dict):
                    continue
                detail = str(raw.get("detail", "")).strip()
                name = str(raw.get("name", "")).strip()

                if detail.lower() == mention_lower or name.lower() == mention_lower:
                    selected = {
                        "type": str(raw.get("type", "")).strip().lower(),
                        "detail": detail,
                        "name": name,
                    }
                    break

            if selected is None:
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message(f"No exact match for @{mention}")
                continue

            completion_type = selected["type"]
            detail = selected["detail"] or selected["name"]
            if not detail:
                continue

            if completion_type == "file":
                file_paths.append(detail)
            elif completion_type in {"class", "module"}:
                class_names.append(detail)
            elif completion_type == "function":
                method_names.append(detail)
            elif completion_type == "field":
                if "." in detail:
                    class_names.append(detail.rsplit(".", 1)[0])

        # De-duplicate while preserving order
        file_paths = list(dict.fromkeys(file_paths))
        class_names = list(dict.fromkeys(class_names))
        method_names = list(dict.fromkeys(method_names))

        attached_parts: List[str] = []
        attached_fragment_ids: List[str] = []

        if file_paths and hasattr(self.executor, "add_context_files"):
            try:
                resp = await self.executor.add_context_files(file_paths)
                attached_fragment_ids.extend(
                    self._extract_fragment_ids_from_add_context_response(resp)
                )
                attached_parts.append(f"files={len(file_paths)}")
            except Exception:
                logger.exception("Failed attaching @mentions as context files")
        if class_names and hasattr(self.executor, "add_context_classes"):
            try:
                resp = await self.executor.add_context_classes(class_names)
                attached_fragment_ids.extend(
                    self._extract_fragment_ids_from_add_context_response(resp)
                )
                attached_parts.append(f"classes={len(class_names)}")
            except Exception:
                logger.exception("Failed attaching @mentions as context classes")
        if method_names and hasattr(self.executor, "add_context_methods"):
            try:
                resp = await self.executor.add_context_methods(method_names)
                attached_fragment_ids.extend(
                    self._extract_fragment_ids_from_add_context_response(resp)
                )
                attached_parts.append(f"methods={len(method_names)}")
            except Exception:
                logger.exception("Failed attaching @mentions as context methods")

        if attached_parts:
            chat = self._maybe_chat()
            if chat:
                details = ", ".join(attached_parts)
                chat.add_system_message(f"Attached @mentions to context: {details}")

        return list(dict.fromkeys(attached_fragment_ids))

    async def _run_job(self, task_input: str) -> None:
        self.job_in_progress = True
        chat = self.query_one(ChatPanel)
        chat.set_job_running(True)
        chat.set_response_pending()
        attached_fragment_ids: List[str] = []
        try:
            attached_fragment_ids = await self._attach_mentions_to_context(task_input)
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
            if (
                self.current_job_id is None
                and attached_fragment_ids
                and hasattr(self.executor, "drop_context_fragments")
            ):
                try:
                    await self.executor.drop_context_fragments(attached_fragment_ids)
                except Exception:
                    logger.exception(
                        "Failed to rollback context fragments after submit_job failure: %s",
                        attached_fragment_ids,
                    )

            chat.add_system_message(f"Job failed or network error: {e}", level="ERROR")
        finally:
            chat.set_response_finished()
            chat.set_job_running(False)

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
            else:
                # Only mark idle once we are sure no more prompts are queued
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
                is_new_message=bool(data.get("isNewMessage", False)),
                is_reasoning=bool(data.get("isReasoning", False)),
                is_terminal=bool(data.get("isTerminal", False)),
            )
        elif event_type == "NOTIFICATION":
            level = data.get("level", "INFO")
            msg = data.get("message", "")
            chat.add_system_message(msg, level=level)
        elif event_type == "ERROR":
            msg = data.get("message", "Unknown error")
            chat.add_system_message(msg, level="ERROR")
            # Note: set_job_running(False) happens in _run_job finally block
        elif event_type == "STATE_HINT":
            hint_name = data.get("name")
            if hint_name in ("contextHistoryUpdated", "workspaceUpdated"):
                self.run_worker(self._refresh_context_panel())

    def _set_mode(self, new_mode: str, *, announce: bool = True) -> None:
        """Sets the agent mode, updates the status line, and optionally announces to chat."""
        self.agent_mode = new_mode
        # Update statusline if present
        self._update_statusline()
        if announce:
            msg_markup = f"Mode changed to: [bold]{self.agent_mode}[/]"
            chat = self._maybe_chat()
            if chat:
                chat.add_system_message_markup(msg_markup)
            else:
                logger.info("Mode changed to %s", self.agent_mode)

    def _render_info(self) -> None:
        """Renders current status and configuration info to the chat."""
        chat = self.query_one(ChatPanel)
        status = (
            "[bold green]Ready[/]" if self._executor_ready else "[bold yellow]Initializing...[/]"
        )
        jar_path = self.executor.resolved_jar_path or "via jbang"
        launch_mode = "Direct JAR" if self.executor.resolved_jar_path else "jbang"

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
            f"Launch Mode: [bold]{launch_mode}[/]\n"
            f"Executor JAR: [bold]{jar_path}[/]\n"
            f"Mode: [bold]{self.agent_mode}[/]\n"
            f"Auto-commit: [bold]{'ON' if self.auto_commit else 'OFF'}[/]\n"
            f"{planner_info}\n"
            f"{code_info}"
        )
        chat.add_system_message_markup(info_markup)

    @staticmethod
    def get_slash_commands() -> List[Dict[str, str]]:
        """Returns the structured catalog of supported slash commands."""
        return [
            {"command": "/api-key", "description": "Update your Brokk API key"},
            {"command": "/context", "description": "Toggle and focus context panel"},
            {"command": "/code", "description": "Set mode to CODE (direct implementation)"},
            {"command": "/ask", "description": "Set mode to ASK (questions only)"},
            {"command": "/lutz", "description": "Set mode to LUTZ (default; full agent access)"},
            {"command": "/mode", "description": "Open mode selection menu"},
            {"command": "/model", "description": "Change the planner LLM model"},
            {"command": "/model-code", "description": "Change the code LLM model"},
            {"command": "/reasoning", "description": "Set reasoning level for planner"},
            {"command": "/reasoning-code", "description": "Set reasoning level for code model"},
            {"command": "/autocommit", "description": "Toggle auto-commit for submitted jobs"},
            {"command": "/settings", "description": "Open settings"},
            {"command": "/history", "description": "Show recent prompt history"},
            {"command": "/history-clear", "description": "Clear prompt history"},
            {"command": "/task", "description": "Open/close the task list"},
            {"command": "/info", "description": "Show current configuration and status"},
            {"command": "/help", "description": "Show help message"},
            {"command": "/quit", "description": "Exit the application"},
            {"command": "/exit", "description": "Exit the application"},
        ]

    def _handle_command(self, cmd: str) -> None:
        chat = self.query_one(ChatPanel)
        parts = cmd.split()
        base = parts[0].lower()

        if base == "/model":
            if len(parts) > 1:
                self.current_model = parts[1]
                # Persist the last-used planner model for subsequent runs
                try:
                    self.settings.last_model = self.current_model
                    self.settings.save()
                except Exception:
                    logger.exception("Failed to persist last_model setting")
                chat.add_system_message_markup(f"Model changed to: [bold]{self.current_model}[/]")
                self._update_statusline()
            else:
                self.run_worker(self.action_select_model_and_reasoning())
        elif base == "/model-code":
            if len(parts) > 1:
                self.code_model = parts[1]
                # Persist the last-used code model
                try:
                    self.settings.last_code_model = self.code_model
                    self.settings.save()
                except Exception:
                    logger.exception("Failed to persist last_code_model setting")
                chat.add_system_message_markup(f"Code model changed to: [bold]{self.code_model}[/]")
                self._update_statusline()
            else:
                self.run_worker(self.action_select_code_model_and_reasoning())
        elif base == "/reasoning":
            if len(parts) > 1:
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
            else:
                self.action_select_reasoning(target="planner")
        elif base == "/reasoning-code":
            if len(parts) > 1:
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
                self._update_statusline()
            else:
                self.action_select_reasoning(target="code")
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
        elif base in ("/code", "/ask", "/lutz"):
            self._set_mode(base[1:].upper())
        elif base == "/mode":
            if len(parts) > 1:
                self._set_mode(parts[1].upper())
            else:
                self.action_select_mode()
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
        elif base == "/api-key":

            def on_key_entered(key: str) -> None:
                self.settings.brokk_api_key = key
                self.settings.save()
                self.executor.brokk_api_key = key
                chat.add_system_message("API key updated.")

            self.push_screen(BrokkApiKeyModalScreen("Update Brokk API Key"), on_key_entered)
        elif base == "/context":
            self.action_toggle_context()
        elif base == "/task":
            if len(parts) != 1:
                chat.add_system_message(
                    "Usage: /task (task actions are available via task list keybindings).",
                    level="WARNING",
                )
                return
            self.action_toggle_tasklist()
        elif base == "/help":
            commands = self.get_slash_commands()
            # Calculate padding based on longest command
            max_cmd_len = max(len(c["command"]) for c in commands)
            lines = ["Available commands:"]
            for c in commands:
                lines.append(f"  {c['command']: <{max_cmd_len}} - {c['description']}")
            chat.append_message("System", "\n".join(lines))
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

            def update_selection(model_id: str | None) -> None:
                if model_id:
                    self.current_model = model_id
                    # Persist choice
                    try:
                        self.settings.last_model = model_id
                        self.settings.save()
                    except Exception:
                        logger.exception("Failed to persist model setting")

                    if chat:
                        chat.add_system_message_markup(f"Model changed to: [bold]{model_id}[/]")

                    # Update statusline (best-effort)
                    try:
                        self._update_statusline()
                    except Exception:
                        pass

            self.push_screen(ModelSelectModal(available_models), update_selection)
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to fetch models: {e}", level="ERROR")

    async def action_select_model_and_reasoning(self) -> None:
        await self._select_model_and_reasoning_flow(target="planner")

    async def action_select_code_model_and_reasoning(self) -> None:
        await self._select_model_and_reasoning_flow(target="code")

    async def _select_model_and_reasoning_flow(self, target: str = "planner") -> None:
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

            def update_selection(result: tuple[str, str] | None) -> None:
                if result:
                    model_id, reasoning = result
                    if target == "code":
                        self.code_model = model_id
                        self.reasoning_level_code = reasoning
                        try:
                            self.settings.last_code_model = model_id
                            self.settings.last_code_reasoning_level = reasoning
                            self.settings.save()
                        except Exception:
                            logger.exception("Failed to persist code model/reasoning settings")
                        label = "Code Model"
                    else:
                        self.current_model = model_id
                        self.reasoning_level = reasoning
                        try:
                            self.settings.last_model = model_id
                            self.settings.last_reasoning_level = reasoning
                            self.settings.save()
                        except Exception:
                            logger.exception("Failed to persist model/reasoning settings")
                        label = "Model"

                    if chat:
                        msg = f"{label}: [bold]{model_id}[/] (Reasoning: [bold]{reasoning}[/])"
                        chat.add_system_message_markup(f"Settings updated: {msg}")

                    # Update statusline (best-effort)
                    try:
                        self._update_statusline()
                    except Exception:
                        pass

            current_m = self.code_model if target == "code" else self.current_model
            current_r = self.reasoning_level_code if target == "code" else self.reasoning_level

            self.push_screen(
                ModelReasoningSelectModal(available_models, current_m, current_r),
                update_selection,
            )
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to fetch models: {e}", level="ERROR")

    def action_select_reasoning(self, target: str = "planner") -> None:
        chat = self._maybe_chat()
        if chat:
            self._reasoning_target = target
            levels = ["disable", "low", "medium", "high"]
            current_val = self.reasoning_level_code if target == "code" else self.reasoning_level
            current = str(current_val or "low").strip() or "low"
            if current not in levels:
                current = "low"
            chat.open_reasoning_menu(levels, current)

    def action_select_mode(self) -> None:
        chat = self._maybe_chat()
        if chat:
            chat.open_mode_menu(["CODE", "ASK", "LUTZ"], self.agent_mode)

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

    def _maybe_focused_widget(self) -> Any | None:
        """Best-effort focused widget lookup; returns None if there is no active screen."""
        try:
            return self.focused
        except ScreenStackError:
            return None

    def action_toggle_tasklist(self) -> None:
        """Toggle the task list modal.

        When opened, the modal's TaskListPanel receives focus.
        When closed, focus is restored to whatever had it previously (best-effort).
        """
        try:
            current_screen = self.screen
        except ScreenStackError:
            current_screen = None

        if isinstance(current_screen, TaskListModalScreen):
            self._restore_tasklist_focus()
            current_screen.dismiss(None)
            return

        self._tasklist_restore_focus_widget = self._maybe_focused_widget()

        def on_close() -> None:
            self._restore_tasklist_focus()

        self.push_screen(TaskListModalScreen(on_close=on_close))

        if self._executor_ready:
            self.run_worker(self._ensure_tasklist_data())
            self.run_worker(self._refresh_context_panel())

    def _restore_tasklist_focus(self) -> None:
        widget = self._tasklist_restore_focus_widget
        self._tasklist_restore_focus_widget = None
        if widget is None:
            return
        try:
            widget.focus()
        except Exception:
            pass

    def action_task_next(self) -> None:
        panel = self._active_tasklist_panel()
        panel.move_selection(1)

    def action_task_prev(self) -> None:
        panel = self._active_tasklist_panel()
        panel.move_selection(-1)

    def action_task_toggle(self) -> None:
        self.run_worker(self._toggle_selected_task())

    def action_task_delete(self) -> None:
        self.run_worker(self._delete_selected_task())

    def action_task_add(self) -> None:
        def on_done(result: Optional[str]) -> None:
            if result is None:
                return
            self.run_worker(self._add_task(result))

        self.push_screen(TaskTitleModalScreen("Add Task"), on_done)

    def action_task_edit(self) -> None:
        chat = self._maybe_chat()
        panel = self._active_tasklist_panel()
        selected = panel.selected_task()
        if not selected:
            if chat:
                chat.add_system_message("No task selected.")
            return

        initial = str(selected.get("title", "") or "").strip()

        def on_done(result: Optional[str]) -> None:
            if result is None:
                return
            self.run_worker(self._edit_selected_task(result))

        self.push_screen(TaskTitleModalScreen("Edit Task", initial=initial), on_done)

    def action_toggle_mode(self) -> None:
        """Cycles through agent modes: CODE -> ASK -> LUTZ -> CODE."""
        modes = ["CODE", "ASK", "LUTZ"]
        try:
            current_index = modes.index(self.agent_mode)
        except ValueError:
            current_index = 0

        next_index = (current_index + 1) % len(modes)
        self._set_mode(modes[next_index])

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
