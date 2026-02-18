import pytest
from textual.app import App, ComposeResult
from brokk_code.widgets.chat_panel import ChatPanel, ChatInput
from brokk_code.widgets.status_line import StatusLine

class ChatPanelLayoutApp(App):
    def compose(self) -> ComposeResult:
        yield ChatPanel()

@pytest.mark.asyncio
async def test_status_line_above_chat_input():
    """Verify that the status line is positioned above the chat input in the DOM."""
    app = ChatPanelLayoutApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        
        status_line = chat_panel.query_one("#status-line", StatusLine)
        chat_input = chat_panel.query_one("#chat-input", ChatInput)
        
        # Get indices in the parent container's children list
        # ChatPanel inherits from Vertical, so indices correspond to vertical order
        children = chat_panel.children
        status_index = children.index(status_line)
        input_index = children.index(chat_input)
        
        assert status_index < input_index, (
            f"Expected #status-line (index {status_index}) to be above "
            f"#chat-input (index {input_index})"
        )
