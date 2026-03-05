import asyncio
import logging
import random
import re
import time
import webbrowser
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional

from textual.app import App, ComposeResult, ScreenStackError
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, ListItem, ListView, Static

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.prompt_history import append_prompt, clear_history, load_history
from brokk_code.settings import (
    DEFAULT_THEME,
    Settings,
    normalize_theme_name,
    write_brokk_api_key,
)
from brokk_code.welcome import build_welcome_message, get_braille_icon
from brokk_code.widgets.chat_panel import ChatContainer, ChatInput, ChatPanel
from brokk_code.widgets.context_panel import ContextPanel
from brokk_code.widgets.status_line import StatusLine
from brokk_code.widgets.tasklist_panel import TaskListPanel
from brokk_code.workspace import resolve_workspace_dir
from brokk_code.worktrees import WorktreeInfo, WorktreeService

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


class BrokkApiKeyModalScreen(ModalScreen[None]):
    """Modal to prompt for the Brokk API key."""

    BINDINGS = [
        Binding("ctrl+c", "quit_prompt", "Quit", show=False),
        Binding("ctrl+d", "quit_prompt", "Quit", show=False),
    ]

    def __init__(
        self,
        on_submit: Callable[[str], asyncio.Future[bool] | Any],
        message: str = "Enter Brokk API Key",
        is_update: bool = False,
    ) -> None:
        super().__init__()
        self._on_submit = on_submit
        self._message = message
        self._is_update = is_update

    def compose(self) -> ComposeResult:
        from textual.widgets import LoadingIndicator, Markdown

        with Vertical(id="api-key-modal-container"):
            with VerticalScroll(id="api-key-modal-scroll"):
                yield Static(get_braille_icon(), id="api-key-modal-icon")
                yield Markdown(
                    build_welcome_message(BrokkApp.get_slash_commands()),
                    id="api-key-modal-welcome",
                )
            yield Static(self._message, id="api-key-modal-title")
            yield LoadingIndicator(id="api-key-modal-spinner", classes="hidden")
            yield Input(password=True, placeholder="API Key (sk-...)", id="api-key-input")
            footer_text = "Press Ctrl+C or Ctrl+D to exit."
            if self._is_update:
                footer_text += (
                    "\n[dim]Note: API key updates will apply to " + "the next executor restart.[/]"
                )
            yield Static(footer_text, id="api-key-modal-footer")

    def on_mount(self) -> None:
        self.query_one("#api-key-input", Input).focus()

    async def action_quit_prompt(self) -> None:
        await self.app.action_quit()

    async def on_input_submitted(self, event: Input.Submitted) -> None:
        value = str(event.value or "").strip()
        if not value:
            self.query_one("#api-key-modal-title", Static).update(
                "[bold red]API Key is required[/]"
            )
            return

        # Show spinner and disable input while processing
        spinner = self.query_one("#api-key-modal-spinner")
        title = self.query_one("#api-key-modal-title", Static)
        spinner.remove_class("hidden")
        event.input.disabled = True
        if self._is_update:
            title.update("Saving key...")
        else:
            title.update("Starting Brokk... (first run may take a moment)")

        try:
            res = self._on_submit(value)
            if asyncio.iscoroutine(res):
                success = await res
            else:
                success = bool(res)

            if success:
                self.dismiss(None)
            else:
                spinner.add_class("hidden")
                title.update("[bold red]Failed to save API key[/]")
                event.input.disabled = False
                event.input.focus()
        except Exception as e:
            logger.exception("API key submission failed")
            spinner.add_class("hidden")
            title.update(f"[bold red]{str(e)}[/]")
            event.input.disabled = False
            event.input.focus()


class OpenAiAuthUrlModalScreen(ModalScreen[None]):
    """Modal for copying/opening the OpenAI OAuth URL safely."""

    BINDINGS = [
        Binding("escape", "dismiss", "Close", show=False),
    ]

    def __init__(self, auth_url: str) -> None:
        super().__init__()
        self._auth_url = auth_url

    def compose(self) -> ComposeResult:
        with Vertical(id="openai-auth-modal-container"):
            yield Static("OpenAI Authorization", id="openai-auth-modal-title")
            yield Static(
                "Use Copy URL to avoid terminal wrapping/copy issues.",
                id="openai-auth-modal-help",
            )
            yield Input(value=self._auth_url, id="openai-auth-url-input")
            with Horizontal(id="openai-auth-modal-actions"):
                yield Button("Copy URL", id="openai-auth-copy", variant="primary")
                yield Button("Open Browser", id="openai-auth-open")
                yield Button("Close", id="openai-auth-close")
            yield Static("", id="openai-auth-modal-status")

    def on_mount(self) -> None:
        inp = self.query_one("#openai-auth-url-input", Input)
        inp.focus()
        inp.cursor_position = 0
        try:
            inp.select_all()
        except Exception:
            pass

    def on_button_pressed(self, event: Button.Pressed) -> None:
        status = self.query_one("#openai-auth-modal-status", Static)
        button_id = event.button.id or ""
        if button_id == "openai-auth-copy":
            try:
                self.app.copy_to_clipboard(self._auth_url)
                status.update("Copied URL to clipboard.")
            except Exception as e:
                logger.exception("Failed to copy OpenAI OAuth URL to clipboard")
                status.update(f"Copy failed: {e}")
            return
        if button_id == "openai-auth-open":
            try:
                opened = bool(webbrowser.open(self._auth_url))
                status.update(
                    "Opened default browser." if opened else "Could not open browser automatically."
                )
            except Exception as e:
                logger.exception("Failed to open OpenAI OAuth URL from modal")
                status.update(f"Open failed: {e}")
            return
        if button_id == "openai-auth-close":
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


