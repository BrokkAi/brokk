import asyncio
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from rich.markdown import ListItem as RichMarkdownListItem
from rich.markdown import Markdown, Segment, loop_first
from rich.panel import Panel
from rich.text import Text
from textual import events
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.widgets import ListItem, ListView, LoadingIndicator, RichLog, Static, TextArea

from brokk_code.widgets.status_line import StatusLine
from brokk_code.widgets.token_bar import TokenBar

# Arrow glyphs for UI display (ASCII-only source)
UP_ARROW = chr(0x2191)  # up arrow
DOWN_ARROW = chr(0x2193)  # down arrow


class DotOrderedListItem(RichMarkdownListItem):
    """Preserve the '.' in ordered list markers (e.g. '1. item')."""

    def render_number(self, console, options, number: int, last_number: int):
        number_width = len(str(last_number)) + 2
        render_options = options.update(width=options.max_width - number_width)
        lines = console.render_lines(self.elements, render_options, style=self.style)
        number_style = console.get_style("markdown.item.number", default="none")

        new_line = Segment("\n")
        padding = Segment(" " * number_width, number_style)
        numeral = Segment(f"{number}. ".rjust(number_width), number_style)
        for first, line in loop_first(lines):
            yield numeral if first else padding
            yield from line
            yield new_line


class DotMarkdown(Markdown):
    """Rich Markdown renderer with ordered list markers rendered as 'N. '."""

    elements = dict(Markdown.elements)
    elements["list_item_open"] = DotOrderedListItem


class ModeSuggestions(ListView):
    """A popup list for selecting agent modes."""

    show_vertical_scrollbar = True
    DEFAULT_CSS = """
    ModeSuggestions {
        background: $panel;
        border: none;
        color: $text;
        scrollbar-gutter: stable;
        margin: 0 2 6 2;
        max-height: 20;
        width: 1fr;
        display: none;
        layer: top;
        dock: bottom;
    }
    """

    class ModeSelected(Message):
        def __init__(self, mode: str) -> None:
            self.mode = mode
            super().__init__()

    def update_modes(self, modes: List[str], current: str) -> None:
        self.clear()
        for mode in modes:
            marker = "[x]" if mode.upper() == current.upper() else "[ ]"
            li = ListItem(Static(f"{marker} {mode}", markup=False))
            li.mode_name = mode
            self.append(li)

        # Focus current or first
        try:
            idx = [m.upper() for m in modes].index(current.upper())
            self.index = idx
        except ValueError:
            self.index = 0

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if message.item:
            mode = getattr(message.item, "mode_name", "")
            self.display = False
            self.post_message(self.ModeSelected(mode))

    def on_key(self, event: events.Key) -> None:
        if event.key == "escape":
            self.display = False
            try:
                self.app.query_one("#chat-input").focus()
            except Exception:
                pass
            event.stop()
            return

        # If it's a character key (length 1), hide the menu and forward to input
        if event.character and len(event.character) == 1:
            self.display = False
            try:
                chat_input = self.app.query_one("#chat-input", ChatInput)
                chat_input.focus()
                # Explicitly insert the character to trigger change handlers
                chat_input.insert(event.character)
            except Exception:
                pass
            event.stop()


class ReasoningSuggestions(ListView):
    """A popup list for selecting reasoning levels."""

    show_vertical_scrollbar = True
    DEFAULT_CSS = """
    ReasoningSuggestions {
        background: $panel;
        border: none;
        color: $text;
        scrollbar-gutter: stable;
        margin: 0 2 6 2;
        max-height: 20;
        width: 1fr;
        display: none;
        layer: top;
        dock: bottom;
    }
    """

    class LevelSelected(Message):
        def __init__(self, level: str) -> None:
            self.level = level
            super().__init__()

    def update_levels(self, levels: List[str], current: str) -> None:
        self.clear()
        for level in levels:
            marker = "[x]" if level.lower() == current.lower() else "[ ]"
            li = ListItem(Static(f"{marker} {level}", markup=False))
            li.level_name = level
            self.append(li)

        # Focus current or first
        try:
            idx = [level.lower() for level in levels].index(current.lower())
            self.index = idx
        except ValueError:
            self.index = 0

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if message.item:
            level = getattr(message.item, "level_name", "")
            self.display = False
            self.post_message(self.LevelSelected(level))

    def on_key(self, event: events.Key) -> None:
        if event.key == "escape":
            self.display = False
            try:
                self.app.query_one("#chat-input").focus()
            except Exception:
                pass
            event.stop()
            return

        if event.character and len(event.character) == 1:
            self.display = False
            try:
                chat_input = self.app.query_one("#chat-input", ChatInput)
                chat_input.focus()
                chat_input.insert(event.character)
            except Exception:
                pass
            event.stop()


class SlashCommandSuggestions(ListView):
    """A popup list for slash command autocomplete."""

    show_vertical_scrollbar = True
    DEFAULT_CSS = """
    SlashCommandSuggestions {
        background: $panel;
        border: none;
        color: $text;
        scrollbar-gutter: stable;
        margin: 0 2 6 2;
        max-height: 20;
        width: 1fr;
        display: none;
        layer: top;
        dock: bottom;
    }
    """

    class CommandSelected(Message):
        def __init__(self, command: str) -> None:
            self.command = command
            super().__init__()

    def update_suggestions(self, query: str, commands: List[Dict[str, str]]) -> bool:
        """Filters suggestions based on query. Returns True if there are matches."""
        self.clear()
        # Ensure we don't show for multi-line or non-slash inputs
        if not query.startswith("/") or "\n" in query:
            return False

        query_stripped = query.strip().lower()
        matches: list[dict[str, str]] = []
        for c in commands:
            cmd_name = c["command"].lower()
            if cmd_name.startswith(query_stripped):
                matches.append(c)

        if not matches:
            self.display = False
            return False

        for m in matches:
            li = ListItem(Static(f"{m['command']} - {m['description']}", markup=False))
            li.command_name = m["command"]
            self.append(li)

        self.index = 0
        self.display = True
        return True

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if message.item:
            cmd_text = getattr(message.item, "command_name", "")
            if not cmd_text:
                # Fallback to parsing if attribute missing
                cmd_text = str(message.item.query_one(Static).renderable).split(" - ")[0]
            self.display = False
            self.post_message(self.CommandSelected(cmd_text))


