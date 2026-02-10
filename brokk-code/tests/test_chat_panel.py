import pytest
from brokk_code.widgets.chat_panel import ChatPanel
from textual.widgets import LoadingIndicator

@pytest.mark.asyncio
async def test_spinner_lifecycle():
    """
    Verify that the spinner visibility is controlled explicitly and
    remains visible during token streaming.
    """
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        spinner = panel.query_one("#chat-spinner", LoadingIndicator)
        
        # Initial state: hidden
        assert "hidden" in spinner.classes
        
        # Start job
        panel.set_job_running(True)
        assert "hidden" not in spinner.classes
        
        # Append tokens - spinner should STAY visible
        panel.append_token("Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False)
        assert "hidden" not in spinner.classes
        
        panel.append_token(" world", "AI", is_new_message=False, is_reasoning=False, is_terminal=True)
        assert "hidden" not in spinner.classes
        
        # Explicit finish
        panel.set_job_running(False)
        assert "hidden" in spinner.classes