class WorktreeSelectModal(ModalScreen[tuple[str, Optional[Path]]]):
    """A modal for selecting, creating, and removing Git worktrees."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
        Binding("n", "new_worktree", "New", show=False),
        Binding("d", "remove_worktree", "Remove", show=False),
        Binding("x", "remove_worktree", "Remove", show=False),
    ]

    def __init__(self, worktrees: List[WorktreeInfo], current_path: Path) -> None:
        super().__init__()
        self.worktrees = worktrees
        self.current_path = current_path
        self._item_id_to_worktree: Dict[str, WorktreeInfo] = {
            f"wt-{idx}": wt for idx, wt in enumerate(worktrees)
        }

    def compose(self) -> ComposeResult:
        with Vertical(id="worktree-select-container"):
            yield Static("Manage Worktrees", id="worktree-select-title")
            with VerticalScroll(id="worktree-select-list-wrap"):
                items = []
                for item_id, wt in self._item_id_to_worktree.items():
                    marker = "[x]" if wt.path == self.current_path else "[ ]"
                    label = f"{marker} {wt.path.name} ({wt.branch or 'no branch'})"
                    items.append(ListItem(Static(label, markup=False), id=item_id))
                yield ListView(*items, id="worktree-select-list")
            yield Static(
                "Enter: Switch  [bold]N[/]: New  [bold]D[/]: Remove  Esc: Cancel",
                id="worktree-select-footer",
            )

    def on_mount(self) -> None:
        self.query_one("#worktree-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return
        worktree = self._item_id_to_worktree.get(message.item.id)
        if worktree:
            self.dismiss(("switch", worktree.path))

    def action_new_worktree(self) -> None:
        self.dismiss(("add", None))

    def action_remove_worktree(self) -> None:
        list_view = self.query_one("#worktree-select-list", ListView)
        if not list_view.highlighted_child or not list_view.highlighted_child.id:
            return
        worktree = self._item_id_to_worktree.get(list_view.highlighted_child.id)
        if worktree:
            self.dismiss(("remove", worktree.path))


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


class SessionSelectModal(ModalScreen[str]):
    """A modal for selecting and switching between sessions."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
        Binding("n", "new_session", "New", show=False),
        Binding("r", "rename_session", "Rename", show=False),
        Binding("d", "delete_session", "Delete", show=False),
        Binding("x", "delete_session", "Delete", show=False),
    ]

    def __init__(
        self,
        sessions: List[Dict[str, Any]],
        current_id: str,
        on_rename: Optional[Callable[[str, str], Awaitable[bool]]] = None,
    ) -> None:
        super().__init__()
        self.sessions = sessions
        self.current_id = current_id
        self._on_rename = on_rename
        self._item_id_to_session_id: Dict[str, str] = {
            f"session-{idx}": str(s.get("id", "")) for idx, s in enumerate(sessions)
        }

    @staticmethod
    def _format_session_row(s: Dict[str, Any]) -> str:
        title_width = 60
        date_width = 16  # matches "YYYY-MM-DD HH:MM"

        name = s.get("name") or s.get("id")
        title = (str(name or "")).strip()
        title = title[:title_width]

        # Modified time
        modified = s.get("modified", 0)
        date_text = ""
        if modified > 0:
            dt = datetime.fromtimestamp(modified / 1000)
            date_text = dt.strftime("%Y-%m-%d %H:%M")

        # AI responses (entries)
        ai_responses = s.get("aiResponses", 0)
        entry_text = ""
        if ai_responses > 0:
            suffix = "entry" if ai_responses == 1 else "entries"
            entry_text = f"{ai_responses} {suffix}"

        return f"{title:<{title_width}}  {date_text:<{date_width}}  {entry_text}".rstrip()

    def compose(self) -> ComposeResult:
        with Vertical(id="session-select-container"):
            yield Static("Select Session", id="session-select-title")
            with VerticalScroll(id="session-select-list-wrap"):
                items = []
                for item_id, s in zip(self._item_id_to_session_id.keys(), self.sessions):
                    label = self._format_session_row(s)
                    items.append(ListItem(Static(label, markup=False), id=item_id))

                yield ListView(*items, id="session-select-list")
            yield Static(
                "Enter: Switch  [bold]N[/]: New  [bold]R[/]: Rename "
                + " [bold]D[/]: Delete  Esc: Cancel",
                id="session-select-footer",
            )

    def on_mount(self) -> None:
        self.query_one("#session-select-list", ListView).focus()

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return
        selected_id = self._item_id_to_session_id.get(message.item.id)
        if selected_id:
            self.dismiss(selected_id)

    def action_rename_session(self) -> None:
        list_view = self.query_one("#session-select-list", ListView)
        if list_view.highlighted_child and list_view.highlighted_child.id:
            session_id = self._item_id_to_session_id.get(list_view.highlighted_child.id)
            if not session_id:
                return

            initial_name = ""
            for s in self.sessions:
                if str(s.get("id")) == session_id:
                    initial_name = s.get("name") or ""
                    break

            def on_rename_submitted(new_name: Optional[str]) -> None:
                if not new_name or not new_name.strip() or self._on_rename is None:
                    return
                clean_name = new_name.strip()

                async def do_rename() -> None:
                    renamed = await self._on_rename(session_id, clean_name)
                    if not renamed:
                        return
                    self._update_session_name(session_id, clean_name)

                self.app.run_worker(do_rename())

            self.app.push_screen(
                TaskTitleModalScreen("Rename Session", initial=initial_name),
                on_rename_submitted,
            )

    def action_new_session(self) -> None:
        self.dismiss("new")

    def action_delete_session(self) -> None:
        list_view = self.query_one("#session-select-list", ListView)
        if list_view.highlighted_child and list_view.highlighted_child.id:
            session_id = self._item_id_to_session_id.get(list_view.highlighted_child.id)
            if session_id:
                self.dismiss(f"delete:{session_id}")

    def _update_session_name(self, session_id: str, new_name: str) -> None:
        for s in self.sessions:
            if str(s.get("id")) == session_id:
                s["name"] = new_name
                break

        item_id = next(
            (item_id for item_id, sid in self._item_id_to_session_id.items() if sid == session_id),
            None,
        )
        if not item_id:
            return

        list_view = self.query_one("#session-select-list", ListView)
        item = next((child for child in list_view.children if child.id == item_id), None)
        if not isinstance(item, ListItem):
            return

        try:
            label_widget = item.query_one(Static)
        except Exception:
            return

        session = next((s for s in self.sessions if str(s.get("id")) == session_id), None)
        if not session:
            return

        label_widget.update(self._format_session_row(session))


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
        m_list: Optional[ListView] = None
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

        # Focus the model list immediately for deterministic test interaction
        if m_list:
            try:
                m_list.focus()
            except Exception:
                pass

        # Fallback focus for environment variability
        def focus_model_list():
            try:
                self.query_one("#model-select-list", ListView).focus()
            except Exception:
                pass

        self.call_after_refresh(focus_model_list)

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if not message.item or not message.item.id:
            return

        try:
            list_id = message.list_view.id
            item_id = str(message.item.id)
            # Support both 'm-0' and 'model-0' style IDs
            parts = item_id.split("-")
            idx = int(parts[-1])

            if list_id == "model-select-list":
                self.selected_model = self.models[idx]
                # Update markers in model list
                for i, item in enumerate(message.list_view.query(ListItem)):
                    marker = "[x]" if i == idx else "[ ]"
                    try:
                        item.query_one(Static).update(f"{marker} {self.models[i]}")
                    except Exception:
                        pass

                # Advance focus to reasoning list
                r_list = self.query_one("#reasoning-select-list", ListView)
                # Ensure marker sync in reasoning list matches current selected reasoning
                for i, item in enumerate(r_list.query(ListItem)):
                    marker = "[x]" if self.reasoning_levels[i] == self.selected_reasoning else "[ ]"
                    try:
                        item.query_one(Static).update(f"{marker} {self.reasoning_levels[i]}")
                    except Exception:
                        pass
                r_list.focus()

            elif list_id == "reasoning-select-list":
                self.selected_reasoning = self.reasoning_levels[idx]
                # Update markers in reasoning list before dismissal
                for i, item in enumerate(message.list_view.query(ListItem)):
                    marker = "[x]" if i == idx else "[ ]"
                    try:
                        item.query_one(Static).update(f"{marker} {self.reasoning_levels[i]}")
                    except Exception:
                        pass
                # Dismiss immediately upon reasoning selection
                self.dismiss((self.selected_model, self.selected_reasoning))
        except (ValueError, IndexError, Exception) as e:
            logger.error("Failed to handle selection in combined modal: %s", e)


@dataclass
class RuntimeState:
    """Encapsulates the runtime state for a specific worktree."""

    executor: ExecutorManager
    session_id: Optional[str] = None
    job_in_progress: bool = False
    current_job_id: Optional[str] = None
    session_switch_in_progress: bool = False
    current_switch_target_session_id: Optional[str] = None
    pending_prompt: Optional[str] = None
    pending_switch_prompt: Optional[tuple[str, str]] = None
    pending_updated_at: float = 0
    pending_generation: int = 0
    pending_min_wait_until: float = 0.0
    current_job_cost: float = 0.0
    session_total_cost: float = 0.0
    session_total_cost_id: Optional[str] = None
    executor_started: bool = False
    executor_ready: bool = False
    current_branch: str = "unknown"
    auto_rename_eligible: bool = False
    renamed: bool = False

    # Worktree-scoped settings
    agent_mode: str = "LUTZ"
    planner_model: str = "gpt-5.2"
    code_model: str = "gemini-3-flash-preview"
    reasoning_level: str = "low"
    reasoning_level_code: str = "disable"
    auto_commit: bool = True