class MentionSuggestions(ListView):
    """A popup list for @mention autocomplete."""

    show_vertical_scrollbar = True
    DEFAULT_CSS = """
    MentionSuggestions {
        background: $panel;
        border: none;
        color: $text;
        scrollbar-gutter: stable;
        margin: 0 2 6 2;
        max-height: 20;
        width: 1fr;
        display: none;
        layer: top;
        dock: bottom;
    }
    """

    class MentionSelected(Message):
        def __init__(self, value: str) -> None:
            self.value = value
            super().__init__()

    def update_suggestions(self, completions: List[Dict[str, str]]) -> bool:
        """Updates popup with completion results. Returns True if any items exist."""
        self.clear()
        if not completions:
            self.display = False
            return False

        for item in completions:
            name = item.get("name", "").strip()
            detail = item.get("detail", "").strip()
            completion_type = item.get("type", "").strip()
            value = detail or name
            if not value:
                continue
            label = f"@{name}" if name else f"@{value}"
            if detail and detail != name:
                label = f"{label} - {detail}"
            if completion_type:
                label = f"[{completion_type}] {label}"

            li = ListItem(Static(label, markup=False))
            li.mention_value = value
            self.append(li)

        has_items = len(self.children) > 0
        if not has_items:
            self.display = False
            return False

        self.index = 0
        self.display = True
        return True

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        if message.item:
            value = getattr(message.item, "mention_value", "")
            self.display = False
            if value:
                self.post_message(self.MentionSelected(value))


