from typing import Optional

from rich.markdown import Markdown
from rich.panel import Panel
from rich.text import Text
from textual.app import ComposeResult
from textual.containers import Vertical
from textual.message import Message
from textual.widgets import Input, RichLog


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

    def compose(self) -> ComposeResult:
        yield RichLog(highlight=True, markup=True, id="chat-log")
        yield Input(placeholder="Type a message or /command...", id="chat-input")

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.value.strip():
            self.post_message(self.Submitted(event.value))
            event.input.value = ""

    def append_token(
        self,
        token: str,
        message_type: str,
        is_new_message: bool,
        is_reasoning: bool,
        is_terminal: bool,
    ) -> None:
        """Appends a token to the current buffer and handles rendering transitions."""
        log = self.query_one("#chat-log", RichLog)

        if is_new_message:
            self._flush_message()
            self._current_message_type = message_type
            self._is_reasoning = is_reasoning

            if is_reasoning:
                log.write(Text("Thinking...", style="italic grey50"))

        self._current_message_buffer += token

        # Incremental feedback for non-terminal tokens
        if token:
            log.write(token, scroll_end=True)

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
        """Renders a system message styled by level."""
        log = self.query_one("#chat-log", RichLog)
        style = {"INFO": "italic grey50", "WARNING": "bold yellow", "ERROR": "bold red"}.get(
            level.upper(), "italic grey50"
        )

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
            log.write(f"[bold green]{author}:[/] {text}")
