import asyncio
import time
from typing import Optional

from rich.markdown import Markdown
from rich.panel import Panel
from rich.text import Text
from textual import work
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.widgets import Input, LoadingIndicator, RichLog, Static


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
        self._incremental_line_index: Optional[int] = None

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        with Horizontal(id="chat-spinner-area", classes="hidden"):
            yield LoadingIndicator(id="chat-spinner")
            yield Static(id="chat-timer", classes="ml-1")
        yield RichLog(highlight=True, markup=False, id="notification-panel", classes="hidden")
        yield Input(placeholder="Type a message or /command...", id="chat-input")

    def on_mount(self) -> None:
        """Focus the input when the panel is mounted."""
        self.query_one("#chat-input", Input).focus()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.value.strip():
            self.post_message(self.Submitted(event.value))
            event.input.value = ""

    def set_job_running(self, running: bool) -> None:
        """Explicitly controls the visibility of the job progress spinner and timer."""
        if running:
            if self._job_start_time is None:
                self._job_start_time = self._get_now()
                self._update_elapsed_time()
        else:
            self._job_start_time = None
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

    def _show_spinner(self, show: bool) -> None:
        spinner_area = self.query_one("#chat-spinner-area", Horizontal)
        if show:
            spinner_area.remove_class("hidden")
        else:
            spinner_area.add_class("hidden")

    @work(exclusive=True)
    async def _update_elapsed_time(self) -> None:
        """Periodic worker to update the elapsed time ticker."""
        try:
            timer_label = self.query_one("#chat-timer", Static)
        except Exception:
            return

        while self._job_start_time is not None:
            elapsed = max(0, int(self._get_now() - self._job_start_time))
            hours, remainder = divmod(elapsed, 3600)
            minutes, seconds = divmod(remainder, 60)

            if hours > 0:
                time_str = f"{hours:02}:{minutes:02}:{seconds:02}"
            else:
                time_str = f"{minutes:02}:{seconds:02}"

            timer_label.update(f"Elapsed: {time_str}")
            # Use a smaller sleep interval to remain responsive to _job_start_time becoming None,
            # but the logic remains driven by _get_now for deterministic testing.
            await asyncio.sleep(0.1)

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

        if is_new_message:
            self.set_response_active()
            self._monitor_inactivity()
            self._flush_message()
            self._current_message_type = message_type
            self._is_reasoning = is_reasoning
            self._last_flush_time = now

            if is_reasoning:
                log = self.query_one("#chat-log", RichLog)
                log.write(Text("Thinking...", style="italic grey50"))

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
        if not self._current_message_buffer.strip():
            if not is_incremental:
                self._current_message_buffer = ""
                self._incremental_line_index = None
            return

        log = self.query_one("#chat-log", RichLog)

        if self._is_reasoning:
            # Reasoning is flushed only when complete
            if not is_incremental:
                log.write(
                    Panel(
                        Text(self._current_message_buffer.strip(), style="grey50"),
                        title="Thinking",
                        border_style="grey37",
                    )
                )
                self._current_message_buffer = ""
                self._is_reasoning = False
        else:
            # AI Response
            content = self._current_message_buffer.strip()
            # If incremental, we replace the previous line we wrote if it exists
            if is_incremental:
                if self._incremental_line_index is not None:
                    # Clear the previous incremental line. RichLog doesn't have a direct 'replace'
                    # so we clear and rewrite if it's the last line, but Textual RichLog
                    # is append-only. To achieve "incremental" look without excessive growth:
                    # we write the markdown. Note: Multiple log.write calls for the same
                    # message will repeat the content.
                    # Standard RichLog behavior makes it hard to 'update' a line.
                    # We will append, but only if significant content has changed.
                    pass

                # For now, append markdown. In a real terminal, we'd ideally use a Static
                # widget for the "active" message and move it to RichLog when done,
                # but RichLog is the current architecture.
                log.write(Markdown(content))
            else:
                log.write(Markdown(content))
                log.write("")  # Spacer
                self._current_message_buffer = ""
                self._incremental_line_index = None

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