class ChatInput(TextArea):
    """A multiline text area for chat input that submits on Enter."""

    DEFAULT_CSS = """
    ChatInput {
        height: 3;
    }
    """

    suppress_autocomplete_once: bool = False
    submit_after_accept: bool = False
    _mention_worker: Optional[asyncio.Task[None]] = None
    _mention_request_id: int = 0

    BINDINGS = [
        Binding("shift+enter", "insert_newline", "Insert Newline", show=False),
        Binding("ctrl+j", "insert_newline", "Insert Newline", show=False),
        Binding("tab", "accept_suggestion", "Accept Suggestion", show=False),
        Binding("escape", "hide_autocomplete", "Hide Autocomplete", show=False),
    ]

    class Submitted(Message):
        """Posted when user submits the text."""

        def __init__(self, text: str) -> None:
            self.text = text
            super().__init__()

    def action_quit(self) -> None:
        """Exit immediately from the chat input widget."""
        app = self.app
        if app is not None:
            app.run_worker(app.action_quit())

    def action_submit(self) -> None:
        text = self.text
        if text.strip():
            # Suppress re-showing during the clear operation
            self.suppress_autocomplete_once = True
            self._set_autocomplete_open(False)
            self.post_message(self.Submitted(text))
            self.text = ""

    def action_insert_newline(self) -> None:
        self.insert("\n")

    def _set_autocomplete_open(self, is_open: bool) -> None:
        """Synchronizes suggestions visibility and container styling."""
        try:
            suggestions = self.app.query_one(SlashCommandSuggestions)
            mention_suggestions = self.app.query_one(MentionSuggestions)
            container = self.app.query_one("#chat-input-container")
            if not is_open:
                if suggestions.display:
                    suggestions.display = False
                if mention_suggestions.display:
                    mention_suggestions.display = False
                container.set_class(False, "autocomplete-open")
            else:
                container.set_class(
                    suggestions.display or mention_suggestions.display, "autocomplete-open"
                )
        except Exception:
            pass

    def _set_autocomplete_container_class(self) -> None:
        try:
            container = self.app.query_one("#chat-input-container")
            suggestions = self.app.query_one(SlashCommandSuggestions)
            mention_suggestions = self.app.query_one(MentionSuggestions)
            container.set_class(
                suggestions.display or mention_suggestions.display, "autocomplete-open"
            )
        except Exception:
            pass

    def _cancel_mention_worker(self) -> None:
        if self._mention_worker is not None and not self._mention_worker.done():
            self._mention_worker.cancel()
        self._mention_worker = None

    def action_hide_autocomplete(self) -> None:
        """Hides the popup suggestions."""
        self._set_autocomplete_open(False)
        self._cancel_mention_worker()
        self.submit_after_accept = False
        self.suppress_autocomplete_once = False
        try:
            mode_sug = self.app.query_one(ModeSuggestions)
            if mode_sug.display:
                mode_sug.display = False
        except Exception:
            pass
        try:
            reason_sug = self.app.query_one(ReasoningSuggestions)
            if reason_sug.display:
                reason_sug.display = False
        except Exception:
            pass

    def action_accept_suggestion(self) -> None:
        """Accepts the highlighted popup suggestion."""
        app = self.app
        try:
            suggestions = app.query_one(SlashCommandSuggestions)
            if suggestions.display:
                suggestions.action_select_cursor()
                return
            mentions = app.query_one(MentionSuggestions)
            if mentions.display:
                mentions.action_select_cursor()
                return
            modes = app.query_one(ModeSuggestions)
            if modes.display:
                modes.action_select_cursor()
                return
            reasoning = app.query_one(ReasoningSuggestions)
            if reasoning.display:
                reasoning.action_select_cursor()
                return
        except Exception:
            pass

    def watch_text(self, old_text: str, new_text: str) -> None:
        """Watch for programmatic text changes."""
        # Trigger immediate sync for slash commands as they are often set programmatically or via pilot.press.
        # Do not require focus for this path so that programmatic text changes (e.g. chat_input.insert)
        # still trigger autocomplete even if focus races haven't settled yet.
        if new_text.startswith("/") and "\n" not in new_text:
            self._sync_autocomplete(new_text, require_focus=False)
            self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))
            return

        self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))
        self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))

    def on_text_area_changed(self, event: TextArea.Changed) -> None:
        """Handle typed input for autocomplete reactivity."""
        # If we are in the middle of a multi-key sequence (like pilot.press),
        # an immediate sync helps reactivity.
        text = self.text
        self._sync_autocomplete(text, require_focus=False)
        if text.startswith("/") and "\n" not in text:
            self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))
        self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))

    def on_focus(self, event: events.Focus) -> None:
        """Re-check autocomplete when input gains focus."""
        self._sync_autocomplete(self.text, require_focus=False)

    def on_blur(self, event: events.Blur) -> None:
        """Cancel pending mention fetch when input loses focus."""
        self._cancel_mention_worker()
        self.submit_after_accept = False

    def _sync_autocomplete(self, text: str, *, require_focus: bool = True) -> None:
        """Drives autocomplete visibility based on current text and focus state."""
        if self.suppress_autocomplete_once:
            self._set_autocomplete_open(False)
            self._cancel_mention_worker()
            self.submit_after_accept = False
            self.suppress_autocomplete_once = False
            return

        # Always hide if there are multiple lines.
        # Only hide due to focus loss if explicitly required.
        if "\n" in text or (require_focus and not self.has_focus):
            self._set_autocomplete_open(False)
            self._cancel_mention_worker()
            self.submit_after_accept = False
            return

        # Resolve suggestions widgets robustly via app or parent screen
        suggestions: Optional[SlashCommandSuggestions] = None
        mention_suggestions: Optional[MentionSuggestions] = None
        try:
            suggestions = self.app.query_one(SlashCommandSuggestions)
            mention_suggestions = self.app.query_one(MentionSuggestions)
        except Exception:
            try:
                suggestions = self.query_one(SlashCommandSuggestions)
                mention_suggestions = self.query_one(MentionSuggestions)
            except Exception:
                pass

        # Slash autocomplete applies to single-line input beginning with "/".
        if text.startswith("/") and "\n" not in text:
            self._cancel_mention_worker()

            if suggestions is not None:
                commands = []
                if self.app and hasattr(self.app, "get_slash_commands"):
                    commands = self.app.get_slash_commands()

                is_any = suggestions.update_suggestions(text, commands)
                # Ensure the display state is directly synchronized for test pilot
                suggestions.display = is_any

                if is_any:
                    if mention_suggestions is not None:
                        mention_suggestions.display = False
                    # Hide other mutually exclusive menus
                    try:
                        self.app.query_one(ModeSuggestions).display = False
                        self.app.query_one(ReasoningSuggestions).display = False
                    except Exception:
                        pass
                else:
                    self.submit_after_accept = False

                self._set_autocomplete_container_class()
            return

        # Hide slash suggestions if we've left the slash command path
        if suggestions is not None:
            suggestions.display = False
        self._set_autocomplete_container_class()

        mention = self._extract_active_mention(text)
        if mention is None:
            self._cancel_mention_worker()
            return

        _, _, query = mention
        if not query.strip():
            self._cancel_mention_worker()
            try:
                mention_suggestions = self.app.query_one(MentionSuggestions)
                mention_suggestions.display = False
                self._set_autocomplete_container_class()
            except Exception:
                pass
            return

        self._mention_request_id += 1
        request_id = self._mention_request_id
        self._cancel_mention_worker()
        self._mention_worker = asyncio.create_task(
            self._fetch_mention_suggestions(query=query, request_id=request_id)
        )

    def _extract_active_mention(self, text: str) -> Optional[Tuple[int, int, str]]:
        """Returns (start_idx, end_idx, query) for active @mention under cursor."""
        cursor_row, cursor_col = self.cursor_location
        lines = text.split("\n")
        if cursor_row >= len(lines):
            return None
        cursor_idx = sum(len(line) + 1 for line in lines[:cursor_row]) + cursor_col
        cursor_idx = max(0, min(cursor_idx, len(text)))
        before_cursor = text[:cursor_idx]

        at_idx = before_cursor.rfind("@")
        if at_idx < 0:
            return None

        if at_idx > 0 and not before_cursor[at_idx - 1].isspace():
            return None

        query = before_cursor[at_idx + 1 :]
        if any(ch.isspace() for ch in query):
            return None
        return at_idx, cursor_idx, query

    async def _fetch_mention_suggestions(self, query: str, request_id: int) -> None:
        """Fetches mention completion items and updates popup if still current."""
        await asyncio.sleep(0.12)
        if request_id != self._mention_request_id:
            return
        if not self.has_focus:
            return

        try:
            app = self.app
            executor = getattr(app, "executor", None)
            if executor is None or not hasattr(executor, "get_completions"):
                return
            data = await executor.get_completions(query=query, limit=20)
            if request_id != self._mention_request_id:
                return
            completions = data.get("completions", [])
            normalized = []
            for item in completions:
                if not isinstance(item, dict):
                    continue
                normalized.append(
                    {
                        "type": str(item.get("type", "")),
                        "name": str(item.get("name", "")),
                        "detail": str(item.get("detail", "")),
                    }
                )

            mention_suggestions = self.app.query_one(MentionSuggestions)
            is_any = mention_suggestions.update_suggestions(normalized)
            if is_any:
                # Hide other menus if they were open to ensure exclusivity
                self.app.query_one(SlashCommandSuggestions).display = False
                self.app.query_one(ModeSuggestions).display = False
                self.app.query_one(ReasoningSuggestions).display = False
            self._set_autocomplete_container_class()
        except asyncio.CancelledError:
            return
        except Exception:
            pass

    async def _on_key(self, event: events.Key) -> None:
        # Track last key for autocomplete detection
        if event.character:
            self._last_key = event.character
        # TextArea consumes Enter for newline in its own _on_key. Intercept first so
        # Enter submits and Shift+Enter inserts a newline.
        if self.read_only:
            return

        # Handle explicit quit
        if event.key == "ctrl+d":
            event.stop()
            event.prevent_default()
            self.action_quit()
            return

        try:
            suggestions = self.app.query_one(SlashCommandSuggestions)
            mention_suggestions = self.app.query_one(MentionSuggestions)
            mode_suggestions = self.app.query_one(ModeSuggestions)
            reasoning_suggestions = self.app.query_one(ReasoningSuggestions)
        except Exception:
            suggestions = None
            mention_suggestions = None
            mode_suggestions = None
            reasoning_suggestions = None

        # Check suggestions, modes, or reasoning
        active_popup = None
        if suggestions and suggestions.display:
            active_popup = suggestions
        elif mention_suggestions and mention_suggestions.display:
            active_popup = mention_suggestions
        elif mode_suggestions and mode_suggestions.display:
            active_popup = mode_suggestions
        elif reasoning_suggestions and reasoning_suggestions.display:
            active_popup = reasoning_suggestions

        if active_popup:
            if event.key == "up":
                active_popup.action_cursor_up()
                event.stop()
                event.prevent_default()
                return
            if event.key == "down":
                active_popup.action_cursor_down()
                event.stop()
                event.prevent_default()
                return
            if event.key in ("tab", "enter"):
                # Flag that we want to submit immediately if Enter was used on slash suggestions.
                # Only apply this to 'suggestions' (SlashCommandSuggestions),
                # not mode/reasoning menus.
                if event.key == "enter" and active_popup == suggestions:
                    self.submit_after_accept = True

                self.action_accept_suggestion()
                event.stop()
                event.prevent_default()
                return
            if event.key == "escape":
                self.action_hide_autocomplete()
                event.stop()
                event.prevent_default()
                return

        # Track last key for autocomplete detection
        if event.character:
            self._last_key = event.character

        # Handle Enter, Ctrl+J and Shift+Enter
        if event.key == "enter":
            self.action_submit()
            return

        if event.key == "shift+enter" or event.key == "ctrl+j":
            self.action_insert_newline()
            event.stop()
            event.prevent_default()
            return

        await super()._on_key(event)
        # Ensure autocomplete re-evaluates after TextArea processes the key.
        # Immediate call for slash/mentions is critical for test pilot responsiveness.
        self._sync_autocomplete(self.text, require_focus=False)
        self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))
        self.call_after_refresh(lambda: self._sync_autocomplete(self.text, require_focus=False))