class BrokkApp(App):
    """The main Brokk TUI application."""

    CSS_PATH = "styles/app.tcss"
    COMMAND_PALETTE_DISPLAY = "Settings"
    BINDINGS = [
        # Footer/help-bar ordering: Context, Tasks, Settings
        Binding("ctrl+c", "handle_ctrl_c", "Quit", show=True),
        Binding("ctrl+p", "command_palette", "Settings", show=True),
        Binding("ctrl+o", "toggle_output", "Toggle Output", show=True),
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
        self.settings = Settings.load()
        self._jar_path = jar_path
        self._executor_version = executor_version
        self._executor_snapshot = executor_snapshot
        self._vendor = vendor
        self.requested_session_id = session_id
        self.resume_session = resume_session

        self.runtimes: Dict[Path, RuntimeState] = {}
        self.current_worktree = resolve_workspace_dir(workspace_dir or Path.cwd())

        # Initialize primary executor
        if executor:
            primary_executor = executor
            if workspace_dir:
                primary_executor.workspace_dir = self.current_worktree
            if vendor is not None:
                primary_executor.vendor = vendor
        else:
            primary_executor = self._create_executor(self.current_worktree)

        # Capture baseline defaults from settings at initialization.
        # This prevents custom values in one worktree from bleeding into new ones.
        self._baseline_planner_model = self._normalize_setting(self.settings.last_model, "gpt-5.2")
        self._baseline_code_model = self._normalize_setting(
            self.settings.last_code_model, "gemini-3-flash-preview"
        )
        self._baseline_reasoning_level = self._normalize_setting(
            self.settings.last_reasoning_level, "low"
        )
        self._baseline_reasoning_level_code = self._normalize_setting(
            self.settings.last_code_reasoning_level, "disable"
        )
        self._baseline_auto_commit = bool(
            self.settings.last_auto_commit if self.settings.last_auto_commit is not None else True
        )

        # Initialize initial worktree runtime state with baseline defaults
        self.runtimes[self.current_worktree] = RuntimeState(
            executor=primary_executor,
            planner_model=self._baseline_planner_model,
            code_model=self._baseline_code_model,
            reasoning_level=self._baseline_reasoning_level,
            reasoning_level_code=self._baseline_reasoning_level_code,
            auto_commit=self._baseline_auto_commit,
        )

        self._set_theme(self.settings.theme)
        self.show_verbose_output: bool = False

        self._startup_pending_prompt: Optional[str] = None
        self._resubmit_grace_s: float = 0.2
        self._last_ctrl_c_time: float = 0
        self._auto_rename_eligible_sessions: set[str] = set()
        self._renamed_sessions: set[str] = set()
        self._refresh_context_lock = asyncio.Lock()
        self._reported_refresh_errors: set[str] = set()
        self._rename_session_lock = asyncio.Lock()
        self._session_switch_lock = asyncio.Lock()
        self._reasoning_target: str = "planner"

        self._tasklist_restore_focus_widget: Any | None = None

        self._is_mounted = False
        self._shutting_down = False
        self._shutdown_completed = False
        self._shutdown_lock = asyncio.Lock()

        repo_root = WorktreeService.find_repo_root(self.current_worktree)
        self.worktree_service = WorktreeService(repo_root) if repo_root else None

    def _ensure_baseline_defaults(self) -> None:
        """Initializes baseline defaults from settings if not already present."""
        if not hasattr(self, "settings"):
            self.settings = Settings.load()

        if not hasattr(self, "_baseline_planner_model"):
            self._baseline_planner_model = self._normalize_setting(
                self.settings.last_model, "gpt-5.2"
            )
        if not hasattr(self, "_baseline_code_model"):
            self._baseline_code_model = self._normalize_setting(
                self.settings.last_code_model, "gemini-3-flash-preview"
            )
        if not hasattr(self, "_baseline_reasoning_level"):
            self._baseline_reasoning_level = self._normalize_setting(
                self.settings.last_reasoning_level, "low"
            )
        if not hasattr(self, "_baseline_reasoning_level_code"):
            self._baseline_reasoning_level_code = self._normalize_setting(
                self.settings.last_code_reasoning_level, "disable"
            )
        if not hasattr(self, "_baseline_auto_commit"):
            self._baseline_auto_commit = bool(
                self.settings.last_auto_commit
                if self.settings.last_auto_commit is not None
                else True
            )

    def _lazy_init_runtimes(self) -> None:
        """Ensures core runtime state exists (handles partially initialized app in tests)."""
        if not hasattr(self, "runtimes"):
            self.runtimes = {}
        if not hasattr(self, "_auto_rename_eligible_sessions"):
            self._auto_rename_eligible_sessions = set()
        if not hasattr(self, "_renamed_sessions"):
            self._renamed_sessions = set()
        if not hasattr(self, "settings"):
            self.settings = Settings.load()
        if not hasattr(self, "_rename_session_lock"):
            self._rename_session_lock = asyncio.Lock()
        if not hasattr(self, "current_worktree"):
            self.current_worktree = resolve_workspace_dir(Path.cwd())
        self._ensure_baseline_defaults()

    @property
    def current_runtime(self) -> RuntimeState:
        """Returns the RuntimeState for the currently selected worktree."""
        self._lazy_init_runtimes()
        return self.get_runtime(self.current_worktree)

    @property
    def executor(self) -> ExecutorManager:
        """Returns the executor for the currently selected worktree."""
        return self.current_runtime.executor

    @executor.setter
    def executor(self, value: ExecutorManager) -> None:
        """Allows tests to inject mock executors into the current runtime."""
        self.current_runtime.executor = value

    @property
    def session_id(self) -> Optional[str]:
        return self.current_runtime.session_id

    @session_id.setter
    def session_id(self, value: Optional[str]) -> None:
        self.current_runtime.session_id = value

    @property
    def job_in_progress(self) -> bool:
        return self.current_runtime.job_in_progress

    @job_in_progress.setter
    def job_in_progress(self, value: bool) -> None:
        self.current_runtime.job_in_progress = value

    @property
    def current_job_id(self) -> Optional[str]:
        return self.current_runtime.current_job_id

    @current_job_id.setter
    def current_job_id(self, value: Optional[str]) -> None:
        self.current_runtime.current_job_id = value

    @property
    def session_switch_in_progress(self) -> bool:
        return self.current_runtime.session_switch_in_progress

    @session_switch_in_progress.setter
    def session_switch_in_progress(self, value: bool) -> None:
        self.current_runtime.session_switch_in_progress = value

    @property
    def current_job_cost(self) -> float:
        return self.current_runtime.current_job_cost

    @current_job_cost.setter
    def current_job_cost(self, value: float) -> None:
        self.current_runtime.current_job_cost = value

    @property
    def session_total_cost(self) -> float:
        return self.current_runtime.session_total_cost

    @session_total_cost.setter
    def session_total_cost(self, value: float) -> None:
        self.current_runtime.session_total_cost = value

    @property
    def session_total_cost_id(self) -> Optional[str]:
        return self.current_runtime.session_total_cost_id

    @property
    def _current_switch_target_session_id(self) -> Optional[str]:
        return self.current_runtime.current_switch_target_session_id

    @property
    def _pending_switch_prompt(self) -> Optional[tuple[str, str]]:
        return self.current_runtime.pending_switch_prompt

    @_pending_switch_prompt.setter
    def _pending_switch_prompt(self, value: Optional[tuple[str, str]]) -> None:
        self.current_runtime.pending_switch_prompt = value

    @property
    def _pending_prompt(self) -> Optional[str]:
        return self.current_runtime.pending_prompt

    @_pending_prompt.setter
    def _pending_prompt(self, value: Optional[str]) -> None:
        self.current_runtime.pending_prompt = value

    @property
    def _executor_ready(self) -> bool:
        return self.current_runtime.executor_ready

    @_executor_ready.setter
    def _executor_ready(self, value: bool) -> None:
        self.current_runtime.executor_ready = value

    def _normalize_setting(self, value: Any, fallback: str) -> str:
        """Safely normalizes an optional string setting to avoid 'None' literal."""
        if value is None:
            return fallback
        s = str(value).strip()
        return s if s and s.lower() != "none" else fallback

    def get_runtime(self, workspace_path: Path) -> RuntimeState:
        """Lazily retrieves or creates a runtime for a given path."""
        self._lazy_init_runtimes()
        self._ensure_baseline_defaults()
        path = resolve_workspace_dir(workspace_path)
        if path not in self.runtimes:
            exec_mgr = self._create_executor(path)
            # Use captured baseline defaults for first-time runtimes to ensure isolation.
            self.runtimes[path] = RuntimeState(
                executor=exec_mgr,
                planner_model=self._baseline_planner_model,
                code_model=self._baseline_code_model,
                reasoning_level=self._baseline_reasoning_level,
                reasoning_level_code=self._baseline_reasoning_level_code,
                auto_commit=self._baseline_auto_commit,
            )
        return self.runtimes[path]

    def _create_executor(self, workspace_dir: Path) -> ExecutorManager:
        return ExecutorManager(
            workspace_dir=workspace_dir,
            jar_path=getattr(self, "_jar_path", None),
            executor_version=getattr(self, "_executor_version", None),
            executor_snapshot=getattr(self, "_executor_snapshot", True),
            vendor=getattr(self, "_vendor", None),
            exit_on_stdin_eof=True,
            brokk_api_key=self.settings.get_brokk_api_key(),
        )

    def run_worker(self, coroutine: Awaitable[Any], **kwargs: Any) -> asyncio.Task[Any]:
        """
        Overridden to prevent starting background tasks if the app is not
        yet running or is in a test environment where the loop is managed
        differently.
        """
        if not self._is_mounted:
            # Fallback for early startup workers or tests
            try:
                loop = asyncio.get_running_loop()
                return loop.create_task(coroutine)
            except RuntimeError:
                # No loop running, return a dummy or let it fail gracefully
                pass
        return super().run_worker(coroutine, **kwargs)

    def _schedule(self, coroutine: Awaitable[Any], **kwargs: Any) -> Any:
        """Schedules a coroutine and handles un-awaited cleanup in tests."""
        result = self.run_worker(coroutine, **kwargs)
        # If run_worker was mocked (common in tests), it might return the coroutine
        # or a MagicMock instead of a Task/Worker. Close the coro to avoid RuntimeWarning.
        if asyncio.iscoroutine(coroutine) and not isinstance(
            result, (asyncio.Task, asyncio.Future)
        ):
            try:
                coroutine.close()
            except Exception:
                pass
        return result

    @property
    def agent_mode(self) -> str:
        return self.current_runtime.agent_mode

    @agent_mode.setter
    def agent_mode(self, value: str) -> None:
        self.current_runtime.agent_mode = value

    @property
    def current_mode(self) -> str:
        """Alias for agent_mode used by tests and for unified access."""
        return self.agent_mode

    @current_mode.setter
    def current_mode(self, value: str) -> None:
        self.agent_mode = value

    @property
    def current_model(self) -> str:
        return self.current_runtime.planner_model

    @current_model.setter
    def current_model(self, value: str) -> None:
        self.current_runtime.planner_model = value

    @property
    def code_model(self) -> str:
        return self.current_runtime.code_model

    @code_model.setter
    def code_model(self, value: str) -> None:
        self.current_runtime.code_model = value

    @property
    def reasoning_level(self) -> str:
        return self.current_runtime.reasoning_level

    @reasoning_level.setter
    def reasoning_level(self, value: str) -> None:
        self.current_runtime.reasoning_level = value

    @property
    def reasoning_level_code(self) -> str:
        return self.current_runtime.reasoning_level_code

    @reasoning_level_code.setter
    def reasoning_level_code(self, value: str) -> None:
        self.current_runtime.reasoning_level_code = value

    @property
    def auto_commit(self) -> bool:
        return self.current_runtime.auto_commit

    @auto_commit.setter
    def auto_commit(self, value: bool) -> None:
        self.current_runtime.auto_commit = value

    def _maybe_chat(self, path: Optional[Path] = None) -> Optional[ChatPanel]:
        """Safely attempt to get the ChatPanel for a path, returning current if path is None."""
        try:
            target_path = path or self.current_worktree
            # Check for container in the app DOM or active screen
            container = self.query_one("#chat-container", ChatContainer)
            return container.get_panel(target_path)
        except Exception:
            # Fallback for tests using mocks or direct ChatPanel placement
            try:
                return self.query_one(ChatPanel)
            except Exception:
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
            runtime = self.current_runtime
            status.update_status(
                mode=getattr(self, "current_mode", getattr(self, "agent_mode", "unknown")),
                model=getattr(self, "current_model", None),
                reasoning=getattr(self, "reasoning_level", None),
                workspace=str(self.current_worktree),
                branch=runtime.current_branch,
                turn_cost=runtime.current_job_cost,
                session_cost=runtime.session_total_cost,
            )
        except Exception:
            # Swallow all errors when updating UI that's possibly not mounted in tests.
            return

    def compose(self) -> ComposeResult:
        from brokk_code.widgets.chat_panel import ChatContainer

        with Horizontal():
            yield ChatContainer(id="chat-container")
            yield TaskListPanel(id="side-tasklist")

    async def on_mount(self) -> None:
        self._is_mounted = True

        # Ensure initial runtime/panel exists
        try:
            container = self.query_one("#chat-container", ChatContainer)
        except Exception:
            # If query fails, we might be in a test that didn't compose correctly
            return

        # Ensure the first panel created is for the current worktree so it gets #chat-main
        chat = container.switch_to(self.current_worktree)

        # Fix startup race: ensure visibility after layout/mount cycle completes.
        def sync_token_bar():
            c = self._maybe_chat()
            if c:
                c.set_token_bar_visible(True)

        # Multiple passes to ensure visibility across different mount timings
        sync_token_bar()
        self.set_timer(0.001, sync_token_bar)
        self.call_after_refresh(sync_token_bar)
        self.set_timer(0.1, sync_token_bar)

        logger.info("Using workspace directory: %s", self.executor.workspace_dir)
        if chat:
            chat.show_verbose = self.show_verbose_output

            # Load initial prompt history for arrow-key navigation
            history = load_history(self.executor.workspace_dir)
            chat.set_history(history)

            self._show_welcome_message()

        # Check for API key before starting executor
        if not self.settings.get_brokk_api_key():

            async def on_key_entered(key: str) -> bool:
                try:
                    await asyncio.to_thread(write_brokk_api_key, key)
                    self.executor.brokk_api_key = key
                    if chat:
                        chat.add_system_message("API key saved. Starting Brokk executor...")
                    self.run_worker(self._start_executor())
                    return True
                except Exception as e:
                    logger.exception("Failed to save API key on startup")
                    raise e

            self.push_screen(BrokkApiKeyModalScreen(on_submit=on_key_entered))
        else:
            if chat:
                chat.add_system_message("Starting Brokk executor...")
            self.run_worker(self._start_executor())
        self.run_worker(self._monitor_executor())
        self.run_worker(self._poll_tasklist())
        self.run_worker(self._poll_context())
        self._update_statusline()

    async def _start_executor(self, path: Optional[Path] = None) -> None:
        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        chat = self._maybe_chat()
        if chat and target_path == self.current_worktree:
            chat.set_job_running(True)
        try:
            from brokk_code.session_persistence import (
                get_session_zip_resume_path,
                load_last_session_id,
                save_last_session_id,
            )

            await runtime.executor.start()
            runtime.executor_started = True

            # Fetch and display effective build hint immediately
            try:
                live_info = await runtime.executor.get_health_live()
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
                session_to_resume = load_last_session_id(runtime.executor.workspace_dir)

            resumed = False
            if session_to_resume:
                zip_path = get_session_zip_resume_path(
                    runtime.executor.workspace_dir, session_to_resume
                )
                if zip_path.exists():
                    try:
                        msg = f"Resuming session {session_to_resume}..."
                        if chat:
                            chat.add_system_message(msg)
                        else:
                            logger.info(msg)
                        zip_bytes = zip_path.read_bytes()
                        await runtime.executor.import_session_zip(
                            zip_bytes, session_id=session_to_resume
                        )
                        resumed = True
                    except Exception as e:
                        logger.warning("Failed to resume session %s: %s", session_to_resume, e)

            if not resumed:
                sid = await runtime.executor.create_session()
                if sid:
                    runtime.auto_rename_eligible = True

            if runtime.executor.session_id:
                save_last_session_id(runtime.executor.workspace_dir, runtime.executor.session_id)

            if await runtime.executor.wait_ready():
                runtime.executor_ready = True
                # Initial context load
                self.run_worker(self._refresh_context_panel(target_path))

                if resumed:
                    try:
                        conversation_data = await runtime.executor.get_conversation()
                        replayed = self._replay_conversation_entries(conversation_data)
                        if replayed:
                            msg = (
                                f"Replayed {replayed} conversation "
                                f"{'entry' if replayed == 1 else 'entries'}."
                            )
                            if chat:
                                chat.add_system_message(msg)
                            else:
                                logger.info(msg)
                    except Exception as e:
                        logger.warning("Failed to replay conversation transcript: %s", e)

                # Process prompt queued during startup
                if self._startup_pending_prompt:
                    queued_prompt = self._startup_pending_prompt
                    self._startup_pending_prompt = None
                    self.run_worker(self._run_job(queued_prompt))
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
        finally:
            if chat:
                chat.set_job_running(False)

    async def _monitor_executor(self) -> None:
        """Background worker to check if the executors die unexpectedly."""
        while True:
            await asyncio.sleep(2.0)
            if self._shutting_down:
                break

            for path, runtime in list(self.runtimes.items()):
                if not runtime.executor_started:
                    continue

                if not runtime.executor.check_alive():
                    if self._shutting_down or self._shutdown_completed:
                        break
                    msg = f"Executor for {path} crashed unexpectedly."
                    runtime.executor_started = False
                    runtime.executor_ready = False
                    chat = self._maybe_chat()
                    if chat and path == self.current_worktree:
                        chat.add_system_message(msg, level="ERROR")
                    else:
                        logger.error(msg)

    async def _poll_tasklist(self) -> None:
        """Periodically refreshes the task list details for all ready runtimes."""
        while True:
            for path, runtime in list(self.runtimes.items()):
                if runtime.executor_ready:
                    try:
                        tasklist_data = await runtime.executor.get_tasklist()
                        if path == self.current_worktree:
                            self._update_tasklist_details_all(tasklist_data)
                    except Exception:
                        logger.debug("Periodic tasklist poll failed for %s", path, exc_info=True)
            await asyncio.sleep(15.0)

    async def _poll_context(self) -> None:
        """Periodically refreshes the context panel for the current runtime."""
        while True:
            try:
                if self.current_runtime.executor_ready:
                    await self._refresh_context_panel()
            except Exception:
                logger.debug("Periodic context poll failed", exc_info=True)
            await asyncio.sleep(random.uniform(10.0, 15.0))

    async def _refresh_context_panel(self, path: Optional[Path] = None) -> None:
        """Fetches latest context and updates UI for the specified (or current) path."""
        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        if not runtime.executor_ready:
            return

        async with self._refresh_context_lock:
            try:
                context_data = await runtime.executor.get_context()
                runtime.current_branch = context_data.get("branch", "unknown")

                remote_total = context_data.get("totalCost")
                current_sid = runtime.executor.session_id
                if isinstance(remote_total, (int, float)) and not runtime.job_in_progress:
                    remote_val = round(float(remote_total), 6)
                    if current_sid != runtime.session_total_cost_id:
                        runtime.session_total_cost = remote_val
                        runtime.session_total_cost_id = current_sid
                    else:
                        runtime.session_total_cost = max(runtime.session_total_cost, remote_val)

                if target_path == self.current_worktree:
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

                    chat = self._maybe_chat()
                    if chat:
                        used = context_data.get("usedTokens", 0)
                        max_tokens = context_data.get("maxTokens")
                        fragments = context_data.get("fragments")
                        chat.set_token_usage(
                            used, max_tokens, fragments, session_cost=runtime.session_total_cost
                        )

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
        chat = self._maybe_chat()
        if not chat:
            return
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
        chat = self._maybe_chat()
        if not chat:
            return
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
        chat = self._maybe_chat()
        if not chat:
            return
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
        chat = self._maybe_chat()
        if not chat:
            return
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
            chat = self._maybe_chat()
            if chat:
                chat.add_history_entry(raw_text)
                chat.add_user_message(raw_text)
            if self.session_switch_in_progress and self._current_switch_target_session_id:
                self._pending_switch_prompt = (self._current_switch_target_session_id, raw_text)
                if chat:
                    chat.add_system_message("Queuing prompt until session switch is complete...")
            elif self.job_in_progress and self.current_job_id:
                self._pending_prompt = raw_text
                now = time.monotonic()
                self._pending_updated_at = now
                self._pending_generation += 1
                self._pending_min_wait_until = max(
                    self._pending_min_wait_until, now + self._resubmit_grace_s
                )
                # Avoid redundant cancellation messages if already pending
                if self._pending_generation == 1 and chat:
                    chat.add_system_message("Interrupting current job to start new request...")
                self.run_worker(self.executor.cancel_job(self.current_job_id))
            elif not self._executor_ready:
                self._startup_pending_prompt = raw_text
                if chat:
                    chat.add_system_message("Queuing prompt until Brokk is ready...")
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

    async def _login_openai(self) -> None:
        """Async helper to initiate OpenAI OAuth login flow."""
        chat = self._maybe_chat()
        if not chat:
            return

        if not self._executor_ready:
            chat.add_system_message(
                "Brokk executor is not yet ready. Please wait a moment and try again.",
                level="WARNING",
            )
            return

        try:
            resp = await self.executor.start_openai_oauth()
            auth_url = resp.get("url") if isinstance(resp, dict) else None
            if isinstance(auth_url, str) and auth_url:
                opened = False
                try:
                    opened = bool(await asyncio.to_thread(webbrowser.open, auth_url))
                except Exception:
                    logger.exception("Failed to open OpenAI OAuth URL from TUI client")
                chat.add_system_message(
                    "Starting OpenAI authorization. "
                    + ("Browser opened. " if opened else "")
                    + "Use the OpenAI Authorization modal to copy the exact URL."
                )
                try:
                    self.push_screen(OpenAiAuthUrlModalScreen(auth_url))
                except Exception:
                    logger.exception("Failed to open OpenAI OAuth URL modal")
                    chat.add_system_message(
                        "OpenAI auth URL (exact): " + auth_url,
                        level="WARNING",
                    )
            else:
                chat.add_system_message(
                    "Opening browser for OpenAI authorization. After completing the login flow, "
                    "Codex-gated models will become available."
                )
        except Exception as e:
            logger.exception("OpenAI OAuth login failed")
            chat.add_system_message(f"Failed to start OpenAI login: {e}", level="ERROR")

    def _derive_session_name(self, text: str) -> str:
        """Derives a short session name from the first prompt text."""
        # Strip leading mentions and common command-like prefixes
        cleaned = re.sub(r"^(?:@\S+\s+|/ask\s+|/lutz\s+|/code\s+)+", "", text, flags=re.IGNORECASE)
        # Take first line and truncate
        first_line = cleaned.strip().split("\n")[0]
        if len(first_line) > 60:
            return first_line[:57].strip() + "..."
        return first_line

    async def _maybe_rename_session(self, first_prompt: str, path: Optional[Path] = None) -> None:
        """Asynchronously renames the session if it's new/unnamed."""
        self._lazy_init_runtimes()
        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        session_id = runtime.executor.session_id

        # Use legacy sets for tracking if possible (backwards compatibility for tests)
        eligible = runtime.auto_rename_eligible or (
            session_id is not None and session_id in self._auto_rename_eligible_sessions
        )
        if not session_id or not eligible:
            return

        async with self._rename_session_lock:
            if runtime.renamed or session_id in self._renamed_sessions:
                return

            # Check if the session name is generic before renaming
            try:
                sessions_data = await runtime.executor.list_sessions()
                current_id = sessions_data.get("currentSessionId")
                sessions = sessions_data.get("sessions", [])

                # Find current session and check if it's using the default name
                current_session = next((s for s in sessions if s.get("id") == current_id), None)
                if not current_session or current_session.get("name") != "TUI Session":
                    # Already named or not found; mark as "renamed" to skip further checks
                    runtime.renamed = True
                    self._renamed_sessions.add(session_id)
                    return

                new_name = self._derive_session_name(first_prompt)
                if not new_name:
                    # If we can't derive a name, consider it done so we don't keep trying
                    runtime.renamed = True
                    self._renamed_sessions.add(session_id)
                    return

                await runtime.executor.rename_session(session_id, new_name)
                runtime.renamed = True
                self._renamed_sessions.add(session_id)

                chat = self._maybe_chat()
                if chat and target_path == self.current_worktree:
                    chat.add_system_message(f"Session renamed to: {new_name}")
            except Exception as e:
                logger.warning("Failed to auto-rename session: %s", e)

    async def _run_job(self, task_input: str, path: Optional[Path] = None) -> None:
        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        # Attempt auto-rename on first prompt if session is default
        if runtime.executor_ready and runtime.executor.session_id:
            self.run_worker(self._maybe_rename_session(task_input, target_path))

        # Reset per-job cost accumulator
        runtime.current_job_cost = 0.0
        runtime.job_in_progress = True
        chat = self._maybe_chat(target_path)
        if chat:
            chat.set_job_running(True)
            chat.set_response_pending()
        attached_fragment_ids: List[str] = []
        try:
            attached_fragment_ids = await self._attach_mentions_to_context(task_input)
            runtime.current_job_id = await runtime.executor.submit_job(
                task_input,
                self.current_model,
                code_model=self.code_model,
                reasoning_level=self.reasoning_level,
                reasoning_level_code=self.reasoning_level_code,
                mode=self.current_mode,
                auto_commit=self.auto_commit,
            )
            async for event in runtime.executor.stream_events(runtime.current_job_id):
                self._handle_event(event, target_path)
        except Exception as e:
            if (
                runtime.current_job_id is None
                and attached_fragment_ids
                and hasattr(runtime.executor, "drop_context_fragments")
            ):
                try:
                    await runtime.executor.drop_context_fragments(attached_fragment_ids)
                except Exception:
                    logger.exception(
                        "Failed to rollback context fragments after submit_job failure: %s",
                        attached_fragment_ids,
                    )

            if chat:
                err_type = type(e).__name__
                chat.add_system_message(
                    f"Job failed or interrupted ({err_type}): {e}",
                    level="ERROR",
                )
            else:
                logger.error("Job failed or interrupted (%s): %s", type(e).__name__, e)
        finally:
            if chat:
                chat.set_response_finished()
                chat.set_job_running(False)

            await asyncio.sleep(0)

            if runtime.pending_prompt:
                debounce_window = 0.05
                while True:
                    now = time.monotonic()
                    current_gen = runtime.pending_generation
                    elapsed_since_update = now - runtime.pending_updated_at

                    if (
                        now >= runtime.pending_min_wait_until
                        and elapsed_since_update >= debounce_window
                        and runtime.pending_generation == current_gen
                    ):
                        break
                    await asyncio.sleep(0.01)

                next_prompt = runtime.pending_prompt
                runtime.pending_prompt = None
                runtime.pending_updated_at = 0
                runtime.pending_generation = 0
                runtime.pending_min_wait_until = 0.0

                if next_prompt:
                    await self._run_job(next_prompt, target_path)
                else:
                    runtime.job_in_progress = False
                    runtime.current_job_id = None
            else:
                runtime.job_in_progress = False
                runtime.current_job_id = None

    def _handle_event(self, event: Dict[str, Any], path: Optional[Path] = None) -> None:
        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        event_type = event.get("type")
        data = event.get("data", {})
        chat = self._maybe_chat()
        is_current = target_path == self.current_worktree

        if event_type == "LLM_TOKEN":
            if chat and is_current:
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
            cost = data.get("cost")

            level_upper = level.upper()
            is_cost = level_upper == "COST"
            is_confirm = level_upper == "CONFIRM"

            if is_cost and isinstance(cost, (int, float)):
                increment = float(cost)
                runtime.current_job_cost = round(runtime.current_job_cost + increment, 6)
                runtime.session_total_cost = round(runtime.session_total_cost + increment, 6)
                if is_current:
                    self._update_statusline()

            if chat and is_current and not is_cost and not is_confirm:
                chat.add_system_message(msg, level=level)
        elif event_type == "ERROR":
            msg = data.get("message", "Unknown error")
            if chat and is_current:
                chat.add_system_message(msg, level="ERROR")
        elif event_type == "COMMAND_RESULT":
            if chat and is_current:
                stage = data.get("stage", "Command")
                command = data.get("command", "")
                success = data.get("success", False)
                output = data.get("output", "").strip()
                exception = data.get("exception")

                status = "[bold green]Success[/]" if success else "[bold red]Failed[/]"
                header = f"**{stage}**: `{command}` ({status})"

                parts = [header]
                if output:
                    parts.append(f"```\n{output}\n```")
                if exception:
                    parts.append(f"**Error**: {exception}")

                chat.add_tool_result("\n\n".join(parts))
        elif event_type == "STATE_HINT":
            hint_name = data.get("name")
            if hint_name in ("contextHistoryUpdated", "workspaceUpdated"):
                self.run_worker(self._refresh_context_panel(path))

    def _replay_conversation_entries(self, conversation_data: Dict[str, Any]) -> int:
        """Render executor conversation history into the ChatPanel."""
        chat = self._maybe_chat()
        if not chat:
            return 0

        entries = conversation_data.get("entries")
        if not isinstance(entries, list):
            return 0

        replayed_entries = 0
        for entry in entries:
            if not isinstance(entry, dict):
                continue

            messages = entry.get("messages")
            if isinstance(messages, list):
                rendered_any = False
                for msg in messages:
                    if not isinstance(msg, dict):
                        continue

                    role = str(msg.get("role", "")).strip().lower()
                    text = msg.get("text")
                    if not isinstance(text, str):
                        text = ""

                    reasoning = msg.get("reasoning")
                    if isinstance(reasoning, str) and reasoning.strip():
                        content = reasoning.strip()
                        chat._message_history.append({"kind": "REASONING", "content": content})
                        chat._render_message_entry("REASONING", content)
                        rendered_any = True

                    if not text.strip():
                        continue

                    if role == "user":
                        chat.add_user_message(text)
                    elif role in ("ai", "assistant"):
                        chat.add_markdown(text)
                    elif "tool" in role:
                        chat.add_tool_result(text)
                    elif role in ("system", "notification", "error"):
                        chat.add_system_message(text, level="ERROR" if role == "error" else "INFO")
                    else:
                        chat.append_message(role.title() if role else "System", text)
                    rendered_any = True

                if rendered_any:
                    replayed_entries += 1
                continue

            summary = entry.get("summary")
            if isinstance(summary, str) and summary.strip():
                chat.add_markdown(summary)
                replayed_entries += 1

        return replayed_entries

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
        chat = self._maybe_chat()
        if not chat:
            return
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
            {"command": "/code", "description": "Set mode to CODE (direct implementation)"},
            {"command": "/ask", "description": "Set mode to ASK (questions only)"},
            {"command": "/lutz", "description": "Set mode to LUTZ (default; full agent access)"},
            {"command": "/mode", "description": "Open mode selection menu"},
            {"command": "/model", "description": "Change the planner LLM model"},
            {"command": "/model-code", "description": "Change the code LLM model"},
            {"command": "/autocommit", "description": "Toggle auto-commit for submitted jobs"},
            {"command": "/task", "description": "Open/close the task list"},
            {"command": "/context", "description": "Toggle and focus context panel"},
            {"command": "/sessions", "description": "List and switch between sessions"},
            {
                "command": "/worktree",
                "description": "Open worktree manager (switch/add/remove)",
            },
            {"command": "/history", "description": "Show recent prompt history"},
            {"command": "/history-clear", "description": "Clear prompt history"},
            {"command": "/info", "description": "Show current configuration and status"},
            {"command": "/settings", "description": "Open settings"},
            {"command": "/api-key", "description": "Update your Brokk API key"},
            {"command": "/login-openai", "description": "Connect your OpenAI ChatGPT subscription"},
            {"command": "/help", "description": "Show help message"},
            {"command": "/quit", "description": "Exit the application"},
            {"command": "/exit", "description": "Exit the application"},
        ]

    def _handle_command(self, cmd: str) -> None:
        chat = self._maybe_chat()
        if not chat:
            return
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
        elif base in ("/code", "/ask", "/lutz", "/plan"):
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
        elif base == "/login-openai":
            if len(parts) > 1:
                chat.add_system_message(
                    "Usage: /login-openai (opens browser for authorization)",
                    level="WARNING",
                )
            else:
                self.run_worker(self._login_openai())
        elif base == "/api-key":

            async def on_key_entered(key: str) -> bool:
                try:
                    await asyncio.to_thread(write_brokk_api_key, key)
                    self.executor.brokk_api_key = key
                    chat.add_system_message(
                        "API key updated. New key will be used on the next executor launch."
                    )
                    return True
                except Exception as e:
                    logger.error("Failed to update API key: %s", e)
                    chat.add_system_message(f"Failed to update API key: {e}", level="ERROR")
                    raise

            self.push_screen(
                BrokkApiKeyModalScreen(on_key_entered, "Update Brokk API Key", is_update=True)
            )
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
        elif base == "/sessions":
            self.run_worker(self._show_sessions())
        elif base == "/worktree":
            if len(parts) != 1:
                chat.add_system_message(
                    "Usage: /worktree",
                    level="WARNING",
                )
                return
            self.run_worker(self._show_worktrees())
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

    def action_select_mode(self) -> None:
        chat = self._maybe_chat()
        if chat:
            chat.open_mode_menu(["CODE", "ASK", "LUTZ", "PLAN"], self.agent_mode)

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

    def action_toggle_output(self) -> None:
        """Toggles visibility of reasoning and tool output."""
        self.show_verbose_output = not self.show_verbose_output
        chat = self._maybe_chat()
        if chat:
            chat.show_verbose = self.show_verbose_output
            chat.refresh_log(self.show_verbose_output)

    def action_toggle_mode(self) -> None:
        """Cycles through agent modes: CODE -> ASK -> LUTZ -> PLAN -> CODE."""
        modes = ["CODE", "ASK", "LUTZ", "PLAN"]
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
            try:
                chat_input = chat_panel.query_one("#chat-input", ChatInput)
                if chat_input.text.strip():
                    chat_panel.clear_input()
                    return
            except Exception:
                pass

        now = time.time()
        runtime = self.current_runtime

        if runtime.job_in_progress and runtime.current_job_id:
            runtime.pending_prompt = None
            runtime.pending_updated_at = 0
            runtime.pending_generation = 0
            runtime.pending_min_wait_until = 0.0
            if chat_panel:
                chat_panel.add_system_message("Cancelling job...")
            await runtime.executor.cancel_job(runtime.current_job_id)
            self._last_ctrl_c_time = now
            return

        if now - self._last_ctrl_c_time < 2.0:
            await self.action_quit()
        else:
            if chat_panel:
                chat_panel.add_system_message("Press Ctrl+C again to quit.")
            else:
                logger.info("Press Ctrl+C again to quit.")
            self._last_ctrl_c_time = now

    async def _shutdown_once(self, *, show_message: bool = True) -> None:
        """Perform shutdown actions for all runtimes."""
        async with self._shutdown_lock:
            if self._shutdown_completed:
                return

            self._shutting_down = True
            if show_message:
                msg = "Shutting down..."
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message(msg)
                else:
                    logger.info(msg)

            success = True
            for runtime in self.runtimes.values():
                runtime.executor_ready = False
                try:
                    await runtime.executor.stop()
                except Exception:
                    success = False
                    logger.debug("Executor stop failed during shutdown", exc_info=True)

            if success:
                self._shutdown_completed = True
            else:
                self._shutting_down = False

    async def action_quit(self) -> None:
        # Centralized shutdown; show_message True to surface to user via chat/logs.
        await self._shutdown_once(show_message=True)
        # After clean shutdown, exit the app
        try:
            self.exit()
        except Exception:
            # exit() may raise in some test harnesses; ignore to avoid double-shutdown.
            pass

    async def _show_worktrees(self) -> None:
        if not self.worktree_service:
            chat = self._maybe_chat()
            if chat:
                chat.add_system_message(
                    "Not in a git repository; worktrees unavailable.", level="WARNING"
                )
            return

        worktrees = await asyncio.to_thread(self.worktree_service.list_worktrees)

        def on_selected(action: tuple[str, Optional[Path]] | None) -> None:
            if not action:
                return
            action_type, selected_path = action
            if action_type == "switch" and selected_path and selected_path != self.current_worktree:
                self.run_worker(self._switch_to_worktree(selected_path))
            elif action_type == "add":
                self._worktree_add_workflow()
            elif action_type == "remove" and selected_path:
                self.run_worker(self._perform_worktree_remove(selected_path))

        self.push_screen(WorktreeSelectModal(worktrees, self.current_worktree), on_selected)

    def _worktree_add_workflow(self) -> None:
        def on_submit(name: Optional[str]) -> None:
            if not name or not name.strip():
                return
            self.run_worker(self._perform_worktree_add(name.strip()))

        self.push_screen(TaskTitleModalScreen("Add Worktree (name or path)"), on_submit)

    async def _perform_worktree_add(self, name: str) -> None:
        chat = self._maybe_chat()
        if not self.worktree_service:
            return

        raw_input = name.strip()
        # Absolute/explicit filesystem paths should not be treated as branch names.
        is_explicit_path = Path(raw_input).is_absolute() or raw_input.startswith((".", "~"))

        # Default behavior: plain names become sibling worktrees and branch names.
        repo_root = self.worktree_service.repo_root
        if is_explicit_path:
            target_path = Path(raw_input).expanduser().resolve()
            branch = None
        else:
            target_path = (repo_root.parent / raw_input).resolve()
            branch = raw_input

        if chat:
            chat.add_system_message(f"Creating worktree at {target_path}...")

        if branch is None:
            success = await asyncio.to_thread(self.worktree_service.add_worktree, target_path)
        else:
            success = await asyncio.to_thread(
                self.worktree_service.add_worktree, target_path, branch=branch
            )
        if success:
            if chat:
                chat.add_system_message(
                    f"Worktree created: {target_path}. Use /worktree to switch when ready."
                )
        elif chat:
            chat.add_system_message(f"Failed to create worktree at {target_path}", level="ERROR")

    async def _perform_worktree_remove(self, path: Path) -> None:
        chat = self._maybe_chat()
        if not self.worktree_service:
            return
        if path.resolve() == self.current_worktree.resolve():
            if chat:
                chat.add_system_message(
                    "Cannot remove the currently selected worktree.",
                    level="WARNING",
                )
            return

        success = await asyncio.to_thread(self.worktree_service.remove_worktree, path)
        if success:
            if chat:
                chat.add_system_message(f"Removed worktree: {path}")
            if path in self.runtimes:
                rt = self.runtimes.pop(path)
                await rt.executor.stop()
        elif chat:
            chat.add_system_message(f"Failed to remove worktree: {path}", level="ERROR")

    async def _worktree_remove_workflow(self) -> None:
        if not self.worktree_service:
            return
        worktrees = await asyncio.to_thread(self.worktree_service.list_worktrees)
        # Filter out current worktree - can't remove while active
        others = [wt for wt in worktrees if wt.path.resolve() != self.current_worktree.resolve()]

        if not others:
            chat = self._maybe_chat()
            if chat:
                chat.add_system_message("No other worktrees to remove.", level="WARNING")
            return

        def on_selected(path: Path | None) -> None:
            if path:
                self.run_worker(self._perform_worktree_remove(path))

        self.push_screen(WorktreeSelectModal(others, self.current_worktree), on_selected)

    async def _switch_to_worktree(self, path: Path) -> None:
        # Update current worktree BEFORE switching UI or getting runtime
        # so that _start_executor/get_runtime uses the new path correctly.
        self.current_worktree = path
        try:
            container = self.query_one("#chat-container", ChatContainer)
            chat = container.switch_to(path)
        except Exception:
            chat = self._maybe_chat()

        runtime = self.get_runtime(path)
        if not runtime.executor_started:
            if chat:
                chat.add_system_message(f"Starting executor for worktree: {path.name}...")
            self.run_worker(self._start_executor(path))

        self._update_statusline()
        # Refresh local context
        await self._refresh_context_panel(path)

    async def _show_sessions(self) -> None:
        chat = self._maybe_chat()
        if not self._executor_ready:
            if chat:
                chat.add_system_message(
                    "Executor is not ready. Cannot list sessions.", level="ERROR"
                )
            return

        try:
            data = await self.executor.list_sessions()
            sessions = data.get("sessions", [])
            current_id = data.get("currentSessionId", "")

            if not sessions:
                if chat:
                    chat.add_system_message("No sessions found.")
                return

            def on_selected(selected_id: str | None) -> None:
                if not selected_id:
                    return
                if selected_id == "new":
                    self._schedule(self._create_session_from_menu())
                elif selected_id.startswith("rename:"):
                    sid = selected_id.split(":", 1)[1]
                    self._schedule(self._rename_session_workflow(sid, sessions))
                elif selected_id.startswith("delete:"):
                    sid = selected_id.split(":", 1)[1]
                    self._schedule(self._delete_session_workflow(sid))
                elif selected_id != current_id:
                    self._schedule(self._switch_to_session(selected_id))

            async def on_rename(session_id: str, new_name: str) -> bool:
                return await self._rename_session(session_id, new_name)

            self.push_screen(SessionSelectModal(sessions, current_id, on_rename), on_selected)
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to list sessions: {e}", level="ERROR")

    async def _rename_session(self, session_id: str, new_name: str) -> bool:
        chat = self._maybe_chat()
        async with self._rename_session_lock:
            try:
                await self.executor.rename_session(session_id, new_name)
                self._renamed_sessions.add(session_id)
                if chat:
                    chat.add_system_message(f"Session renamed to: {new_name}")
                await self._refresh_context_panel()
                return True
            except Exception as e:
                if chat:
                    chat.add_system_message(f"Failed to rename session: {e}", level="ERROR")
                return False

    async def _create_session_from_menu(self, path: Optional[Path] = None) -> None:
        """Async worker to create a new session for the specified (or current) worktree."""
        from brokk_code.session_persistence import save_last_session_id

        target_path = path or self.current_worktree
        runtime = self.get_runtime(target_path)
        chat = self._maybe_chat()
        is_current = target_path == self.current_worktree

        try:
            if is_current and chat:
                chat.add_system_message("Creating new session...")

            session_id = await runtime.executor.create_session()
            runtime.auto_rename_eligible = True
            self._auto_rename_eligible_sessions.add(session_id)
            save_last_session_id(runtime.executor.workspace_dir, session_id)

            if is_current and chat:
                chat._message_history.clear()
                try:
                    log = chat.query_one("#chat-log")
                    res = log.query("*").remove()
                except Exception:
                    res = None
                if asyncio.iscoroutine(res):
                    await res

                await self._refresh_context_panel(target_path)
                chat.add_system_message(f"Created and switched to session {session_id}.")
        except Exception as e:
            if is_current and chat:
                chat.add_system_message(f"Failed to create session: {e}", level="ERROR")
            else:
                logger.error("Failed to create session for %s: %s", target_path, e)

    async def _rename_session_workflow(
        self, session_id: str, sessions: List[Dict[str, Any]]
    ) -> None:
        """Async worker for the rename session flow."""
        initial_name = ""
        for s in sessions:
            if str(s.get("id")) == session_id:
                initial_name = s.get("name") or ""
                break

        def on_rename_submitted(new_name: Optional[str]) -> None:
            if not new_name or not new_name.strip():
                return

            async def do_rename():
                await self._rename_session(session_id, new_name.strip())

            self._schedule(do_rename())

        self.push_screen(
            TaskTitleModalScreen("Rename Session", initial=initial_name), on_rename_submitted
        )

    async def _delete_session_workflow(self, session_id: str) -> None:
        """Async worker for the delete session flow."""
        chat = self._maybe_chat()
        try:
            await self.executor.delete_session(session_id)
            if chat:
                chat.add_system_message(f"Deleted session {session_id}")
            await self._refresh_context_panel()
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to delete session: {e}", level="ERROR")

    async def _switch_to_session(self, session_id: str) -> None:
        from brokk_code.session_persistence import save_last_session_id

        chat = self._maybe_chat()
        if not chat:
            return

        runtime = self.current_runtime
        async with self._session_switch_lock:
            if runtime.session_switch_in_progress:
                chat.add_system_message("A session switch is already in progress.", level="WARNING")
                return
            runtime.session_switch_in_progress = True
            runtime.current_switch_target_session_id = session_id

        _prev_job_cost = runtime.current_job_cost
        _prev_session_cost = runtime.session_total_cost

        try:
            chat.add_system_message(f"Switching to session {session_id}...")
            chat.set_job_running(True)

            runtime.current_job_cost = 0.0
            runtime.session_total_cost = 0.0

            await runtime.executor.switch_session(session_id)
            try:
                save_last_session_id(runtime.executor.workspace_dir, session_id)
            except Exception:
                logger.warning("Failed to persist session ID", exc_info=True)

            chat._message_history.clear()
            try:
                log = chat.query_one("#chat-log")
                res = log.query("*").remove()
            except Exception:
                res = None
            if asyncio.iscoroutine(res):
                await res

            conversation_data = await runtime.executor.get_conversation()
            self._replay_conversation_entries(conversation_data)

            await self._refresh_context_panel()
            chat.add_system_message(f"Successfully switched to session {session_id}.")

            if runtime.pending_switch_prompt:
                target_id, prompt = runtime.pending_switch_prompt
                if target_id == session_id:
                    runtime.pending_switch_prompt = None
                    self.run_worker(self._run_job(prompt))
                else:
                    runtime.pending_switch_prompt = None

        except Exception as e:
            logger.exception("Failed to switch session")
            runtime.current_job_cost = _prev_job_cost
            runtime.session_total_cost = _prev_session_cost
            chat.add_system_message(f"Failed to switch session: {e}", level="ERROR")
            if runtime.pending_switch_prompt:
                runtime.pending_switch_prompt = None
                chat.add_system_message("Dropped queued prompt due to session switch failure.")
        finally:
            runtime.session_switch_in_progress = False
            runtime.current_switch_target_session_id = None
            chat.set_job_running(False)

    async def _on_mount(self, event: Any) -> None:
        """Test compatibility alias for on_mount."""
        await self.on_mount()

    async def on_unmount(self) -> None:
        """Ensure cleanup even if app exits via other means."""
        # on_unmount is a fallback shutdown path; avoid double-running shutdown logic.
        await self._shutdown_once(show_message=False)


if __name__ == "__main__":
    app = BrokkApp()
    app.run()
