import asyncio
import logging
import os
import random
import re
import signal
import time
import uuid
import webbrowser
from datetime import datetime
from enum import StrEnum
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional

from textual import events
from textual.app import App, ComposeResult, ScreenStackError
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.css.query import NoMatches
from textual.screen import ModalScreen
from textual.widgets import Button, Input, ListItem, ListView, Static, TextArea

from brokk_code.event_utils import is_failure_state, safe_data
from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.git_utils import infer_github_repo_from_remote
from brokk_code.prompt_history import append_prompt, load_history
from brokk_code.settings import (
    DEFAULT_THEME,
    Settings,
    normalize_theme_name,
    write_brokk_api_key,
)
from brokk_code.welcome import build_welcome_message, get_braille_icon
from brokk_code.widgets.chat_panel import ChatInput, ChatPanel
from brokk_code.widgets.context_panel import ContextPanel
from brokk_code.widgets.dependencies_panel import DependenciesPanel
from brokk_code.widgets.review_panel import GuidedReviewPanel
from brokk_code.widgets.status_line import StatusLine
from brokk_code.widgets.tasklist_panel import TaskListPanel
from brokk_code.workspace import resolve_workspace_dir

logger = logging.getLogger(__name__)


class _JobLifecycleResult(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ContextModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the context panel."""

    BINDINGS = [
        Binding("escape", "close_context", "Close", show=False),
    ]

    def __init__(self, on_close: Callable[[], None]) -> None:
        super().__init__()
        self._on_close = on_close
        self._easter_egg_buf: list[str] = []

    def compose(self) -> ComposeResult:
        with Vertical(id="context-modal-container"):
            yield ContextPanel(id="context-panel")

    def on_mount(self) -> None:
        self.query_one(ContextPanel).focus()

    def on_key(self, event: events.Key) -> None:
        char = event.character
        if char:
            self._easter_egg_buf.append(char)
            if len(self._easter_egg_buf) > 5:
                self._easter_egg_buf.pop(0)
            if self._easter_egg_buf == list("!-!-!"):
                self._easter_egg_buf.clear()
                from brokk_code.widgets.brokk_defense import BrokkDefenseScreen

                self.app.push_screen(BrokkDefenseScreen())

    def action_close_context(self) -> None:
        self._on_close()
        self.dismiss(None)


class ReviewModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the guided review panel."""

    BINDINGS = [
        Binding("escape", "close_review", "Close", show=False),
    ]

    def __init__(self, on_close: Callable[[], None]) -> None:
        super().__init__()
        self._on_close = on_close

    def compose(self) -> ComposeResult:
        with Vertical(id="review-modal-container"):
            yield GuidedReviewPanel(id="review-panel")

    def on_mount(self) -> None:
        self.query_one(GuidedReviewPanel).focus()

    def action_close_review(self) -> None:
        self._on_close()
        self.dismiss(None)


class DependenciesModalScreen(ModalScreen[None]):
    """Full-screen modal wrapper for the dependencies panel."""

    BINDINGS = [
        Binding("escape", "close_dependencies", "Close", show=False),
    ]

    def __init__(self, on_close: Callable[[], None]) -> None:
        super().__init__()
        self._on_close = on_close

    def compose(self) -> ComposeResult:
        with Vertical(id="dependencies-modal-container"):
            yield DependenciesPanel(id="dependencies-panel")

    def on_mount(self) -> None:
        self.query_one("#dependencies-list", ListView).focus()
        self.app.run_worker(self.app._refresh_dependencies_panel())

    def action_close_dependencies(self) -> None:
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


class SessionCostsModalScreen(ModalScreen[None]):
    """Modal showing per-session cost line items and aggregates."""

    LEGACY_MIGRATION_OPERATION_TYPE = "LEGACY_MIGRATION"

    BINDINGS = [
        Binding("escape", "dismiss", "Close", show=False),
        Binding("q", "dismiss", "Close", show=False),
        Binding("enter", "dismiss", "Close", show=False),
    ]

    def __init__(self, cost_data: Dict[str, Any]) -> None:
        super().__init__()
        self.cost_data = cost_data

    def compose(self) -> ComposeResult:
        events = self.cost_data.get("events", [])
        total_cost = self.cost_data.get("totalCost", 0.0)

        with Vertical(id="session-costs-container"):
            yield Static("Session Cost Breakdown", id="session-costs-title")

            if not events:
                with VerticalScroll(id="session-costs-list-wrap"):
                    yield Static(
                        "No cost data available for this session.", id="session-costs-empty"
                    )
            else:
                with Horizontal(id="session-costs-header"):
                    yield Static("Timestamp", classes="col-ts")
                    yield Static("Type", classes="col-type")
                    yield Static("Model (Tier)", classes="col-model")
                    yield Static("Operation", classes="col-label")
                    yield Static("Tokens", classes="col-tokens")
                    yield Static("Cost", classes="col-cost")

                with VerticalScroll(id="session-costs-list-wrap"):
                    # Aggregates by (model, tier)
                    aggregates: Dict[tuple[str, str], float] = {}
                    legacy_carryover_total = 0.0
                    list_items = []

                    for event in events:
                        ts = event.get("timestampMillis", 0)
                        dt = datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M")
                        label = event.get("operationLabel") or "Internal"
                        op_type = event.get("operationType") or "OTHER"
                        model = event.get("modelName", "unknown")
                        tier = event.get("tier", "default")
                        cost = event.get("costUsd", 0.0)

                        in_t = event.get("inputTokens", 0)
                        cache_t = event.get("cachedInputTokens", 0)
                        think_t = event.get("thinkingTokens", 0)
                        out_t = event.get("outputTokens", 0)

                        token_str = f"in:{in_t}"
                        if cache_t:
                            token_str += f" c:{cache_t}"
                        if think_t:
                            token_str += f" t:{think_t}"
                        token_str += f" out:{out_t}"

                        list_items.append(
                            ListItem(
                                Horizontal(
                                    Static(dt, classes="col-ts"),
                                    Static(op_type, classes="col-type"),
                                    Static(f"{model} ({tier})", classes="col-model"),
                                    Static(label, classes="col-label"),
                                    Static(token_str, classes="col-tokens"),
                                    Static(f"[bold green]${cost:.4f}[/]", classes="col-cost"),
                                    classes="session-cost-row",
                                )
                            )
                        )

                        key = (model, tier)
                        if op_type == self.LEGACY_MIGRATION_OPERATION_TYPE:
                            legacy_carryover_total += cost
                        else:
                            aggregates[key] = aggregates.get(key, 0.0) + cost

                    yield ListView(*list_items, id="session-costs-list")

            # Summary Section
            with Vertical(id="session-costs-summary"):
                if events:
                    summary_lines = ["[bold]Aggregates by Model:[/]\n"]
                    if aggregates:
                        for (model, tier), agg_cost in sorted(aggregates.items()):
                            summary_lines.append(f"  {model} ({tier}): ${agg_cost:.4f}")
                    else:
                        summary_lines.append("  No billable model calls recorded.")
                    if legacy_carryover_total > 0:
                        summary_lines.append("")
                        summary_lines.append(
                            f"Legacy carryover (not a model): ${legacy_carryover_total:.4f}"
                        )
                    yield Static("\n".join(summary_lines), id="session-costs-aggregates")

                yield Static(
                    f"[bold]Total Session Cost: ${total_cost:.4f}[/]", id="session-costs-total"
                )

            yield Static("Esc: Close", id="session-costs-help-line")

    def on_mount(self) -> None:
        if self.query(ListView):
            self.query_one(ListView).focus()
        else:
            self.query_one("#session-costs-help-line").focus()

    def on_list_view_highlighted(self, message: ListView.Highlighted) -> None:
        """Ensure the highlighted cost row is scrolled into view."""
        if message.item:
            try:
                scroll_wrap = self.query_one("#session-costs-list-wrap")
                scroll_wrap.scroll_to_widget(message.item, animate=False)
            except NoMatches:
                # Benign if the modal is unmounting or layout hasn't settled.
                pass
            except Exception:
                logger.debug("Failed to scroll cost item into view", exc_info=True)


class PrCreateModalScreen(ModalScreen[Optional[tuple[str, str, List[str]]]]):
    """Modal for creating a pull request with editable title, body, and session selection."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
        Binding("ctrl+enter", "submit_pr", "Create PR", show=False),
    ]

    def __init__(
        self,
        suggested_title: str = "",
        suggested_body: str = "",
        source_branch: str = "",
        target_branch: str = "",
        sessions: Optional[List[Dict[str, Any]]] = None,
        selected_session_ids: Optional[List[str]] = None,
    ) -> None:
        super().__init__()
        self._suggested_title = suggested_title
        self._suggested_body = suggested_body
        self._source_branch = source_branch
        self._target_branch = target_branch
        self._sessions = sessions or []
        self._selected_session_ids = set(selected_session_ids or [])
        # Build ordered list of session IDs to preserve display order in result
        self._session_id_order = [str(s.get("id", "")) for s in self._sessions]

    def compose(self) -> ComposeResult:
        with Vertical(id="pr-create-container"):
            yield Static("Create Pull Request", id="pr-create-title")
            yield Static(
                f"[dim]{self._source_branch} -> {self._target_branch}[/]",
                id="pr-create-branches",
            )
            yield Static("Title:", id="pr-title-label")
            yield Input(value=self._suggested_title, placeholder="PR title", id="pr-title-input")
            yield Static("Description:", id="pr-body-label")
            yield TextArea(text=self._suggested_body, id="pr-body-input")
            if self._sessions:
                yield Static("Sessions (Space/Enter to toggle):", id="pr-sessions-label")
                with VerticalScroll(id="pr-sessions-scroll"):
                    items = []
                    for session in self._sessions:
                        session_id = str(session.get("id", ""))
                        session_name = session.get("name") or session_id[:8]
                        is_selected = session_id in self._selected_session_ids
                        marker = "[x]" if is_selected else "[ ]"
                        item = ListItem(
                            Static(f"{marker} {session_name}", markup=False),
                            id=f"pr-session-{session_id}",
                        )
                        item.session_id = session_id
                        items.append(item)
                    yield ListView(*items, id="pr-sessions-list")
            with Horizontal(id="pr-create-actions"):
                yield Button("Create PR", id="pr-create-submit", variant="primary")
                yield Button("Cancel", id="pr-create-cancel")

    def on_mount(self) -> None:
        self.query_one("#pr-title-input", Input).focus()

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        """Toggle session selection when Enter is pressed on a list item."""
        if event.item and hasattr(event.item, "session_id"):
            self._toggle_session(event.item.session_id)
            event.stop()

    def on_key(self, event) -> None:
        """Handle Space key to toggle selection in the sessions list."""
        if event.key == "space":
            try:
                sessions_list = self.query_one("#pr-sessions-list", ListView)
                if sessions_list.has_focus or (
                    sessions_list.highlighted_child and sessions_list.highlighted_child.has_focus
                ):
                    item = sessions_list.highlighted_child
                    if item and hasattr(item, "session_id"):
                        self._toggle_session(item.session_id)
                        event.stop()
                        event.prevent_default()
            except Exception:
                pass

    def _toggle_session(self, session_id: str) -> None:
        """Toggle selection state for a session."""
        if session_id in self._selected_session_ids:
            self._selected_session_ids.discard(session_id)
            is_selected = False
        else:
            self._selected_session_ids.add(session_id)
            is_selected = True

        # Update the visual state
        try:
            item = self.query_one(f"#pr-session-{session_id}", ListItem)
            session_name = None
            for s in self._sessions:
                if str(s.get("id", "")) == session_id:
                    session_name = s.get("name") or session_id[:8]
                    break
            if session_name:
                marker = "[x]" if is_selected else "[ ]"
                item.query_one(Static).update(f"{marker} {session_name}")
        except Exception:
            pass

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "pr-create-submit":
            self._do_submit()
        elif event.button.id == "pr-create-cancel":
            self.dismiss(None)

    def action_submit_pr(self) -> None:
        self._do_submit()

    def _do_submit(self) -> None:
        title_label = self.query_one("#pr-title-label", Static)
        title_label.update("Title:")
        title = self.query_one("#pr-title-input", Input).value.strip()
        body = self.query_one("#pr-body-input", TextArea).text.strip()
        if not title:
            title_label.update("[bold red]Title is required[/]")
            return
        # Return selected IDs in display order (deterministic)
        selected_ids = [sid for sid in self._session_id_order if sid in self._selected_session_ids]
        self.dismiss((title, body, selected_ids))


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
                with Vertical(id="model-select-list-wrap"):
                    items = []
                    for idx, m in enumerate(self.models):
                        label = f"{'[x]' if m == self.selected_model else '[ ]'} {m}"
                        items.append(ListItem(Static(label, markup=False), id=f"m-{idx}"))
                    yield ListView(*items, id="model-select-list")

            with Vertical(classes="selection-pane"):
                yield Static("Reasoning", id="reasoning-select-title")
                with Vertical(id="reasoning-select-list-wrap"):
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
            # Ensure the initial highlight is visible
            if m_list.highlighted_child:
                m_list.highlighted_child.scroll_visible(animate=False)
        except (ValueError, Exception):
            pass

        # Sync reasoning list highlight
        try:
            r_list = self.query_one("#reasoning-select-list", ListView)
            r_idx = self.reasoning_levels.index(self.selected_reasoning)
            r_list.index = r_idx
            # Ensure the initial highlight is visible
            if r_list.highlighted_child:
                r_list.highlighted_child.scroll_visible(animate=False)
        except (ValueError, Exception):
            pass

        # Focus the model list by default
        self.query_one("#model-select-list", ListView).focus()

    def on_list_view_highlighted(self, message: ListView.Highlighted) -> None:
        """Ensure the highlighted item is always scrolled into view."""
        if message.item:
            message.item.scroll_visible()

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
                # Explicitly scroll the focused/highlighted item into view
                if r_list.highlighted_child:
                    r_list.highlighted_child.scroll_visible(animate=False)

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
        Binding("ctrl+d", "handle_ctrl_c", "Quit", show=False),
        Binding("ctrl+p", "command_palette", "Settings", show=True),
        Binding("ctrl+o", "toggle_output", "Toggle Output", show=True),
        Binding("ctrl+z", "suspend_process", "Suspend", show=False, priority=True),
        Binding("shift+tab", "toggle_mode", "Toggle mode", show=False, priority=True),
    ]

    def action_suspend_process(self) -> None:
        """Suspend the app and executor subprocess with resumable job-control signals."""
        process = self.executor._process
        if process is not None and process.returncode is None:
            # Ensure the Java subprocess pauses even though Ctrl+Z is handled in-app.
            # Textual's parent suspend still controls terminal mode transitions.
            try:
                os.kill(process.pid, signal.SIGTSTP)
            except ProcessLookupError:
                pass
            except Exception:
                logger.debug("Failed to pause executor process before suspend", exc_info=True)

        # Keep Textual's built-in suspend/resume semantics for the app itself.
        try:
            super().action_suspend_process()
        except Exception:
            logger.debug("Failed to suspend process", exc_info=True)

    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = True,
        executor: Optional[ExecutorManager] = None,
        session_id: Optional[str] = None,
        resume_session: bool = False,
        pick_session: bool = False,
        vendor: Optional[str] = None,
    ) -> None:
        super().__init__()
        self.settings = Settings.load()
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
                exit_on_stdin_eof=True,
                brokk_api_key=self.settings.get_brokk_api_key(),
            )
        self.requested_session_id = session_id
        self.resume_session = resume_session
        self.pick_session = pick_session
        self._set_theme(self.settings.theme)
        self.agent_mode = "LUTZ"
        self.show_verbose_output: bool = False

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
        self.session_switch_in_progress = False
        self._current_switch_target_session_id: Optional[str] = None
        self.current_job_id: Optional[str] = None
        self._pending_prompt: Optional[str] = None
        self._pending_switch_prompt: Optional[tuple[str, str]] = None
        self._startup_pending_prompt: Optional[str] = None
        self._pending_updated_at: float = 0
        self._pending_generation: int = 0
        self._pending_min_wait_until: float = 0.0
        self._resubmit_grace_s: float = 0.2
        self._last_ctrl_c_time: float = 0
        self._task_run_cancelled: bool = False
        self._executor_started: bool = False
        self._executor_ready: bool = False
        self._refresh_context_lock = asyncio.Lock()
        self._reported_refresh_errors: set[str] = set()
        self._session_switch_lock = asyncio.Lock()
        self._relaunch_lock = asyncio.Lock()
        self._reasoning_target: str = "planner"
        self.latest_pypi_version: Optional[str] = None

        # Accumulators for LLM usage costs (USD).
        # current_job_cost is per-job and resets at the start of each _run_job.
        self.current_job_cost: float = 0.0
        # session_total_cost is the cumulative cost for the active session.
        self.session_total_cost: float = 0.0
        # The session ID for which session_total_cost was last reconciled/updated.
        self.session_total_cost_id: Optional[str] = None

        self._tasklist_restore_focus_widget: Any | None = None

        # Shutdown coordination flags and lock
        self._shutting_down: bool = False
        self._shutdown_completed: bool = False
        self._shutdown_lock = asyncio.Lock()
        self._exit_transcript: str = ""
        self._exit_transcript_renderables: list[Any] = []

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
        except Exception:
            pass
        for screen in reversed(self.screen_stack):
            try:
                return screen.query_one(ChatPanel)
            except Exception:
                continue
        return None

    def _capture_exit_transcript(self) -> None:
        """Snapshot the transcript before the TUI unmounts."""
        chat = self._maybe_chat()
        if not chat:
            return
        self._exit_transcript = chat.export_plain_text_transcript().strip()
        self._exit_transcript_renderables = chat.export_rich_transcript_renderables()

    def get_exit_transcript(self) -> str:
        """Return the transcript captured during shutdown."""
        return self._exit_transcript

    def get_exit_transcript_renderables(self) -> list[Any]:
        """Return Rich renderables captured during shutdown."""
        return list(self._exit_transcript_renderables)

    def _show_welcome_message(self, refresh: bool = False) -> None:
        """Constructs and displays the branded welcome message in the ChatPanel."""
        chat = self._maybe_chat()
        if not chat:
            return

        msg = build_welcome_message(self.get_slash_commands(), self.latest_pypi_version)
        try:
            if refresh:
                chat.update_welcome(msg)
            else:
                chat.add_welcome(get_braille_icon(), msg)
        except Exception:
            # Safely ignore races during teardown/unmount if the chat log or other
            # expected widgets are gone.
            logger.debug(
                "Failed to show/refresh welcome message (likely unmounting)", exc_info=True
            )

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
                turn_cost=getattr(self, "current_job_cost", None),
                session_cost=getattr(self, "session_total_cost", None),
            )
        except Exception:
            # Swallow all errors when updating UI that's possibly not mounted in tests.
            return

    def _apply_executor_model_configs(self, configs: dict[str, Any]) -> None:
        """Mirror persisted executor model roles into the TUI's local state."""
        architect = configs.get("architect")
        if isinstance(architect, dict):
            model = str(architect.get("model", "")).strip()
            reasoning = str(architect.get("reasoning", "")).strip()
            if model:
                self.current_model = model
            if reasoning:
                self.reasoning_level = reasoning

        code = configs.get("code")
        if isinstance(code, dict):
            model = str(code.get("model", "")).strip()
            reasoning = str(code.get("reasoning", "")).strip()
            if model:
                self.code_model = model
            if reasoning:
                self.reasoning_level_code = reasoning

    async def _sync_model_roles_from_executor(self) -> None:
        """Load persisted CODE/ARCHITECT roles so TUI selectors match Swing semantics."""
        if not self._executor_ready:
            return

        try:
            configs = await self.executor.get_model_config()
            self._apply_executor_model_configs(configs)
            self.settings.last_model = self.current_model
            self.settings.last_reasoning_level = self.reasoning_level
            self.settings.last_code_model = self.code_model
            self.settings.last_code_reasoning_level = self.reasoning_level_code
            self.settings.save()
            self._update_statusline()
        except Exception:
            logger.exception("Failed to sync model roles from executor")

    async def _persist_model_role(
        self, role: str, model_id: str, reasoning: str, label: str
    ) -> None:
        chat = self._maybe_chat()
        if role == "CODE":
            self.code_model = model_id
            self.reasoning_level_code = reasoning
            self.settings.last_code_model = model_id
            self.settings.last_code_reasoning_level = reasoning
        else:
            self.current_model = model_id
            self.reasoning_level = reasoning
            self.settings.last_model = model_id
            self.settings.last_reasoning_level = reasoning

        try:
            self.settings.save()
            self._update_statusline()

            setter = getattr(self.executor, "set_model_config", None)
            if setter is not None:
                result = setter(role=role, model=model_id, reasoning=reasoning)
                if hasattr(result, "__await__"):
                    await result

            if chat:
                chat.add_system_message_markup(
                    f"{label}: [bold]{model_id}[/] (Reasoning: [bold]{reasoning}[/])"
                )
        except Exception as e:
            logger.exception("Failed to persist %s model role", role)
            if chat:
                chat.add_system_message(f"Failed to update {label.lower()}: {e}", level="ERROR")

    def compose(self) -> ComposeResult:
        with Horizontal():
            yield ChatPanel(id="chat-main")
            yield TaskListPanel(id="side-tasklist")

    async def on_mount(self) -> None:
        self.app_resume_signal.subscribe(self, self._on_app_resume)
        chat = self._maybe_chat()
        logger.info("Using workspace directory: %s", self.executor.workspace_dir)
        if chat:
            chat.show_verbose = self.show_verbose_output
            chat.set_token_bar_visible(True)

            # Load initial prompt history for arrow-key navigation
            history = load_history(self.executor.workspace_dir)
            chat.set_history(history)

            self._show_welcome_message()

        # Check for updates in background
        self.run_worker(self._check_for_updates())

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

    def _on_app_resume(self, _app: App) -> None:
        """Ensure the executor process resumes if it was paused by suspend."""
        process = self.executor._process
        if process is None or process.returncode is not None:
            return
        try:
            os.kill(process.pid, signal.SIGCONT)
        except ProcessLookupError:
            return
        except Exception:
            logger.debug("Failed to resume executor process after app resume", exc_info=True)

    async def _start_executor(self) -> None:
        chat = self._maybe_chat()
        if chat:
            chat.set_job_running(True)
        try:
            from brokk_code.session_persistence import (
                get_session_zip_resume_path,
                load_last_session_id,
                save_last_session_id,
            )

            await self.executor.start()
            # Mark as started only after successful launch so monitor begins checks
            self._executor_started = True

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
                zip_path = get_session_zip_resume_path(
                    self.executor.workspace_dir, session_to_resume
                )
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
                await self._sync_model_roles_from_executor()
                # Initial context load
                self.run_worker(self._refresh_context_panel())

                if self.pick_session:
                    self.pick_session = False
                    self.run_worker(self._show_sessions())

                if resumed:
                    try:
                        conversation_data = await self.executor.get_conversation()
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
        """Background worker to check if the executor dies unexpectedly."""
        while True:
            if not self._executor_started:
                await asyncio.sleep(0.5)
                continue

            await asyncio.sleep(2.0)
            # Re-check started flag in case of rapid stop during sleep
            if not self._executor_started:
                continue

            if not self.executor.check_alive():
                if self._shutting_down or self._shutdown_completed:
                    logger.debug("Executor process exited during shutdown.")
                    break
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

                # Seed or update session cost from executor's cumulative total.
                # Only reconcile when no job is running. While a job is active, cost
                # is updated exclusively via COST notification events in _handle_event.
                # Reconciling during a job risks double-counting: the executor's
                # totalCost may already include events the TUI hasn't yet received via
                # SSE, so bumping session_total_cost with max() and then processing
                # those same events again inflates the displayed cost.
                remote_total = context_data.get("totalCost")
                current_sid = self.executor.session_id
                if isinstance(remote_total, (int, float)) and not self.job_in_progress:
                    remote_val = round(float(remote_total), 6)
                    if current_sid != self.session_total_cost_id:
                        # Session switch detected: overwrite without monotonic guard
                        # so that cost can legitimately decrease to the new session's level.
                        self.session_total_cost = remote_val
                        self.session_total_cost_id = current_sid
                    else:
                        # Same session: use max() to guard against out-of-order events.
                        self.session_total_cost = max(self.session_total_cost, remote_val)

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
                    chat.set_token_usage(
                        used, max_tokens, fragments, session_cost=self.session_total_cost
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
        """Returns a mutable copy of the current tasklist data suitable for updates.

        Callers may freely modify the returned dict without affecting previously
        persisted snapshots or the panel's internal state.
        """
        from copy import deepcopy

        panel: Optional[TaskListPanel]
        try:
            panel = self._active_tasklist_panel()
        except Exception:
            panel = None

        if panel is not None:
            data = panel.tasklist_data_for_update()
            if data is not None:
                return data

        data = await self.executor.get_tasklist()
        self._update_tasklist_details_all(data)

        if panel is not None:
            updated = panel.tasklist_data_for_update()
            if updated is not None:
                return updated

        # Return a deep copy so callers can mutate without affecting the executor's
        # internal state or previously persisted snapshots.
        return deepcopy(data)

    async def _persist_tasklist(self, data: Dict[str, Any]) -> Dict[str, Any]:
        saved = await self.executor.set_tasklist(data)
        self._update_tasklist_details_all(saved)
        return saved

    async def _toggle_selected_task(self) -> None:
        chat = self.query_one(ChatPanel)
        if self.job_in_progress:
            chat.add_system_message(
                "Cannot modify tasks while a job is in progress.", level="WARNING"
            )
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
        chat = self.query_one(ChatPanel)
        if self.job_in_progress:
            chat.add_system_message(
                "Cannot modify tasks while a job is in progress.", level="WARNING"
            )
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
        chat = self.query_one(ChatPanel)
        if self.job_in_progress:
            chat.add_system_message(
                "Cannot modify tasks while a job is in progress.", level="WARNING"
            )
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
        chat = self.query_one(ChatPanel)
        if self.job_in_progress:
            chat.add_system_message(
                "Cannot modify tasks while a job is in progress.", level="WARNING"
            )
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

    async def _execute_job_lifecycle(
        self, task_input: str, mode: str, manage_job_state: bool = True
    ) -> _JobLifecycleResult:
        """Performs the full job lifecycle: prepare, submit, stream, and finalize.

        If manage_job_state is True, this method handles setting/clearing job_in_progress
        and notifying the chat panel of job start/end. Callers performing sequential
        jobs (like Run All) should manage this state themselves and pass False.
        """
        chat = self._maybe_chat()
        self.current_job_cost = 0.0
        self._task_run_cancelled = False

        if manage_job_state:
            self.job_in_progress = True
            if chat:
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
                mode=mode,
                auto_commit=self.auto_commit,
            )
            async for event in self.executor.stream_events(self.current_job_id):
                self._handle_event(event)

            return (
                _JobLifecycleResult.CANCELLED
                if self._task_run_cancelled
                else _JobLifecycleResult.SUCCEEDED
            )

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

            if not self._task_run_cancelled:
                if chat:
                    err_type = type(e).__name__
                    chat.add_system_message(
                        f"Job failed or interrupted ({err_type}): {e}",
                        level="ERROR",
                    )
                else:
                    logger.error("Job failed or interrupted (%s): %s", type(e).__name__, e)
                return _JobLifecycleResult.FAILED
            return _JobLifecycleResult.CANCELLED
        finally:
            if manage_job_state:
                if chat:
                    chat.set_response_finished()
                    chat.set_job_running(False)
                self.job_in_progress = False
            self.current_job_id = None

    async def _run_selected_task(self, task: Dict[str, Any]) -> None:
        """Runs a single task and marks it done on success."""
        chat = self._maybe_chat()
        task_id = str(task.get("id", "")).strip()
        task_text = str(task.get("text", "")).strip()
        task_title = str(task.get("title", "")).strip()

        # Prefer text, fall back to title
        task_input = task_text if task_text else task_title
        if not task_input:
            if chat:
                chat.add_system_message("Task has no text or title to run.", level="ERROR")
            return

        display_name = task_title if task_title else task_input[:50]
        if chat:
            chat.add_system_message(f"Running task: {display_name}")

        result = await self._execute_job_lifecycle(task_input, mode="CODE", manage_job_state=False)

        if result == _JobLifecycleResult.SUCCEEDED and task_id:
            try:
                data = await self._ensure_tasklist_data()
                if data:
                    found = False
                    for t in data.get("tasks", []):
                        if str(t.get("id", "")).strip() == task_id:
                            t["done"] = True
                            found = True
                            break
                    if found:
                        await self._persist_tasklist(data)
                        if chat:
                            chat.add_system_message(
                                f"Task '{display_name}' completed and marked as done."
                            )
                    elif chat:
                        chat.add_system_message(
                            f"Task '{display_name}' disappeared and could not be marked done.",
                            level="WARNING",
                        )
            except Exception as e:
                if chat:
                    chat.add_system_message(f"Failed to mark task as done: {e}", level="WARNING")
        elif result == _JobLifecycleResult.CANCELLED and chat:
            chat.add_system_message(f"Task '{display_name}' was cancelled.")

    def on_guided_review_panel_enqueue_requested(
        self, message: GuidedReviewPanel.EnqueueRequested
    ) -> None:
        self.run_worker(self._enqueue_review_task(message.title, message.text))

    async def _enqueue_review_task(self, title: str, text: str) -> None:
        chat = self._maybe_chat()
        try:
            tasklist = await self.executor.get_tasklist()
            tasks = tasklist.get("tasks", [])
            tasks.append({"id": str(uuid.uuid4()), "title": title, "text": text, "done": False})
            tasklist["tasks"] = tasks
            await self.executor.set_tasklist(tasklist)
            if chat:
                chat.add_system_message(f"Task added: {title}", level="SUCCESS")
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to add task: {e}", level="ERROR")

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

        # Gate ALL input during session operations
        if self.session_switch_in_progress:
            chat = self._maybe_chat()
            if check_text.startswith("/"):
                # Reject slash commands during session switch
                if chat:
                    chat.add_system_message(
                        "Session operation in progress. Please wait.", level="WARNING"
                    )
                return
            elif check_text:
                if self._current_switch_target_session_id:
                    # Queue non-slash prompts for execution after switch completes
                    if chat:
                        chat.add_history_entry(raw_text)
                        chat.add_user_message(raw_text)
                    self._pending_switch_prompt = (self._current_switch_target_session_id, raw_text)
                    if chat:
                        chat.add_system_message(
                            "Queuing prompt until session switch is complete..."
                        )
                else:
                    # No target session (e.g. creating new session) — reject
                    if chat:
                        chat.add_system_message(
                            "Session operation in progress. Please wait.", level="WARNING"
                        )
                return

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
            if self.job_in_progress and self.current_job_id:
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

    _VALID_SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW"}

    def _handle_review_command(self, parts: List[str], *, from_alias: bool = False) -> None:
        """Handle the /pr review slash command.

        Supported syntaxes:
          /pr review <pr_number> [--severity LEVEL]
          /pr review <owner> <repo> <pr_number> [--severity LEVEL]

        LEVEL is one of CRITICAL, HIGH, MEDIUM, LOW (default: HIGH).

        When from_alias=True, the command was invoked via the deprecated /review alias.
        """
        chat = self._maybe_chat()
        if not chat:
            return

        if from_alias:
            chat.add_system_message(
                "/review is deprecated; use /pr review instead.",
                level="WARNING",
            )

        if not self._executor_ready:
            chat.add_system_message(
                "Executor is not ready. Cannot submit PR review.",
                level="ERROR",
            )
            return

        # Extract --severity flag before positional parsing
        # When called from /pr review, parts[0] is "review" and args start at parts[1]
        # When called from /review alias, parts[0] is "/review" and args start at parts[1]
        raw_args = parts[1:] if len(parts) > 1 else []
        severity_threshold: Optional[str] = None
        positional: List[str] = []
        i = 0
        while i < len(raw_args):
            if raw_args[i] == "--severity" and i + 1 < len(raw_args):
                val = raw_args[i + 1].upper()
                if val not in self._VALID_SEVERITIES:
                    chat.add_system_message(
                        f"Invalid severity: {raw_args[i + 1]}. "
                        f"Must be one of: {', '.join(sorted(self._VALID_SEVERITIES))}",
                        level="ERROR",
                    )
                    return
                severity_threshold = val
                i += 2
            else:
                positional.append(raw_args[i])
                i += 1

        args = positional

        if len(args) == 0:
            chat.add_system_message(
                "Usage: /pr review <pr_number> [--severity LEVEL] "
                "or /pr review <owner> <repo> <pr_number> [--severity LEVEL]",
                level="WARNING",
            )
            return

        pr_number: Optional[int] = None
        owner: Optional[str] = None
        repo: Optional[str] = None

        if len(args) == 1:
            # /pr review <pr_number> - infer owner/repo
            try:
                pr_number = int(args[0])
            except ValueError:
                chat.add_system_message(
                    f"Invalid PR number: {args[0]}. Expected an integer.",
                    level="ERROR",
                )
                return
            owner, repo = infer_github_repo_from_remote(self.executor.workspace_dir)
            if not owner or not repo:
                chat.add_system_message(
                    "Could not infer GitHub owner/repo from git remote. "
                    "Use: /pr review <owner> <repo> <pr_number>",
                    level="ERROR",
                )
                return
        elif len(args) == 3:
            # /pr review <owner> <repo> <pr_number>
            owner = args[0]
            repo = args[1]
            try:
                pr_number = int(args[2])
            except ValueError:
                chat.add_system_message(
                    f"Invalid PR number: {args[2]}. Expected an integer.",
                    level="ERROR",
                )
                return
        else:
            chat.add_system_message(
                "Usage: /pr review <pr_number> [--severity LEVEL] "
                "or /pr review <owner> <repo> <pr_number> [--severity LEVEL]",
                level="WARNING",
            )
            return

        # Get GitHub token from brokk.properties or environment
        github_token = self.settings.get_github_token()
        if not github_token:
            chat.add_system_message(
                "GitHub token not found. Set githubToken in brokk.properties "
                "or the GITHUB_TOKEN environment variable.",
                level="ERROR",
            )
            return

        severity_msg = f" (severity>={severity_threshold})" if severity_threshold else ""
        chat.add_system_message(
            f"Submitting PR review for {owner}/{repo}#{pr_number}{severity_msg}..."
        )
        self.run_worker(
            self._run_pr_review_job(
                pr_number=pr_number,
                github_token=github_token,
                repo_owner=owner,
                repo_name=repo,
                severity_threshold=severity_threshold,
            )
        )

    def _handle_local_review_command(self, parts: List[str]) -> None:
        """Handle the /review slash command for local guided reviews.

        Supported syntax:
          /review [--severity LEVEL]

        LEVEL is one of CRITICAL, HIGH, MEDIUM, LOW (default: LOW -- show everything).

        Reviews all branch changes vs the merge-base with the default branch,
        including uncommitted working tree changes.
        """
        chat = self._maybe_chat()
        if not chat:
            return

        if not self._executor_ready:
            chat.add_system_message(
                "Executor is not ready. Cannot start review.",
                level="ERROR",
            )
            return

        # Parse optional --severity flag
        raw_args = parts[1:] if len(parts) > 1 else []
        severity: Optional[str] = None
        i = 0
        while i < len(raw_args):
            if raw_args[i] == "--severity" and i + 1 < len(raw_args):
                val = raw_args[i + 1].upper()
                if val not in self._VALID_SEVERITIES:
                    chat.add_system_message(
                        f"Invalid severity: {raw_args[i + 1]}. "
                        f"Must be one of: {', '.join(sorted(self._VALID_SEVERITIES))}",
                        level="ERROR",
                    )
                    return
                severity = val
                i += 2
            else:
                chat.add_system_message(
                    "Usage: /review [--severity LEVEL]",
                    level="WARNING",
                )
                return

        severity_msg = f" (severity>={severity})" if severity else ""
        chat.add_system_message(f"Starting guided review{severity_msg}...")
        self._open_review_modal()
        self.run_worker(self._run_review(severity_threshold=severity))

    def _open_review_modal(self) -> None:
        """Opens the review modal screen."""
        if isinstance(self.screen, ReviewModalScreen):
            return

        def on_close() -> None:
            if self.job_in_progress and self.current_job_id:
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message("Cancelling review job...")
                self.run_worker(self.executor.cancel_job(self.current_job_id))

        self.push_screen(ReviewModalScreen(on_close=on_close))

    def _close_review_modal(self) -> None:
        """Closes the review modal if it is currently active."""
        try:
            current_screen = self.screen
        except ScreenStackError:
            return
        if isinstance(current_screen, ReviewModalScreen):
            current_screen.dismiss(None)

    def _get_review_panel(self) -> Optional[GuidedReviewPanel]:
        """Returns the GuidedReviewPanel from the current modal screen, or None."""
        try:
            if isinstance(self.screen, ReviewModalScreen):
                return self.screen.query_one(GuidedReviewPanel)
        except Exception:
            pass
        return None

    async def _run_review(self, severity_threshold: Optional[str] = None) -> None:
        """Run a guided review job and display results in the review panel."""
        from brokk_code.review_models import parse_guided_review

        chat = self._maybe_chat()

        self.current_job_cost = 0.0
        self.job_in_progress = True
        if chat:
            chat.set_job_running(True)

        # Defer panel lookup until after the first await so the modal is mounted
        panel = self._get_review_panel()
        if panel:
            panel.set_loading(True)

        job_failed = False
        review_loaded = False

        try:
            self.current_job_id = await self.executor.submit_review_job(
                planner_model=self.current_model,
                severity_threshold=severity_threshold,
            )

            # Re-fetch panel after the first await; modal is now guaranteed mounted
            panel = self._get_review_panel()
            if panel:
                panel.set_loading(True)

            async for event in self.executor.stream_events(self.current_job_id):
                if not isinstance(event, dict):
                    continue

                event_type = event.get("type")
                data = safe_data(event)

                # The backend emits REVIEW_COMPLETE with the full review data
                if event_type == "REVIEW_COMPLETE":
                    try:
                        guided_review = parse_guided_review(data)
                        review_loaded = True
                        panel = self._get_review_panel()
                        if panel:
                            panel.set_loading(False)
                            panel.update_review(guided_review)
                        if chat:
                            chat.add_system_message(
                                "Guided review completed successfully.", level="SUCCESS"
                            )
                    except Exception as e:
                        logger.exception("Failed to process review data")
                        if chat:
                            chat.add_system_message(f"Failed to process review: {e}", level="ERROR")
                        panel = self._get_review_panel()
                        if panel:
                            panel.set_loading(False)
                    continue

                if event_type == "STATE_CHANGE":
                    state = data.get("state")
                    if state and is_failure_state(state):
                        job_failed = True
                    continue

                if event_type == "ERROR":
                    job_failed = True

                # Handle other events (notifications, costs, etc.)
                self._handle_event(event)

            # Post-loop: handle failure or missing review
            if not review_loaded:
                panel = self._get_review_panel()
                if job_failed:
                    if panel:
                        panel.set_loading(False)
                    if chat:
                        chat.add_system_message("Review job failed.", level="ERROR")
                else:
                    if panel:
                        panel.set_loading(False)

        except Exception as e:
            logger.exception("Review job failed")
            job_failed = True
            if chat:
                err_type = type(e).__name__
                chat.add_system_message(
                    f"Review job failed ({err_type}): {e}",
                    level="ERROR",
                )
            panel = self._get_review_panel()
            if panel:
                panel.set_loading(False)
        finally:
            if chat:
                chat.set_job_running(False)
            self.job_in_progress = False
            self.current_job_id = None

    async def _run_pr_review_job(
        self,
        pr_number: int,
        github_token: str,
        repo_owner: str,
        repo_name: str,
        severity_threshold: Optional[str] = None,
    ) -> None:
        """Run a PR review job and stream events to the chat."""
        self.current_job_cost = 0.0
        self.job_in_progress = True
        chat = self._maybe_chat()
        if chat:
            chat.set_job_running(True)
            chat.set_response_pending()

        job_failed = False
        saw_completed = False
        try:
            self.current_job_id = await self.executor.submit_pr_review_job(
                planner_model=self.current_model,
                github_token=github_token,
                owner=repo_owner,
                repo=repo_name,
                pr_number=pr_number,
                severity_threshold=severity_threshold,
            )
            async for event in self.executor.stream_events(self.current_job_id):
                if not isinstance(event, dict):
                    continue
                event_type = event.get("type")
                # Skip raw LLM token stream — it contains the review JSON,
                # not user-facing content
                if event_type in ("LLM_TOKEN", "TOKEN"):
                    continue
                if event_type == "STATE_CHANGE":
                    data = safe_data(event)
                    state = data.get("state")
                    if state == "COMPLETED":
                        saw_completed = True
                    elif state and is_failure_state(state):
                        job_failed = True
                    continue
                if event_type == "ERROR":
                    job_failed = True
                self._handle_event(event)
        except Exception as e:
            logger.exception("PR review job failed")
            job_failed = True
            if chat:
                err_type = type(e).__name__
                chat.add_system_message(
                    f"PR review job failed ({err_type}): {e}",
                    level="ERROR",
                )
        finally:
            if chat:
                if saw_completed and not job_failed:
                    pr_url = f"https://github.com/{repo_owner}/{repo_name}/pull/{pr_number}"
                    chat.add_system_message_markup(
                        f"PR review posted: [link={pr_url}]{pr_url}[/link]",
                        level="SUCCESS",
                    )
                chat.set_response_finished()
                chat.set_job_running(False)
            self.job_in_progress = False
            self.current_job_id = None

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

    async def _run_job(self, task_input: str) -> None:
        await self._execute_job_lifecycle(task_input, mode=self.current_mode)

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
            # We keep job_in_progress = True during this transition by calling _run_job.
            if next_prompt:
                await self._run_job(next_prompt)

    def _handle_event(self, event: Dict[str, Any]) -> None:
        if not isinstance(event, dict):
            logger.warning("Ignoring non-dict event: %s", type(event).__name__)
            return
        event_type = event.get("type")
        data = safe_data(event)
        chat = self._maybe_chat()

        if event_type == "LLM_TOKEN":
            if chat:
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
                # Use rounding to avoid floating point precision artifacts
                # LLM costs often go to 4+ decimal places.
                self.current_job_cost = round(self.current_job_cost + increment, 6)
                self.session_total_cost = round(self.session_total_cost + increment, 6)
                self._update_statusline()

            if chat and not is_cost and not is_confirm:
                chat.add_system_message(msg, level=level)
        elif event_type == "ERROR":
            msg = data.get("message", "") or "Unknown error"
            if chat:
                chat.add_system_message(msg, level="ERROR")
            # Note: set_job_running(False) happens in _run_job finally block
        elif event_type == "COMMAND_RESULT":
            if chat:
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
                self.run_worker(self._refresh_context_panel())

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
            {"command": "/costs", "description": "Show session cost breakdown"},
            {"command": "/login-openai", "description": "Connect your OpenAI ChatGPT subscription"},
            {"command": "/clear", "description": "Clear the chat transcript"},
            {"command": "/context", "description": "Toggle and focus context panel"},
            {"command": "/model", "description": "Change the planner LLM model"},
            {"command": "/model-code", "description": "Change the code LLM model"},
            {"command": "/autocommit", "description": "Toggle auto-commit for submitted jobs"},
            {"command": "/settings", "description": "Open settings"},
            {"command": "/task", "description": "Open/close the task list"},
            {"command": "/sessions", "description": "List and switch between sessions"},
            {"command": "/commit", "description": "Commit current changes"},
            {"command": "/dependencies", "description": "Manage project dependencies"},
            {"command": "/pr", "description": "Create a pull request"},
            {
                "command": "/pr review",
                "description": "Submit a PR review job (supports --severity LEVEL)",
            },
            {"command": "/review", "description": "Generate guided review of changes"},
            {"command": "/info", "description": "Show current configuration and status"},
            {"command": "/quit", "description": "Exit the application"},
            {"command": "/exit", "description": "Exit the application"},
        ]

    def _handle_command(self, cmd: str) -> None:
        chat = self.query_one(ChatPanel)
        parts = cmd.split()
        base = parts[0].lower()

        if base == "/model":
            if len(parts) > 1:
                self.run_worker(
                    self._persist_model_role("ARCHITECT", parts[1], self.reasoning_level, "Model")
                )
            else:
                self.run_worker(self.action_select_model_and_reasoning())
        elif base == "/model-code":
            if len(parts) > 1:
                self.run_worker(
                    self._persist_model_role(
                        "CODE", parts[1], self.reasoning_level_code, "Code Model"
                    )
                )
            else:
                self.run_worker(self.action_select_code_model_and_reasoning())
        elif base == "/autocommit":
            usage = "Usage: /autocommit [on|off]"
            if len(parts) == 1:
                new_value = not self.auto_commit
            elif len(parts) == 2:
                arg = parts[1].strip().lower()
                truthy = {"on", "true", "1", "yes"}
                falsy = {"off", "false", "0", "no"}
                if arg in truthy:
                    new_value = True
                elif arg in falsy:
                    new_value = False
                else:
                    chat.add_system_message(usage, level="ERROR")
                    return
            else:
                chat.add_system_message(usage, level="ERROR")
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
                    "Auto-commit mode: [bold]OFF[/] (changes will not be committed automatically)"
                )
        elif base == "/settings":
            if len(parts) > 1:
                chat.add_system_message("Settings opens from /settings with no arguments.")
            self.action_command_palette()
        elif base == "/info":
            self._render_info()
        elif base == "/clear":
            chat.clear_transcript()
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
                    chat.add_system_message("API key updated. Relaunching executor...")
                    self.run_worker(self._relaunch_executor())
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
        elif base == "/dependencies":
            self.action_toggle_dependencies()
        elif base == "/commit":
            if not self._executor_ready:
                chat.add_system_message(
                    "Executor is not ready. Cannot commit changes.",
                    level="ERROR",
                )
                return
            # Extract optional message from remaining args
            commit_message: Optional[str] = None
            if len(parts) > 1:
                commit_message = " ".join(parts[1:])
            self.run_worker(self._commit_changes(commit_message))
        elif base == "/sessions":
            self.run_worker(self._show_sessions())
        elif base == "/costs":
            if not self._executor_ready:
                chat.add_system_message(
                    "Executor is not ready. Cannot fetch costs.", level="WARNING"
                )
                return
            self.run_worker(self._show_costs())
        elif base == "/pr":
            # /pr supports subcommands: /pr review ... or /pr [base-branch] for create
            if len(parts) > 1 and parts[1].lower() == "review":
                # /pr review <args...> - route to review handler
                # Build parts list as ["review", ...remaining args...]
                review_parts = parts[1:]  # ["review", ...]
                self._handle_review_command(review_parts)
            else:
                # /pr [base-branch] - PR creation flow
                if not self._executor_ready:
                    chat.add_system_message(
                        "Executor is not ready. Cannot create pull request.",
                        level="ERROR",
                    )
                    return
                base_branch: Optional[str] = None
                if len(parts) > 1:
                    base_branch = parts[1]
                self.run_worker(self._create_pull_request(base_branch))
        elif base == "/review":
            self._handle_local_review_command(parts)
        elif base in ("/quit", "/exit"):
            self.action_quit()
        else:
            chat.append_message("System", f"Unknown command: {base}.")

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
                    self.run_worker(
                        self._persist_model_role(
                            "ARCHITECT", model_id, self.reasoning_level, "Model"
                        )
                    )

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
                        self.run_worker(
                            self._persist_model_role("CODE", model_id, reasoning, "Code Model")
                        )
                    else:
                        self.run_worker(
                            self._persist_model_role("ARCHITECT", model_id, reasoning, "Model")
                        )

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

    def action_toggle_dependencies(self) -> None:
        if isinstance(self.screen, DependenciesModalScreen):
            self.screen.dismiss(None)
            return

        def on_close() -> None:
            pass

        self.push_screen(DependenciesModalScreen(on_close=on_close))

    async def _refresh_dependencies_panel(self) -> None:
        """Fetches latest dependencies and updates the panel."""
        if not self._executor_ready:
            return
        try:
            data = await self.executor.get_dependencies()
            deps_list = data.get("dependencies", [])
            if isinstance(self.screen, DependenciesModalScreen):
                panel = self.screen.query_one(DependenciesPanel)
                panel.refresh_dependencies(deps_list)
        except Exception as e:
            chat = self._maybe_chat()
            if chat:
                chat.add_system_message(f"Failed to fetch dependencies: {e}", level="ERROR")

    def on_dependencies_panel_action_requested(
        self, message: DependenciesPanel.ActionRequested
    ) -> None:
        self.run_worker(self._execute_dependencies_action(message))

    async def _execute_dependencies_action(
        self, message: DependenciesPanel.ActionRequested
    ) -> None:
        if not self._executor_ready:
            return
        chat = self._maybe_chat()

        try:
            if message.action == "toggle_live":
                data = await self.executor.get_dependencies()
                deps = data.get("dependencies", [])
                current_live = [d["name"] for d in deps if d.get("isLive")]
                if message.dependency_name in current_live:
                    current_live.remove(message.dependency_name)
                else:
                    current_live.append(message.dependency_name)
                await self.executor.update_live_dependencies(current_live)
                if chat:
                    chat.add_system_message("Updated live dependencies")

            elif message.action == "update":
                if chat:
                    chat.add_system_message(f"Updating {message.dependency_name}...")
                result = await self.executor.update_dependency(message.dependency_name)
                changed = result.get("changedFiles", 0)
                if chat:
                    chat.add_system_message(
                        f"Updated {message.dependency_name}: {changed} files changed"
                    )

            elif message.action == "delete":
                await self.executor.delete_dependency(message.dependency_name)
                if chat:
                    chat.add_system_message(f"Deleted dependency: {message.dependency_name}")

            elif message.action == "refresh":
                pass

            await self._refresh_dependencies_panel()
        except Exception as e:
            if chat:
                chat.add_system_message(f"Dependency action failed: {e}", level="ERROR")

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

    def _dismiss_tasklist_modal(self) -> None:
        """Dismiss the task list modal if it is currently active."""
        try:
            current_screen = self.screen
        except ScreenStackError:
            return
        if isinstance(current_screen, TaskListModalScreen):
            self._restore_tasklist_focus()
            current_screen.dismiss(None)

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

    def action_task_run(self) -> None:
        """Runs the currently selected task via the executor."""
        chat = self._maybe_chat()
        panel = self._active_tasklist_panel()
        selected = panel.selected_task()
        if not selected:
            if chat:
                chat.add_system_message("No task selected.")
            return

        if not self._executor_ready:
            if chat:
                chat.add_system_message("Executor is not ready. Cannot run task.", level="ERROR")
            return

        if self.job_in_progress:
            if chat:
                chat.add_system_message(
                    "A job is already in progress. Please wait or cancel it first.",
                    level="WARNING",
                )
            return

        if selected.get("done", False):
            if chat:
                chat.add_system_message("Selected task is already done.", level="INFO")
            return

        self._dismiss_tasklist_modal()
        self.run_worker(self._run_selected_task(selected))

    def action_task_run_all(self) -> None:
        """Runs all incomplete tasks in order via the executor."""
        chat = self._maybe_chat()

        if not self._executor_ready:
            if chat:
                chat.add_system_message("Executor is not ready. Cannot run tasks.", level="ERROR")
            return

        if self.job_in_progress:
            if chat:
                chat.add_system_message(
                    "A job is already in progress. Please wait or cancel it first.",
                    level="WARNING",
                )
            return

        self._dismiss_tasklist_modal()
        self.run_worker(self._run_all_incomplete_tasks())

    async def _run_all_incomplete_tasks(self) -> None:
        """Runs all incomplete tasks sequentially, marking each done on success."""
        chat = self._maybe_chat()
        self.job_in_progress = True
        if chat:
            chat.set_job_running(True)

        try:
            # Reset cancellation flag at the start of a run-all sequence
            self._task_run_cancelled = False

            # Fetch current task list
            try:
                data = await self._ensure_tasklist_data()
            except Exception as e:
                if chat:
                    chat.add_system_message(f"Failed to fetch task list: {e}", level="ERROR")
                return

            if not data:
                if chat:
                    chat.add_system_message("No task list active.")
                return

            tasks = data.get("tasks", [])
            incomplete_tasks = [t for t in tasks if not t.get("done", False)]

            if not incomplete_tasks:
                if chat:
                    chat.add_system_message("No incomplete tasks to run.")
                return

            if chat:
                chat.add_system_message(f"Running {len(incomplete_tasks)} incomplete task(s)...")

            for i, task in enumerate(incomplete_tasks):
                # Check for cancellation before starting each task
                if self._task_run_cancelled:
                    remaining = len(incomplete_tasks) - i
                    if chat:
                        chat.add_system_message(
                            f"Task run cancelled. {remaining} task(s) not executed.",
                            level="WARNING",
                        )
                    break

                task_id = str(task.get("id", "")).strip()
                task_text = str(task.get("text", "")).strip()
                task_title = str(task.get("title", "")).strip()

                # Prefer text, fall back to title
                task_input = task_text if task_text else task_title
                if not task_input:
                    if chat:
                        chat.add_system_message(
                            f"Task {i + 1} has no text or title, skipping.", level="WARNING"
                        )
                    continue

                display_name = task_title if task_title else task_input[:50]
                if chat:
                    chat.add_system_message(
                        f"Running task {i + 1}/{len(incomplete_tasks)}: {display_name}"
                    )

                # Use manage_job_state=False so the sequence-level busy state is preserved
                result = await self._execute_job_lifecycle(
                    task_input, mode="CODE", manage_job_state=False
                )

                # Mark task as done only if execution succeeded
                if result == _JobLifecycleResult.SUCCEEDED and task_id:
                    try:
                        # Re-fetch to get latest state
                        current_data = await self._ensure_tasklist_data()
                        if current_data:
                            found = False
                            for t in current_data.get("tasks", []):
                                if str(t.get("id", "")).strip() == task_id:
                                    t["done"] = True
                                    found = True
                                    break
                            if found:
                                await self._persist_tasklist(current_data)
                                if chat:
                                    chat.add_system_message(
                                        f"Task '{display_name}' marked as done."
                                    )
                            elif chat:
                                chat.add_system_message(
                                    f"Task '{display_name}' disappeared; could not be marked done.",
                                    level="WARNING",
                                )
                    except Exception as e:
                        if chat:
                            chat.add_system_message(
                                f"Failed to mark task as done: {e}", level="WARNING"
                            )

                # Abort on failure or cancellation
                if result != _JobLifecycleResult.SUCCEEDED:
                    remaining = len(incomplete_tasks) - i - 1
                    if result == _JobLifecycleResult.CANCELLED:
                        if remaining > 0 and chat:
                            chat.add_system_message(
                                f"Task run cancelled. {remaining} remaining task(s) not executed.",
                                level="WARNING",
                            )
                    else:  # FAILED
                        if remaining > 0 and chat:
                            chat.add_system_message(
                                f"Aborting remaining tasks. {remaining} task(s) not executed.",
                                level="WARNING",
                            )
                    return

            if chat:
                chat.add_system_message(
                    f"Completed all {len(incomplete_tasks)} task(s) successfully."
                )
        finally:
            self.job_in_progress = False
            if chat:
                chat.set_job_running(False)

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
            self._task_run_cancelled = True  # Signal to stop run-all sequence
            if chat_panel:
                chat_panel.add_system_message("Cancelling job...")
            await self.executor.cancel_job(self.current_job_id)
            # Reset double-tap timer so they don't accidentally quit while cancelling
            self._last_ctrl_c_time = now
            return

        if now - self._last_ctrl_c_time < 2.0:
            await self.action_quit()
        else:
            # We don't have easy access to which key triggered the action here without
            # changing the signature, but "Ctrl+C or Ctrl+D" is accurate for both.
            self.query_one(ChatPanel).add_system_message("Press Ctrl+C or Ctrl+D again to quit.")
            self._last_ctrl_c_time = now

    async def _shutdown_once(self, *, show_message: bool = True) -> None:
        """Perform shutdown actions once completed. Concurrency-safe with retry on stop failure."""
        # Fast path
        async with self._shutdown_lock:
            if self._shutdown_completed or self._shutting_down:
                return
            self._shutting_down = True
            self._capture_exit_transcript()

            # Notify user once
            if show_message:
                msg = "Shutting down..."
                chat = self._maybe_chat()
                if chat:
                    chat.add_system_message(msg)
                else:
                    logger.info(msg)

            # Mark executor not ready immediately so any concurrent refresh short-circuits.
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

    async def _show_costs(self) -> None:
        chat = self._maybe_chat()
        try:
            cost_data = await self.executor.get_session_costs()
            self.push_screen(SessionCostsModalScreen(cost_data))
        except Exception as e:
            logger.exception("Failed to fetch session costs")
            if chat:
                chat.add_system_message(f"Failed to fetch session costs: {e}", level="ERROR")

    async def _commit_changes(self, message: Optional[str] = None) -> None:
        """Async worker to commit current changes."""
        chat = self._maybe_chat()
        if not chat:
            return

        try:
            result = await self.executor.commit_context(message)

            if result.get("status") == "no_changes":
                chat.add_system_message("No uncommitted changes to commit.")
                return

            commit_id = result.get("commitId", "")
            first_line = result.get("firstLine", "")

            # Show short hash (first 7 chars) if available
            short_hash = commit_id[:7] if commit_id else ""
            if short_hash and first_line:
                chat.add_system_message_markup(f"Committed [bold]{short_hash}[/]: {first_line}")
            elif short_hash:
                chat.add_system_message_markup(f"Committed [bold]{short_hash}[/]")
            else:
                chat.add_system_message("Changes committed successfully.")

            # Refresh context to update any UI state
            await self._refresh_context_panel()
        except Exception as e:
            logger.exception("Commit failed")
            chat.add_system_message(f"Commit failed: {e}", level="ERROR")

    async def _create_pull_request(self, base_branch: Optional[str] = None) -> None:
        """Async worker to create a pull request."""
        chat = self._maybe_chat()
        if not chat:
            return

        chat.add_system_message("Fetching overlapping sessions and PR suggestion...")
        chat.set_job_running(True)

        # Use current branch as source, optional base branch override or let executor default
        source_branch = self.current_branch if self.current_branch != "unknown" else None

        # Fetch overlapping sessions for this PR's branch diff
        try:
            sessions_data = await self.executor.pr_sessions(
                source_branch=source_branch,
                target_branch=base_branch,
            )
        except Exception as e:
            logger.exception("Failed to fetch PR sessions")
            chat.add_system_message(f"Failed to fetch PR sessions: {e}", level="ERROR")
            chat.set_job_running(False)
            return

        sessions = sessions_data.get("sessions", [])
        resolved_source = sessions_data.get("sourceBranch", source_branch or "")
        resolved_target = sessions_data.get("targetBranch", base_branch or "")

        # Default selection: ALL overlapping sessions, matching Swing behavior
        default_selected_ids: List[str] = [str(s.get("id", "")) for s in sessions]

        try:
            suggestion = await self.executor.pr_suggest(
                source_branch=resolved_source,
                target_branch=resolved_target,
                session_ids=default_selected_ids if default_selected_ids else None,
            )
        except Exception as e:
            logger.exception("Failed to fetch PR suggestion")
            chat.add_system_message(f"Failed to fetch PR suggestion: {e}", level="ERROR")
            chat.set_job_running(False)
            return

        suggested_title = suggestion.get("title", "")
        suggested_body = suggestion.get("description", "")

        chat.set_job_running(False)

        def on_pr_result(result: Optional[tuple[str, str, List[str]]]) -> None:
            if result is None:
                chat.add_system_message("PR creation cancelled.")
                return
            title, body, selected_session_ids = result
            self.run_worker(
                self._do_create_pr(
                    title, body, resolved_source, resolved_target, selected_session_ids
                )
            )

        self.push_screen(
            PrCreateModalScreen(
                suggested_title=suggested_title,
                suggested_body=suggested_body,
                source_branch=resolved_source,
                target_branch=resolved_target,
                sessions=sessions,
                selected_session_ids=default_selected_ids,
            ),
            on_pr_result,
        )

    async def _do_create_pr(
        self,
        title: str,
        body: str,
        source_branch: str,
        target_branch: str,
        session_ids: Optional[List[str]] = None,
    ) -> None:
        """Async worker to actually create the PR after user confirms."""
        chat = self._maybe_chat()
        if not chat:
            return

        try:
            chat.add_system_message("Creating pull request...")
            chat.set_job_running(True)

            result = await self.executor.pr_create(
                title=title,
                body=body,
                source_branch=source_branch,
                target_branch=target_branch,
                session_ids=session_ids if session_ids else None,
            )

            pr_url = result.get("url", "")
            if pr_url:
                chat.add_system_message_markup(f"Pull request created: [bold]{pr_url}[/]")
            else:
                chat.add_system_message("Pull request created successfully.")

            # Refresh context to update any UI state
            await self._refresh_context_panel()

        except Exception as e:
            logger.exception("PR creation failed")
            chat.add_system_message(f"PR creation failed: {e}", level="ERROR")
        finally:
            chat.set_job_running(False)

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
                if selected_id is None:
                    # User canceled the modal (Esc); continue with current session.
                    return
                if selected_id == "new":
                    self.run_worker(self._create_session_from_menu())
                elif selected_id.startswith("rename:"):
                    sid = selected_id.split(":", 1)[1]
                    self.run_worker(self._rename_session_workflow(sid, sessions))
                elif selected_id.startswith("delete:"):
                    sid = selected_id.split(":", 1)[1]
                    self.run_worker(self._delete_session_workflow(sid))
                elif selected_id != current_id:
                    self.run_worker(self._switch_to_session(selected_id))

            async def on_rename(session_id: str, new_name: str) -> bool:
                return await self._rename_session(session_id, new_name)

            self.push_screen(SessionSelectModal(sessions, current_id, on_rename), on_selected)
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to list sessions: {e}", level="ERROR")

    async def _rename_session(self, session_id: str, new_name: str) -> bool:
        chat = self._maybe_chat()
        try:
            await self.executor.rename_session(session_id, new_name)
            if chat:
                chat.add_system_message(f"Session renamed to: {new_name}")
            await self._refresh_context_panel()
            return True
        except Exception as e:
            if chat:
                chat.add_system_message(f"Failed to rename session: {e}", level="ERROR")
            return False

    async def _create_session_from_menu(self) -> None:
        """Async worker to create a new session."""
        from brokk_code.session_persistence import save_last_session_id

        chat = self._maybe_chat()
        if not chat:
            return

        async with self._session_switch_lock:
            if self.session_switch_in_progress:
                chat.add_system_message("A session switch is already in progress.", level="WARNING")
                return
            self.session_switch_in_progress = True

        try:
            chat.set_session_loading(True, "Creating new session...")
            session_id = await self.executor.create_session()
            save_last_session_id(self.executor.workspace_dir, session_id)

            chat._message_history.clear()
            log = chat.query_one("#chat-log")
            res = log.query("*").remove()
            if asyncio.iscoroutine(res):
                await res

            # Reset cost accumulators for new session
            self.current_job_cost = 0.0
            self.session_total_cost = 0.0

            await self._refresh_context_panel()
            chat.add_system_message(f"Created and switched to session {session_id}.")
        except Exception as e:
            chat.add_system_message(f"Failed to create session: {e}", level="ERROR")
        finally:
            self.session_switch_in_progress = False
            chat.set_session_loading(False)

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

            self.run_worker(do_rename())

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

        async with self._session_switch_lock:
            if self.session_switch_in_progress:
                chat.add_system_message("A session switch is already in progress.", level="WARNING")
                return
            self.session_switch_in_progress = True
            self._current_switch_target_session_id = session_id

        # Save previous cost accumulators so we can restore them if the switch fails.
        _prev_job_cost = self.current_job_cost
        _prev_session_cost = self.session_total_cost

        try:
            chat.set_session_loading(True, f"Switching to session {session_id}...")

            # Reset accumulators immediately so UI doesn't show previous session's
            # stale costs during the switch transition. _refresh_context_panel
            # will re-seed session_total_cost from executor context shortly.
            self.current_job_cost = 0.0
            self.session_total_cost = 0.0
            # We don't update session_total_cost_id here; we let _refresh_context_panel
            # detect the mismatch between the new executor.session_id and the old ID.

            await self.executor.switch_session(session_id)
            try:
                save_last_session_id(self.executor.workspace_dir, session_id)
            except Exception:
                logger.warning(
                    "Failed to persist last session ID for session %s", session_id, exc_info=True
                )

            # Clear UI and history
            chat._message_history.clear()
            # Clear log container (the ScrollableContainer containing message widgets)
            log = chat.query_one("#chat-log")
            res = log.query("*").remove()
            if asyncio.iscoroutine(res):
                await res

            # Replay
            conversation_data = await self.executor.get_conversation()
            self._replay_conversation_entries(conversation_data)

            # Refresh
            await self._refresh_context_panel()
            chat.add_system_message(f"Successfully switched to session {session_id}.")

            # Handle queued prompt after switch
            if self._pending_switch_prompt:
                target_id, prompt = self._pending_switch_prompt
                if target_id == session_id:
                    self._pending_switch_prompt = None
                    self.run_worker(self._run_job(prompt))
                else:
                    # This shouldn't normally happen with the lock, but for safety:
                    self._pending_switch_prompt = None

        except Exception as e:
            logger.exception("Failed to switch session")
            # Restore cost accumulators so the UI reflects the original session's costs.
            self.current_job_cost = _prev_job_cost
            self.session_total_cost = _prev_session_cost
            chat.add_system_message(f"Failed to switch session: {e}", level="ERROR")
            if self._pending_switch_prompt:
                self._pending_switch_prompt = None
                chat.add_system_message("Dropped queued prompt due to session switch failure.")
        finally:
            self.session_switch_in_progress = False
            self._current_switch_target_session_id = None
            chat.set_session_loading(False)

    async def _fetch_latest_pypi_version(self) -> Optional[str]:
        """Fetches the latest version of 'brokk' from PyPI."""
        import httpx

        url = "https://pypi.org/pypi/brokk/json"
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(url, timeout=3.0)
                if response.status_code == 200:
                    data = response.json()
                    return data.get("info", {}).get("version")
        except Exception:
            logger.debug("Failed to fetch latest version from PyPI", exc_info=True)
        return None

    async def _check_for_updates(self) -> None:
        """Background worker to check for PyPI updates and refresh welcome message."""
        version = await self._fetch_latest_pypi_version()
        if version:
            self.latest_pypi_version = version
            self._show_welcome_message(refresh=True)

    async def _relaunch_executor(self) -> None:
        """Relaunch the executor process, attempting to preserve session state."""
        chat = self._maybe_chat()
        if self._relaunch_lock.locked():
            if chat:
                chat.add_system_message("Executor relaunch already in progress.")
            return

        async with self._relaunch_lock:
            self.job_in_progress = True
            if chat:
                chat.set_job_running(True)

            session_zip: Optional[bytes] = None
            current_sid = self.executor.session_id

            try:
                # 1. Capture current state if possible
                if self._executor_ready and current_sid:
                    try:
                        if self.current_job_id:
                            await self.executor.cancel_job(self.current_job_id)
                        session_zip = await self.executor.download_session_zip(current_sid)
                    except Exception as e:
                        logger.warning("Failed to capture session state for relaunch: %s", e)

                # 2. Stop the current process
                self._executor_ready = False
                self._executor_started = False
                await self.executor.stop()

                # 3. Start fresh
                await self.executor.start()
                self._executor_started = True

                # 4. Restore session state OR create new session BEFORE waiting for readiness.
                # Java /health/ready requires a session to be loaded.
                restored = False
                if session_zip and current_sid:
                    try:
                        await self.executor.import_session_zip(session_zip, session_id=current_sid)
                        restored = True
                    except Exception as e:
                        logger.warning("Failed to restore session after relaunch: %s", e)

                if not restored:
                    await self.executor.create_session()
                    if chat:
                        chat.add_system_message(
                            "Session state could not be restored; started a new session.",
                            level="WARNING",
                        )

                # 5. Wait for readiness now that a session is loaded
                if not await self.executor.wait_ready():
                    raise ExecutorError("New executor failed to become ready.")

                self._executor_ready = True
                await self._sync_model_roles_from_executor()

                # 6. Final UI refresh
                await self._refresh_context_panel()

                if chat:
                    chat.add_system_message("Executor relaunched successfully.", level="SUCCESS")

            except Exception as e:
                self._executor_ready = False
                logger.exception("Failed to relaunch executor")
                if chat:
                    chat.add_system_message(f"Failed to relaunch executor: {e}", level="ERROR")
            finally:
                self.job_in_progress = False
                if chat:
                    chat.set_job_running(False)

    async def on_unmount(self) -> None:
        """Ensure cleanup even if app exits via other means."""
        # on_unmount is a fallback shutdown path; avoid double-running shutdown logic.
        await self._shutdown_once(show_message=False)


if __name__ == "__main__":
    app = BrokkApp()
    app.run()
