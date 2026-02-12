import asyncio
import time
from typing import Optional

from rich.markdown import Markdown
from rich.panel import Panel
from rich.text import Text
from textual import events, work
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.widgets import LoadingIndicator, RichLog, Static, TextArea


class ChatInput(TextArea):
    """A multiline text area for chat input that submits on Enter."""

    BINDINGS = [Binding("shift+enter", "insert_newline", "Insert Newline", show=False)]

    class Submitted(Message):
        """Posted when user submits the text."""

        def __init__(self, text: str) -> None:
            self.text = text
            super().__init__()

    def action_submit(self) -> None:
        text = self.text
        if text.strip():
            self.post_message(self.Submitted(text))
            self.text = ""

    def action_insert_newline(self) -> None:
        self.insert("\n")

    async def _on_key(self, event: events.Key) -> None:
        # TextArea consumes Enter for newline in its own _on_key. Intercept first so
        # Enter submits and Shift+Enter inserts a newline.
        if self.read_only:
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
        self._current_message_buffer: str = ""
        self._current_message_type: Optional[str] = None
        self._is_reasoning: bool = False
        self.response_pending: bool = False
        self.response_active: bool = False
        self._last_token_time: float = 0
        self._last_flush_time: float = 0
        self._flush_interval: float = 0.25  # seconds
        self._inactivity_timeout: float = 10.0
        self._get_now = time.time
        self._job_start_time: Optional[float] = None
        self._timer_interval = None
        self._incremental_line_index: Optional[int] = None

        # History Navigation State
        self._history: list[str] = []
        self._history_index: int = -1  # -1 means no history navigation active
        self._draft_buffer: str = ""  # Stores text before history navigation started

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        yield Static(id="streaming-response", classes="hidden")
        with Horizontal(id="chat-spinner-area", classes="hidden"):
            yield LoadingIndicator(id="chat-spinner", classes="hidden")
            yield Static(id="chat-timer", classes="ml-1 hidden")
            yield Static(id="chat-token-usage", classes="token-usage hidden")
        yield RichLog(highlight=True, markup=False, id="notification-panel", classes="hidden")
        yield ChatInput(placeholder="Type a message or /command...", id="chat-input")

    def on_mount(self) -> None:
        """Focus the input when the panel is mounted."""
        self.query_one("#chat-input", ChatInput).focus()

    def on_key(self, event: events.Key) -> None:
        """Handle Up/Down arrow keys for prompt history navigation."""
        chat_input = self.query_one("#chat-input", ChatInput)
        if not chat_input.has_focus:
            return

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

    def set_job_running(self, running: bool) -> None:
        """Explicitly controls the visibility of the job progress spinner and timer."""
        if running:
            if self._job_start_time is None:
                self._job_start_time = self._get_now()
                self._update_elapsed_time_label()
                if self._timer_interval is None:
                    self._timer_interval = self.set_interval(0.2, self._update_elapsed_time_label)
        else:
            self._job_start_time = None
            if self._timer_interval is not None:
                self._timer_interval.stop()
                self._timer_interval = None
            try:
                self.query_one("#chat-timer", Static).update("")
            except Exception:
                pass

        self._show_spinner(running)

    def set_response_pending(self) -> None:
        """Called when a job is submitted and we are waiting for the first token."""
        self.response_pending = True
        self.response_active = False

    def set_response_active(self) -> None:
        """Called when the first token of a response (or new message in stream) arrives."""
        self.response_pending = False
        self.response_active = True
        self._last_token_time = self._get_now()

    def set_response_finished(self) -> None:
        """Called when the job loop exits. Flushes remaining tokens.
        Does not manage spinner/ticker state (see set_job_running)."""
        self.response_pending = False
        self.response_active = False
        # Some backends do not emit an explicit terminal token; flush any buffered text on finish.
        self._flush_message()


    def _update_spinner_area_visibility(self) -> None:
        try:
            area = self.query_one("#chat-spinner-area", Horizontal)
            spinner = self.query_one("#chat-spinner", LoadingIndicator)
            timer = self.query_one("#chat-timer", Static)
            usage_label = self.query_one("#chat-token-usage", Static)
        except Exception:
            return

        should_show = (
            not usage_label.has_class("hidden")
            or not spinner.has_class("hidden")
            or not timer.has_class("hidden")
        )
        area.set_class(not should_show, "hidden")

    def _show_spinner(self, show: bool) -> None:
        try:
            spinner = self.query_one("#chat-spinner", LoadingIndicator)
            timer = self.query_one("#chat-timer", Static)
        except Exception:
            return

        if show:
            spinner.remove_class("hidden")
            timer.remove_class("hidden")
        else:
            spinner.add_class("hidden")
            timer.add_class("hidden")

        self._update_spinner_area_visibility()

    def _update_elapsed_time_label(self) -> None:
        """Updates the elapsed time ticker label."""
        if self._job_start_time is None:
            return

        try:
            timer_label = self.query_one("#chat-timer", Static)
        except Exception:
            return

        elapsed = max(0, int(self._get_now() - self._job_start_time))
        hours, remainder = divmod(elapsed, 3600)
        minutes, seconds = divmod(remainder, 60)

        if hours > 0:
            time_str = f"{hours:02}:{minutes:02}:{seconds:02}"
        else:
            time_str = f"{minutes:02}:{seconds:02}"

        timer_label.update(f"Elapsed: {time_str}")

    @work(exclusive=True)
    async def _monitor_inactivity(self) -> None:
        """Re-shows spinner if no tokens arrive for a while during an active stream."""
        while self.response_active:
            await asyncio.sleep(1.0)
            self._check_inactivity()

    def _check_inactivity(self) -> None:
        """Internal check to update spinner based on time since last token."""
        if (
            self.response_active
            and (self._get_now() - self._last_token_time) > self._inactivity_timeout
        ):
            self._show_spinner(True)

    def append_token(
        self,
        token: str,
        message_type: str,
        is_new_message: bool,
        is_reasoning: bool,
        is_terminal: bool,
    ) -> None:
        """Appends a token to the current buffer and handles rendering transitions."""
        now = self._get_now()
        self._last_token_time = now

        # Defensive: Ensure response is marked active if tokens are arriving
        if not self.response_active:
            self.set_response_active()
            self._monitor_inactivity()

        # Handle transitions: new message flag or switching between reasoning/normal
        should_start_new = is_new_message or (
            self._current_message_buffer and self._is_reasoning != is_reasoning
        )

        if should_start_new:
            if self._current_message_buffer:
                self._flush_message()

            self._current_message_type = message_type
            self._is_reasoning = is_reasoning
            self._last_flush_time = now

        self._current_message_buffer += token

        if is_terminal:
            self._flush_message()
        elif not is_reasoning:
            # Incremental rendering for AI responses
            should_flush = (now - self._last_flush_time) > self._flush_interval or "\n" in token
            if should_flush:
                self._flush_message(is_incremental=True)
                self._last_flush_time = now

    def _flush_message(self, is_incremental: bool = False) -> None:
        """Renders the accumulated buffer as Markdown or a reasoning Panel."""
        log = self.query_one("#chat-log", RichLog)
        streaming_area = self.query_one("#streaming-response", Static)

        if not self._current_message_buffer.strip():
            if not is_incremental:
                self._current_message_buffer = ""
                streaming_area.update("")
                streaming_area.add_class("hidden")
            return

        if self._is_reasoning:
            # Reasoning is flushed only when complete
            if not is_incremental:
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
        else:
            # AI Response
            content = self._current_message_buffer.strip()
            if is_incremental:
                # Update the live preview widget instead of the append-only log
                streaming_area.remove_class("hidden")
                streaming_area.update(Markdown(content))
                streaming_area.scroll_end(animate=False)
                # Auto-scroll the log to keep the bottom visible while streaming
                log.scroll_end(animate=False)
            else:
                # Message is complete, hide preview and commit to log
                streaming_area.update("")
                streaming_area.add_class("hidden")
                log.write(Markdown(content))
                log.write("")  # Spacer
                self._current_message_buffer = ""

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
            usage_label = self.query_one("#chat-token-usage", Static)
            usage_label.set_class(not visible, "hidden")
        except Exception:
            return

        self._update_spinner_area_visibility()

    def set_token_usage(self, used: int, max_tokens: Optional[int] = None) -> None:
        """Updates the token usage display in the spinner area."""
        try:
            usage_label = self.query_one("#chat-token-usage", Static)
        except Exception:
            return

        if used <= 0:
            usage_label.update("")
            return

        if max_tokens and max_tokens > 0:
            bar_width = 20
            # Clamp ratio between 0 and 1
            ratio = max(0.0, min(1.0, used / max_tokens))
            filled_len = int(bar_width * ratio)
            bar = "█" * filled_len + "░" * (bar_width - filled_len)
            usage_text = f"[{bar}] {used:,} / {max_tokens:,}"
        else:
            usage_text = f"Tokens: {used:,}"

        # Using Text object to avoid markup injection/crashes
        usage_label.update(Text(usage_text))
