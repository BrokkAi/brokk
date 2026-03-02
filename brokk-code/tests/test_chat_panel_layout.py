import pytest
from textual.app import App, ComposeResult

from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.status_line import StatusLine


class ChatPanelLayoutApp(App):
    def compose(self) -> ComposeResult:
        yield ChatPanel()


@pytest.mark.asyncio
async def test_status_line_above_chat_input():
    """Verify that the status line is positioned above the chat input in the DOM."""
    app = ChatPanelLayoutApp()
    async with app.run_test():
        chat_panel = app.query_one(ChatPanel)

        status_line = chat_panel.query_one("#status-line", StatusLine)
        input_container = chat_panel.query_one("#chat-input-container")

        # Get indices in the parent container's children list
        # ChatPanel inherits from Vertical, so indices correspond to vertical order.
        # #chat-input is nested inside #chat-input-container.
        children = chat_panel.children
        status_index = children.index(status_line)
        container_index = children.index(input_container)

        assert status_index < container_index, (
            f"Expected #status-line (index {status_index}) to be above "
            f"#chat-input-container (index {container_index})"
        )


@pytest.mark.asyncio
async def test_help_row_spinner_order():
    """Verify that the spinner is to the left of the help text in the help row."""
    app = ChatPanelLayoutApp()
    async with app.run_test():
        help_row = app.query_one("#chat-help-row")
        spinner = help_row.query_one("#help-spinner")
        help_label = help_row.query_one("#chat-help")

        children = list(help_row.children)
        spinner_index = children.index(spinner)
        label_index = children.index(help_label)

        assert spinner_index < label_index, (
            f"Expected #help-spinner (index {spinner_index}) to be before "
            f"#chat-help (index {label_index}) in horizontal row"
        )


@pytest.mark.asyncio
async def test_suggestions_overlay_chat_input():
    """
    Verify that slash suggestions are a direct child of ChatPanel
    so they can overlay above the input without being clipped by the input container.
    """
    app = ChatPanelLayoutApp()
    async with app.run_test():
        chat_panel = app.query_one(ChatPanel)
        suggestions = app.query_one("#slash-suggestions")

        assert suggestions in chat_panel.children, (
            "Suggestions should be a direct child of ChatPanel for overlay positioning"
        )
        # Check for layer and docking setup for overlay
        assert suggestions.styles.dock == "bottom"
        assert suggestions.styles.layer == "top"
