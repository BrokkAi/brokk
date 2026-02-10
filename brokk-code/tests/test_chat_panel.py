import asyncio
import pytest
from brokk_code.widgets.chat_panel import ChatPanel
from textual.widgets import Static
from textual.containers import Horizontal

@pytest.mark.asyncio
async def test_spinner_and_timer_lifecycle():
    """
    Verify that the spinner and elapsed timer visibility and values are controlled
    explicitly and respond to simulated time.
    """
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        spinner_area = panel.query_one("#chat-spinner-area", Horizontal)
        timer_label = panel.query_one("#chat-timer", Static)

        # Setup deterministic clock
        current_time = 1000.0
        def mock_now():
            return current_time
        panel._get_now = mock_now
        
        # Initial state: hidden, no timer text, display is 'none'
        assert "hidden" in spinner_area.classes
        assert spinner_area.styles.display == "none"
        assert str(timer_label.renderable) == ""
        
        # Start job
        panel.set_job_running(True)
        assert "hidden" not in spinner_area.classes
        assert spinner_area.styles.display == "block"
        
        # Wait for the update worker to run once
        await asyncio.sleep(0.1)
        assert str(timer_label.renderable) == "Elapsed: 00:00"

        # Advance time by 65 seconds
        current_time += 65.0
        await asyncio.sleep(0.2) # Give worker time to loop
        assert str(timer_label.renderable) == "Elapsed: 01:05"

        # Advance time to cross 1 hour (3600s + 65s = 3665s)
        current_time += 3600.0
        await asyncio.sleep(0.2)
        assert str(timer_label.renderable) == "Elapsed: 01:01:05"
        
        # Append tokens - spinner should STAY visible
        panel.append_token("Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False)
        assert "hidden" not in spinner_area.classes
        
        panel.append_token(" world", "AI", is_new_message=False, is_reasoning=False, is_terminal=True)
        assert "hidden" not in spinner_area.classes
        
        # Verify set_response_finished does NOT hide spinner or stop timer
        panel.set_response_finished()
        assert "hidden" not in spinner_area.classes
        
        current_time += 5.0
        await asyncio.sleep(0.2)
        assert str(timer_label.renderable) == "Elapsed: 01:10"

        # Explicit job finish - hides area and clears timer
        panel.set_job_running(False)
        assert "hidden" in spinner_area.classes
        assert spinner_area.styles.display == "none"
        # Wait for worker to exit and check final state
        await asyncio.sleep(0.1)
        assert str(timer_label.renderable) == ""
