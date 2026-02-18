import time
from typing import Any, Callable, Dict, List, Optional

from rich.markdown import Markdown
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


class SlashCommandSuggestions(ListView):
    """A popup list for slash command autocomplete."""

    DEFAULT_CSS = """
    SlashCommandSuggestions {
        display: none;
        background: $surface;
        border-top: solid $primary-darken-1;
        border-bottom: none;
        border-left: none;
        border-right: none;
        height: auto;
        max-height: 10;
        width: 1fr;
        margin: 0 2;
        layer: top;
    }
    SlashCommandSuggestions ListItem {
        padding: 0 1;
    }
    """

    class CommandSelected(Message):
        def __init__(self, command: str) -> None:
            self.command = command
            super().__init__()

    def update_suggestions(self, query: str, commands: List[Dict[str, str]]) -> bool:
        """Filters suggestions based on query. Returns True if there are matches."""
        self.clear()
        query_stripped = query.strip().lower()
        if not query_stripped.startswith("/") or "\n" in query:
            self.display = False
            return False

        matches = []
        for c in commands:
            cmd_name = c["command"].lower()
            # Direct prefix match for the command
            if cmd_name.startswith(query_stripped):
                matches.append(c)
            # For multi-word commands like /task next, if user typed "/task n"
            elif query_stripped.startswith("/task ") and cmd_name.startswith(query_stripped):
                matches.append(c)

        if not matches:
            self.display = False
            return False

        for m in matches:
            li = ListItem(Static(f"{m['command']} - {m['description']}", markup=False))
            # Store the raw command on the ListItem for easier retrieval
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