class ChatContainer(Vertical):
    """A container that manages multiple ChatPanels, one per worktree."""

    DEFAULT_CSS = """
    ChatContainer {
        height: 1fr;
        width: 1fr;
    }
    ChatPanel.hidden {
        display: none;
    }
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._panels: Dict[str, ChatPanel] = {}
        self._current_path: Optional[str] = None
        # Provide a deterministic initial panel for legacy test compatibility
        self._initial_panel: Optional[ChatPanel] = ChatPanel(id="chat-main")

    def get_panel(self, workspace_path: Path) -> ChatPanel:
        path_str = str(workspace_path)
        if path_str in self._panels:
            return self._panels[path_str]

        # Map the first requested path to the pre-composed initial panel if available
        if self._initial_panel and self._initial_panel.parent == self:
            panel = self._initial_panel
            self._initial_panel = None
            self._panels[path_str] = panel
            return panel

        # Additional paths get new numbered panels
        panel_id = f"chat-panel-{len(self._panels)}"
        panel = ChatPanel(id=panel_id)
        self._panels[path_str] = panel
        self.mount(panel)
        return panel

    def switch_to(self, workspace_path: Path) -> ChatPanel:
        path_str = str(workspace_path)
        target_panel = self.get_panel(workspace_path)

        for p in self._panels.values():
            if p == target_panel:
                p.remove_class("hidden")
            else:
                p.add_class("hidden")

        self._current_path = path_str

        # Prefer focusing the input directly if available
        try:
            chat_input = target_panel.query_one("#chat-input")
            chat_input.focus()
            if self.app:
                self.app.set_focus(chat_input)
        except Exception:
            target_panel.focus()

        return target_panel

    def compose(self) -> ComposeResult:
        if self._initial_panel:
            yield self._initial_panel


class ChatPanel(Vertical):
    """Main chat interface with message display and input."""

    class Submitted(Message):
        """Posted when user submits a message."""

        def __init__(self, text: str) -> None:
            self.text = text
            super().__init__()

    class ModeSelected(Message):
        """Posted when a mode is selected from the suggestion popup."""

        def __init__(self, mode: str) -> None:
            self.mode = mode
            super().__init__()

    class ReasoningLevelSelected(Message):
        """Posted when a reasoning level is selected from the suggestion popup."""

        def __init__(self, level: str) -> None:
            self.level = level
            super().__init__()

    class MentionSelected(Message):
        """Posted when an @mention completion is selected."""

        def __init__(self, value: str) -> None:
            self.value = value
            super().__init__()

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._get_now: Callable[[], float] = time.monotonic
        self._current_message_buffer: str = ""
        self._current_message_type: Optional[str] = None
        self._is_reasoning: bool = False
        self.response_pending: bool = False
        self.response_active: bool = False
        self._last_token_time: float = 0.0
        self._job_start_time: Optional[float] = None
        self._timer_interval: Optional[Any] = None

        # Message History for filtering and re-rendering
        self._message_history: List[Dict[str, Any]] = []
        self.show_verbose: bool = True

        # History Navigation State (Input prompts)
        self._history: list[str] = []
        self._history_index: int = -1  # -1 means no history navigation active
        self._draft_buffer: str = ""  # Stores text before history navigation started

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        yield TokenBar(id="chat-token-bar", classes="hidden")
        yield StatusLine(id="status-line")
        yield MentionSuggestions(id="mention-suggestions")
        yield ModeSuggestions(id="mode-suggestions")
        yield ReasoningSuggestions(id="reasoning-suggestions")
        yield SlashCommandSuggestions(id="slash-suggestions")
        with Vertical(id="chat-input-container"):
            yield ChatInput(placeholder="Type a message or /command...", id="chat-input")
        with Horizontal(id="chat-help-row"):
            yield LoadingIndicator(id="help-spinner", classes="hidden")
            yield Static(id="help-elapsed", classes="hidden")
            yield Static(
                f"Enter: Send  Ctrl+J: Newline  {UP_ARROW}/{DOWN_ARROW}: History  Shift+Tab: Mode  /worktree",
                id="chat-help",
            )

    def on_mount(self) -> None:
        """Focus the input when the panel is mounted."""
        # Replay any history that was added before mount (welcome, system messages)
        if self._message_history:
            self.refresh_log(self.show_verbose)

        chat_input = self.query_one("#chat-input", ChatInput)
        chat_input.focus()
        # Best-effort app-level focus for test stability
        if self.app:
            try:
                self.app.set_focus(chat_input)
            except Exception:
                pass

        # Strengthen focus after layout/refresh cycle completes.
        # We guard this so we do not steal focus from modals (e.g. ContextPanel)
        # that may have been pushed immediately after mounting.
        def strengthen_focus():
            if not self.display or self.has_class("hidden"):
                return
            # Only focus if we are on the top-most non-modal screen
            if self.app and self.app.screen_stack:
                active_screen = self.app.screen
                # If a modal or another screen is active, don't steal focus.
                # In Textual, screen_stack[0] is the base app screen.
                if active_screen is not self.app.screen_stack[0]:
                    return

            chat_input.focus()
            if self.app:
                try:
                    self.app.set_focus(chat_input)
                except Exception:
                    pass

        self.call_after_refresh(strengthen_focus)
        # Final focus retry to handle TUI startup races in tests
        self.set_timer(0.01, strengthen_focus)

    def on_key(self, event: events.Key) -> None:
        """Handle Up/Down arrow keys for prompt history navigation."""
        chat_input = self.query_one("#chat-input", ChatInput)

        # Fallback: if ChatPanel has focus but ChatInput does not, route printable characters.
        if not chat_input.has_focus and event.character and len(event.character) == 1:
            # Avoid stealing keys if a popup/menu is already visible
            popups_visible = False
            try:
                popups_visible = any(
                    [
                        self.query_one(SlashCommandSuggestions).display,
                        self.query_one(MentionSuggestions).display,
                        self.query_one(ModeSuggestions).display,
                        self.query_one(ReasoningSuggestions).display,
                    ]
                )
            except Exception:
                pass

            if not popups_visible:
                chat_input.focus()
                chat_input.move_cursor(chat_input.document.end)
                chat_input.insert(event.character)
                chat_input._sync_autocomplete(chat_input.text, require_focus=False)
                event.stop()
                event.prevent_default()
                return

        if not chat_input.has_focus:
            return

        # Bypass history navigation if suggestions, mode, or reasoning popups are visible
        try:
            if self.query_one(SlashCommandSuggestions).display:
                return
            if self.query_one(MentionSuggestions).display:
                return
            if self.query_one(ModeSuggestions).display:
                return
            if self.query_one(ReasoningSuggestions).display:
                return
        except Exception:
            pass

        # Only trigger history navigation if there is no selection
        if not chat_input.selection.is_empty:
            return

        if event.key == "up":
            # Only navigate history if at the start of the text (row 0, col 0),
            # or if history navigation is already active.
            cursor_row, cursor_col = chat_input.cursor_location
            if self._history_index != -1 or (cursor_row == 0 and cursor_col == 0):
                self._navigate_history(-1)
                event.stop()
                event.prevent_default()
        elif event.key == "down":
            # Only navigate history if at the end of the text,
            # or if history navigation is already active.
            cursor_row, _ = chat_input.cursor_location
            last_row = chat_input.document.line_count - 1
            if self._history_index != -1 or (
                cursor_row >= last_row and not chat_input.text.strip()
            ):
                self._navigate_history(1)
                event.stop()
                event.prevent_default()

    def _navigate_history(self, delta: int) -> None:
        """
        Logic for cycling through history:
        - Up (delta -1): Moves towards older entries (towards index 0).
        - Down (delta 1): Moves towards newer entries and eventually the draft.
        - Commands (/) are not in the history.
        - Restores draft_buffer when moving past the newest entry.
        """
        if not self._history:
            return

        chat_input = self.query_one("#chat-input", ChatInput)

        # If starting navigation, save the current text
        if self._history_index == -1:
            if delta == 1:
                return  # Down from draft does nothing
            self._draft_buffer = chat_input.text
            new_index = len(self._history) - 1
        else:
            new_index = self._history_index + delta

        if new_index < 0:
            new_index = 0
        elif new_index >= len(self._history):
            chat_input.text = self._draft_buffer
            self._history_index = -1
            chat_input.move_cursor(chat_input.document.end)
            return

        # Load from history
        self._history_index = new_index
        chat_input.text = self._history[self._history_index]

        # Keep cursor at end so subsequent Up/Down gating checks behave correctly.
        chat_input.move_cursor(chat_input.document.end)

    def set_history(self, history: list[str]) -> None:
        """Updates the internal history list (e.g. from disk)."""
        self._history = history
        self._history_index = -1

    def add_history_entry(self, text: str) -> None:
        """Adds a new entry to the history if it isn't a command.
        Duplicates are preserved to maintain chronological sequence."""
        if text and not text.startswith("/"):
            self._history.append(text)
        self._history_index = -1

    def on_chat_input_submitted(self, event: ChatInput.Submitted) -> None:
        """Forward submission message from the internal ChatInput."""
        self._history_index = -1
        self._draft_buffer = ""
        self.post_message(self.Submitted(event.text))

    def open_mode_menu(self, modes: List[str], current: str) -> None:
        """Opens the lightweight mode selection popup."""
        # Ensure mutual exclusivity: hide other popups and close the input container's open state
        self.query_one(SlashCommandSuggestions).display = False
        self.query_one(MentionSuggestions).display = False
        self.query_one(ReasoningSuggestions).display = False
        self.query_one("#chat-input-container").remove_class("autocomplete-open")

        ms = self.query_one(ModeSuggestions)
        ms.update_modes(modes, current)
        ms.display = True
        ms.focus()

    def open_reasoning_menu(self, levels: List[str], current: str) -> None:
        """Opens the lightweight reasoning selection popup."""
        # Ensure mutual exclusivity: hide other popups and close the input container's open state
        self.query_one(SlashCommandSuggestions).display = False
        self.query_one(MentionSuggestions).display = False
        self.query_one(ModeSuggestions).display = False
        self.query_one("#chat-input-container").remove_class("autocomplete-open")

        rs = self.query_one(ReasoningSuggestions)
        rs.update_levels(levels, current)
        rs.display = True
        rs.focus()

    def on_mode_suggestions_mode_selected(self, event: ModeSuggestions.ModeSelected) -> None:
        self.post_message(self.ModeSelected(event.mode))

    def on_reasoning_suggestions_level_selected(
        self, event: ReasoningSuggestions.LevelSelected
    ) -> None:
        self.post_message(self.ReasoningLevelSelected(event.level))

    def on_slash_command_suggestions_command_selected(
        self, event: SlashCommandSuggestions.CommandSelected
    ) -> None:
        chat_input = self.query_one("#chat-input", ChatInput)
        command = event.command

        # Append a space for commands that typically require arguments.
        # Commands like /mode, /settings, /task open modals/menus and
        # should not have a trailing space.
        needs_arg = command in ("/model", "/model-code")

        if needs_arg:
            command += " "

        # Suppress re-showing autocomplete when we programmatically update the text
        chat_input.suppress_autocomplete_once = True

        chat_input.text = command
        chat_input.move_cursor(chat_input.document.end)
        chat_input.focus()

        if chat_input.submit_after_accept:
            chat_input.submit_after_accept = False
            # We must post the message for ChatPanel.on_chat_input_submitted to see it,
            # but we also need the App's submission handler to fire immediately for tests.
            # ChatPanel handles its internal state when it receives the Submitted message.
            chat_input.action_submit()

    def on_mention_suggestions_mention_selected(
        self, event: MentionSuggestions.MentionSelected
    ) -> None:
        chat_input = self.query_one("#chat-input", ChatInput)
        text = chat_input.text
        mention = chat_input._extract_active_mention(text)
        if mention is None:
            return
        start_idx, end_idx, _ = mention
        inserted = f"@{event.value} "
        new_text = f"{text[:start_idx]}{inserted}{text[end_idx:]}"
        cursor_index = start_idx + len(inserted)
        prefix = new_text[:cursor_index]
        cursor_row = prefix.count("\n")
        cursor_col = len(prefix.rsplit("\n", 1)[-1])
        chat_input.suppress_autocomplete_once = True
        chat_input.text = new_text
        chat_input.move_cursor((cursor_row, cursor_col))
        chat_input.focus()

    def set_response_pending(self) -> None:
        """Called when a job is submitted and we are waiting for the first token."""
        self.response_pending = True
        self.response_active = False

    def set_response_active(self) -> None:
        """Called when the first token of a response arrives."""
        self.response_pending = False
        self.response_active = True
        self._last_token_time = self._get_now()

    def set_response_finished(self) -> None:
        """Called when the job loop exits. Flushes remaining tokens."""
        self.response_pending = False
        self.response_active = False
        self._flush_message()

    def append_token(
        self,
        token: str,
        message_type: str,
        is_new_message: bool,
        is_reasoning: bool,
        is_terminal: bool,
    ) -> None:
        """Appends a token to the current buffer and handles rendering transitions."""
        # Defensive: Ensure response is marked active if tokens are arriving
        if not self.response_active:
            self.set_response_active()

        # Handle transitions: new message flag or switching between reasoning/normal
        should_start_new = is_new_message or (
            self._current_message_buffer and self._is_reasoning != is_reasoning
        )

        if should_start_new:
            if self._current_message_buffer:
                self._flush_message()

            self._current_message_type = message_type
            self._is_reasoning = is_reasoning

        self._current_message_buffer += token

        if is_terminal:
            self._flush_message()

    def _flush_message(self) -> None:
        """Renders the accumulated buffer as Markdown or a reasoning Panel."""
        # If the buffer is empty or only whitespace, clear per-message state
        if not self._current_message_buffer.strip():
            self._current_message_buffer = ""
            self._is_reasoning = False
            self._current_message_type = None
            return

        content = self._current_message_buffer.strip()
        kind = "REASONING" if self._is_reasoning else "AI"

        self._message_history.append({"kind": kind, "content": content})
        self._render_message_entry(kind, content)

        self._current_message_buffer = ""
        self._is_reasoning = False
        self._current_message_type = None

    def _filter_tool_call_blocks(self, content: str) -> str:
        """
        Strips tool-call YAML blocks if show_verbose is False.
        Pattern: `headline` followed by ```yaml ... ``` or ````yaml ... ````
        """
        if self.show_verbose:
            return content

        import re

        # Pattern: `headline` followed by yaml block (3+ backticks).
        # Backreference \2 ensures the closing fence matches the opening.
        pattern = r"`([^`\n]+)`\s*\n\s*(`{3,})yaml\n(.*?)\n\2"

        def replacement(match: re.Match[str]) -> str:
            name = match.group(1)
            yaml_body = match.group(3)
            first_line = next((line.strip() for line in yaml_body.splitlines() if line.strip()), "")
            if len(first_line) > 70:
                first_line = f"{first_line[:67].rstrip()}..."
            return f"{name} [+] (ctrl+o to expand) - {first_line}"

        return re.sub(pattern, replacement, content, flags=re.DOTALL)

    def _collapsible_title(self, label: str, expanded: bool) -> str:
        """Returns a consistent title for collapsible output sections."""
        state = "[-]" if expanded else "[+]"
        action = "collapse" if expanded else "expand"
        return f"{label} {state} (ctrl+o to {action})"

    def _collapsed_summary_text(self, label: str, content: str) -> Text:
        """Returns compact text for collapsed sections to avoid heavy panel borders."""
        first_line = next((line.strip() for line in content.splitlines() if line.strip()), "")
        if len(first_line) > 70:
            first_line = f"{first_line[:67].rstrip()}..."

        summary = Text()
        summary.append(label, style="bold")
        summary.append(" [+] (ctrl+o to expand)")
        if first_line:
            summary.append(f" - {first_line}")
        return summary

    def _render_tool_call_panel(self, name: str, yaml_body: str) -> Panel | Text:
        """Renders a tool call block using the same collapsible panel style as reasoning."""
        if self.show_verbose:
            body = DotMarkdown(f"```yaml\n{yaml_body}\n```")
            return Panel(
                body,
                title=self._collapsible_title(f"Tool Call: {name}", self.show_verbose),
                title_align="center",
                border_style="grey37",
            )
        else:
            return self._collapsed_summary_text(name, yaml_body)

    def _render_ai_content(self, log: RichLog, content: str) -> None:
        """Renders AI markdown and tool-call YAML blocks with consistent formatting."""
        import re

        pattern = re.compile(
            r"`([^`\n]+)`\s*\n\s*(`{3,})yaml\n(.*?)\n\2",
            flags=re.DOTALL,
        )

        position = 0
        matched_tool_call = False
        for match in pattern.finditer(content):
            matched_tool_call = True
            before = content[position : match.start()]
            if before.strip():
                log.write(DotMarkdown(before))
                log.write("")

            tool_name = match.group(1)
            yaml_body = match.group(3)
            log.write(self._render_tool_call_panel(tool_name, yaml_body))
            log.write("")
            position = match.end()

        if not matched_tool_call:
            filtered_content = self._filter_tool_call_blocks(content)
            log.write(DotMarkdown(filtered_content))
            log.write("")
            return

        after = content[position:]
        if after.strip():
            log.write(DotMarkdown(after))
            log.write("")

    def _render_message_entry(self, kind: str, content: str, **kwargs: Any) -> None:
        """Visual rendering implementation for a single history entry."""
        try:
            log = self.query_one("#chat-log", RichLog)
        except Exception:
            # If the log isn't mounted yet, it will be rendered during refresh/mount
            return

        if kind == "AI":
            self._render_ai_content(log, content)
        elif kind == "REASONING":
            if self.show_verbose:
                panel_content = DotMarkdown(content, style="grey50")
                panel = Panel(
                    panel_content,
                    title=self._collapsible_title("Thinking", self.show_verbose),
                    title_align="center",
                    border_style="grey37",
                )
                log.write(panel)
            else:
                log.write(self._collapsed_summary_text("Thinking", content))
            log.write("")
        elif kind == "USER":
            log.write(
                Panel(
                    Text(content, justify="left"),
                    title="You",
                    title_align="right",
                    border_style="blue",
                )
            )
            log.write("")
        elif kind == "SYSTEM":
            level = kwargs.get("level", "INFO")
            markup = kwargs.get("markup", False)
            style_map = {
                "INFO": "italic grey50",
                "WARNING": "bold yellow",
                "ERROR": "bold red",
                "COST": "bold green",
            }
            style = style_map.get(level.upper(), "italic grey50")
            prefix = f"[{level}] " if level != "INFO" else ""

            if markup:
                log.write(f"[{style}]{prefix}{content}[/]")
            else:
                log.write(Text(f"{prefix}{content}", style=style))
        elif kind == "TOOL_RESULT":
            if self.show_verbose:
                panel_content = DotMarkdown(content)
                panel = Panel(
                    panel_content,
                    title=self._collapsible_title("Command Output", self.show_verbose),
                    title_align="center",
                    border_style="grey37",
                )
                log.write(panel)
            else:
                log.write(self._collapsed_summary_text("Command Output", content))
            log.write("")
        elif kind == "WELCOME":
            icon = kwargs.get("icon", "")
            log.write(Text(icon, style="#D04040"))
            log.write(DotMarkdown(content))
            log.write("")
        elif kind == "LEGACY_AUTHOR":
            author = kwargs.get("author", "System")
            output = Text()
            output.append(f"{author}: ", style="bold green")
            output.append(content)
            log.write(output)

    def refresh_log(self, show_verbose: bool) -> None:
        """Clears the RichLog and re-renders history based on the verbosity filter."""
        self.show_verbose = show_verbose
        log = self.query_one("#chat-log", RichLog)
        log.clear()

        for entry in self._message_history:
            self._render_message_entry(
                kind=entry["kind"],
                content=entry["content"],
                **{k: v for k, v in entry.items() if k not in ("kind", "content")},
            )

    def add_markdown(self, content: str) -> None:
        """Renders a block of Markdown content to the chat log."""
        self._message_history.append({"kind": "AI", "content": content})
        self._render_message_entry("AI", content)

    def add_welcome(self, icon: str, body: str) -> None:
        """Renders the welcome message: icon in Brokk red, followed by Markdown body."""
        self._message_history.append({"kind": "WELCOME", "content": body, "icon": icon})
        self._render_message_entry("WELCOME", body, icon=icon)

    def add_user_message(self, text: str) -> None:
        """Renders a user message with distinct styling."""
        self._message_history.append({"kind": "USER", "content": text})
        self._render_message_entry("USER", text)

    def add_system_message(self, text: str, level: str = "INFO") -> None:
        """Renders a system message styled by level. Treats text as plain text."""
        self._message_history.append(
            {"kind": "SYSTEM", "content": text, "level": level, "markup": False}
        )
        self._render_message_entry("SYSTEM", text, level=level, markup=False)

    def add_system_message_markup(self, text: str, level: str = "INFO") -> None:
        """Renders a system message and allows intentional Rich markup in 'text'."""
        self._message_history.append(
            {"kind": "SYSTEM", "content": text, "level": level, "markup": True}
        )
        self._render_message_entry("SYSTEM", text, level=level, markup=True)

    def add_tool_result(self, text: str) -> None:
        """Renders a tool or command result block."""
        self._message_history.append({"kind": "TOOL_RESULT", "content": text})
        self._render_message_entry("TOOL_RESULT", text)

    def append_message(self, author: str, text: str) -> None:
        """Legacy helper for simple messages."""
        if author == "User":
            self.add_user_message(text)
        elif author in ("System", "Notification", "Error"):
            level = "ERROR" if author == "Error" else "INFO"
            self.add_system_message(text, level=level)
        else:
            self._message_history.append(
                {"kind": "LEGACY_AUTHOR", "content": text, "author": author}
            )
            self._render_message_entry("LEGACY_AUTHOR", text, author=author)

    def set_token_bar_visible(self, visible: bool) -> None:
        """Toggles the visibility of the token usage bar."""
        try:
            token_bar = self.query_one("#chat-token-bar", TokenBar)
            token_bar.set_class(not visible, "hidden")
        except Exception:
            pass

    def clear_input(self) -> None:
        """Clears the chat input and resets history navigation state."""
        chat_input = self.query_one("#chat-input", ChatInput)
        chat_input.text = ""
        self._history_index = -1
        self._draft_buffer = ""

    def set_token_usage(
        self,
        used: int,
        max_tokens: Optional[int] = None,
        fragments: Optional[List[Dict[str, Any]]] = None,
        session_cost: Optional[float] = None,
    ) -> None:
        """Updates the token usage display in the spinner area."""
        try:
            token_bar = self.query_one("#chat-token-bar", TokenBar)
            token_bar.update_tokens(used, max_tokens, fragments, session_cost=session_cost)
        except Exception:
            pass

    def set_job_running(self, running: bool) -> None:
        """Update job progress state in StatusLine and the help row spinner/timer."""
        try:
            status_line = self.query_one("#status-line", StatusLine)
            status_line.set_job_running(running)
        except Exception:
            pass

        try:
            spinner = self.query_one("#help-spinner", LoadingIndicator)
            spinner.set_class(not running, "hidden")
        except Exception:
            pass

        try:
            elapsed_label = self.query_one("#help-elapsed", Static)
            if running:
                if self._job_start_time is None:
                    self._job_start_time = self._get_now()
                    self._update_help_timer()
                    if self._timer_interval is None:
                        self._timer_interval = self.set_interval(0.2, self._update_help_timer)
                elapsed_label.remove_class("hidden")
            else:
                self._job_start_time = None
                if self._timer_interval is not None:
                    self._timer_interval.stop()
                    self._timer_interval = None
                elapsed_label.add_class("hidden")
                elapsed_label.update("")
        except Exception:
            pass

    def _update_help_timer(self) -> None:
        if self._job_start_time is None:
            return
        elapsed = max(0, int(self._get_now() - self._job_start_time))
        hours, remainder = divmod(elapsed, 3600)
        minutes, seconds = divmod(remainder, 60)
        time_str = (
            f"{hours:02}:{minutes:02}:{seconds:02}" if hours > 0 else f"{minutes:02}:{seconds:02}"
        )
        try:
            self.query_one("#help-elapsed", Static).update(f"Elapsed: {time_str}")
        except Exception:
            pass

    def on_token_bar_fragment_hovered(self, message: TokenBar.FragmentHovered) -> None:
        try:
            status_line = self.query_one("#status-line", StatusLine)
        except Exception:
            return

        if message.description is None or message.size is None:
            status_line.clear_fragment_info()
            return
        status_line.set_fragment_info(description=message.description, size=message.size)
