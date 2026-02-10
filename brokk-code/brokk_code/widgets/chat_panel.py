import asyncio
import time
from typing import Optional

from rich.markdown import Markdown
from rich.panel import Panel
from rich.text import Text
from textual import work
from textual.app import ComposeResult
from textual.containers import Vertical
from textual.message import Message
from textual.widgets import Input, LoadingIndicator, RichLog


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
        self._inactivity_timeout: float = 10.0
        self._get_now = time.time

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        yield LoadingIndicator(id="chat-spinner", classes="hidden")
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
        """Explicitly controls the visibility of the job progress spinner."""
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
        """Called when the job is complete, cancelled, or failed."""
        self.response_pending = False
        self.response_active = False
        # Some backends do not emit an explicit terminal token; flush any buffered text on finish.
        self._flush_message()

    def _show_spinner(self, show: bool) -> None:
        spinner = self.query_one("#chat-spinner", LoadingIndicator)
        if show:
            spinner.remove_class("hidden")
        else:
            spinner.add_class("hidden")

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
        self._last_token_time = self._get_now()

        if is_new_message:
            self.set_response_active()
            self._monitor_inactivity()
            self._flush_message()
            self._current_message_type = message_type
            self._is_reasoning = is_reasoning

            if is_reasoning:
                log = self.query_one("#chat-log", RichLog)
                log.write(Text("Thinking...", style="italic grey50"))

        self._current_message_buffer += token

        if is_terminal:
            self._flush_message()

    def _flush_message(self) -> None:
        """Renders the accumulated buffer as Markdown or a reasoning Panel and clears it."""
        if not self._current_message_buffer.strip():
            self._current_message_buffer = ""
            return

        log = self.query_one("#chat-log", RichLog)

        if self._is_reasoning:
            # Render reasoning in a distinct panel
            log.write(
                Panel(
                    Text(self._current_message_buffer.strip(), style="grey50"),
                    title="Thinking",
                    border_style="grey37",
                )
            )
        else:
            # Render AI response as Markdown
            log.write(Markdown(self._current_message_buffer.strip()))
            log.write("")  # Spacer

        self._current_message_buffer = ""
        self._is_reasoning = False

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