class ChatInput(TextArea):
    """A multiline text area for chat input that submits on Enter."""

    suppress_autocomplete_once: bool = False

    BINDINGS = [
        Binding("shift+enter", "insert_newline", "Insert Newline", show=False),
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
            self.action_hide_autocomplete()
            self.post_message(self.Submitted(text))
            self.text = ""

    def action_insert_newline(self) -> None:
        self.insert("\n")

    def action_hide_autocomplete(self) -> None:
        """Hides the popup suggestions."""
        try:
            suggestions = self.app.query_one(SlashCommandSuggestions)
            suggestions.display = False
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
        except Exception:
            pass

    def _on_text_area_changed(self, event: TextArea.Changed) -> None:
        """Triggered whenever text changes via typing or backspace."""
        app = self.app
        try:
            suggestions = app.query_one(SlashCommandSuggestions)
        except Exception:
            return

        if self.suppress_autocomplete_once:
            suggestions.display = False
            self.suppress_autocomplete_once = False
            return

        # Always hide if text is empty or focus is lost (programmatic updates)
        if not self.text.strip() or not self.has_focus:
            suggestions.display = False
            return

        if self.text.startswith("/") and "\n" not in self.text:
            commands = []
            if hasattr(app, "get_slash_commands"):
                commands = app.get_slash_commands()
            suggestions.update_suggestions(self.text, commands)
        else:
            suggestions.display = False

    async def _on_key(self, event: events.Key) -> None:
        # TextArea consumes Enter for newline in its own _on_key. Intercept first so
        # Enter submits and Shift+Enter inserts a newline.
        if self.read_only:
            return
        if event.key == "ctrl+d":
            event.stop()
            event.prevent_default()
            self.action_quit()
            return

        try:
            suggestions = self.app.query_one(SlashCommandSuggestions)
        except Exception:
            suggestions = None

        if suggestions and suggestions.display:
            if event.key in ("up", "down"):
                suggestions._on_key(event)
                event.stop()
                event.prevent_default()
                return
            if event.key == "tab":
                self.action_accept_suggestion()
                event.stop()
                event.prevent_default()
                return
            if event.key == "enter":
                # Select the suggestion, then submit the result
                suggestions.action_select_cursor()
                suggestions.display = False
                self.action_submit()
                event.stop()
                event.prevent_default()
                return
            if event.key == "escape":
                self.action_hide_autocomplete()
                event.stop()
                event.prevent_default()
                return

        if event.key == "enter":
            event.stop()
            event.prevent_default()
            self.action_submit()
            return
        if event.key == "shift+enter":
            event.stop()
            event.prevent_default()
            self.action_insert_newline()
            return

        await super()._on_key(event)


class ChatPanel(Vertical):
    """Main chat interface with message display and input."""

    class Submitted(Message):
        """Posted when user submits a message."""

        def __init__(self, text: str) -> None:
            self.text = text
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

        # History Navigation State
        self._history: list[str] = []
        self._history_index: int = -1  # -1 means no history navigation active
        self._draft_buffer: str = ""  # Stores text before history navigation started

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        yield RichLog(highlight=True, markup=False, id="notification-panel", classes="hidden")
        yield TokenBar(id="chat-token-bar", classes="hidden")
        yield StatusLine(id="status-line")
        with Vertical(id="chat-input-container"):
            yield SlashCommandSuggestions(id="slash-suggestions")
            yield ChatInput(placeholder="Type a message or /command...", id="chat-input")
        with Horizontal(id="chat-help-row"):
            yield LoadingIndicator(id="help-spinner", classes="hidden")
            yield Static(id="help-elapsed", classes="hidden")
            yield Static(
                "Enter: Submit  Shift+Enter: Newline  Up/Down: History  /commands",
                id="chat-help",
            )

    def on_mount(self) -> None:
        """Focus the input when the panel is mounted."""
        self.query_one("#chat-input", ChatInput).focus()

    def on_key(self, event: events.Key) -> None:
        """Handle Up/Down arrow keys for prompt history navigation."""
        chat_input = self.query_one("#chat-input", ChatInput)
        if not chat_input.has_focus:
            return

        # Bypass history navigation if suggestions are visible
        try:
            suggestions = self.query_one(SlashCommandSuggestions)
            if suggestions.display:
                return
        except Exception:
            pass

        # Only trigger history navigation if there is no selection
        if not chat_input.selection.is_empty:
            return

        if event.key == "up":
            # Only navigate history if at the start of the text,
            # or if history navigation is already active.
            if self._history_index != -1 or chat_input.cursor_at_start_of_text:
                self._navigate_history(-1)
                event.prevent_default()
        elif event.key == "down":
            # Only navigate history if at the end of the text,
            # or if history navigation is already active.
            if self._history_index != -1 or chat_input.cursor_at_end_of_text:
                self._navigate_history(1)
                event.prevent_default()

    def _navigate_history(self, delta: int) -> None:
        """
        Logic for cycling through history:
        - Up (delta -1): Moves towards older entries.
        - Down (delta 1): Moves towards newer entries and eventually the draft.
        - Commands (/) are not in the history.
        - Restores draft_buffer when moving past the newest entry.
        """
        if not self._history:
            return

        chat_input = self.query_one("#chat-input", ChatInput)

        # If starting navigation, save the current text
        if self._history_index == -1:
            self._draft_buffer = chat_input.text

        new_index = self._history_index + delta

        # Boundaries
        if delta == -1:  # Up
            if self._history_index == -1:
                new_index = len(self._history) - 1
            else:
                new_index = max(0, self._history_index - 1)
        else:  # Down
            if self._history_index == -1:
                return  # Already at draft
            new_index = self._history_index + 1

        if new_index >= len(self._history):
            # Move back to draft
            chat_input.text = self._draft_buffer
            self._history_index = -1
        else:
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
        self.post_message(self.Submitted(event.text))
        self._history_index = -1
        self._draft_buffer = ""

    def on_slash_command_suggestions_command_selected(
        self, event: SlashCommandSuggestions.CommandSelected
    ) -> None:
        chat_input = self.query_one("#chat-input", ChatInput)
        command = event.command

        # Append a space for commands that typically require arguments
        needs_arg = command in ("/model", "/model-code", "/reasoning", "/reasoning-code", "/task")
        # Also check for command group prefixes like "/task "
        if command.startswith(("/task ", "/autocommit ")):
            needs_arg = False  # Already has a sub-command or space

        if needs_arg:
            command += " "

        # Suppress re-showing autocomplete when we programmatically update the text
        chat_input.suppress_autocomplete_once = True

        chat_input.text = command
        chat_input.move_cursor(chat_input.document.end)
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
        log = self.query_one("#chat-log", RichLog)

        # If the buffer is empty or only whitespace, clear per-message state
        # so we don't leave a stale reasoning/typing mode active for subsequent messages.
        if not self._current_message_buffer.strip():
            self._current_message_buffer = ""
            self._is_reasoning = False
            self._current_message_type = None
            return

        if self._is_reasoning:
            content = self._current_message_buffer.strip()
            log.write(
                Panel(
                    Text(content, style="grey50"),
                    title="Thinking",
                    border_style="grey37",
                )
            )
            log.write("")  # Spacer
            self._current_message_buffer = ""
            self._is_reasoning = False
            self._current_message_type = None
        else:
            content = self._current_message_buffer.strip()
            log.write(Markdown(content))
            log.write("")  # Spacer
            self._current_message_buffer = ""
            self._current_message_type = None

    def add_user_message(self, text: str) -> None:
        """Renders a user message with distinct styling."""
        log = self.query_one("#chat-log", RichLog)
        log.write(
            Panel(Text(text, justify="left"), title="You", title_align="right", border_style="blue")
        )
        log.write("")

    def add_system_message(self, text: str, level: str = "INFO") -> None:
        """Renders a system message styled by level. Treats text as plain text."""
        log = self.query_one("#chat-log", RichLog)
        style_map = {"INFO": "italic grey50", "WARNING": "bold yellow", "ERROR": "bold red"}
        style = style_map.get(level.upper(), "italic grey50")

        prefix = f"[{level}] " if level != "INFO" else ""
        # Using Text object ensures 'text' containing markup like [/] doesn't crash parsing
        log.write(Text(f"{prefix}{text}", style=style))

    def add_system_message_markup(self, text: str, level: str = "INFO") -> None:
        """Renders a system message and allows intentional Rich markup in 'text'."""
        log = self.query_one("#chat-log", RichLog)
        style_map = {"INFO": "italic grey50", "WARNING": "bold yellow", "ERROR": "bold red"}
        style = style_map.get(level.upper(), "italic grey50")

        prefix = f"[{level}] " if level != "INFO" else ""
        log.write(f"[{style}]{prefix}{text}[/]")

    def add_notification(self, text: str, level: str = "INFO") -> None:
        """Renders a notification in the notification panel using a Text object."""
        try:
            log = self.query_one("#notification-panel", RichLog)
        except Exception:
            return

        style_map = {"INFO": "italic grey50", "WARNING": "bold yellow", "ERROR": "bold red"}
        style = style_map.get(level.upper(), "italic grey50")

        prefix = f"[{level}] " if level != "INFO" else ""
        log.write(Text(f"{prefix}{text}", style=style))

    def append_message(self, author: str, text: str) -> None:
        """Legacy helper for simple messages."""
        if author == "User":
            self.add_user_message(text)
        elif author in ("System", "Notification", "Error"):
            level = "ERROR" if author == "Error" else "INFO"
            self.add_system_message(text, level=level)
        else:
            log = self.query_one("#chat-log", RichLog)
            # Use Text objects for the author and message to avoid markup injection/crashes
            output = Text()
            output.append(f"{author}: ", style="bold green")
            output.append(text)
            log.write(output)

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
    ) -> None:
        """Updates the token usage display in the spinner area."""
        try:
            token_bar = self.query_one("#chat-token-bar", TokenBar)
            token_bar.update_tokens(used, max_tokens, fragments)
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
