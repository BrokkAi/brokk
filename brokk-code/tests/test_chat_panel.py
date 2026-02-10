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
        await pilot.pause()
        assert "hidden" in spinner_area.classes
        assert not spinner_area.visible
        assert str(timer_label.render()) == ""

        # Start job
        panel.set_job_running(True)
        await pilot.pause()
        assert "hidden" not in spinner_area.classes
        assert spinner_area.visible

        # Wait for the update worker to run once
        await asyncio.sleep(0.1)
        assert str(timer_label.render()) == "Elapsed: 00:00"

        # Advance time by 65 seconds
        current_time += 65.0
        # Give the Textual interval a moment to fire and process the mock time update
        # We may need multiple pauses to ensure the interval task is scheduled and executed
        await pilot.pause()
        await pilot.pause()
        assert str(timer_label.render()) == "Elapsed: 01:05"

        # Advance time to cross 1 hour (3600s + 65s = 3665s)
        current_time += 3600.0
        await pilot.pause()
        assert str(timer_label.render()) == "Elapsed: 01:01:05"

        # Verify timer continues even with NO tokens arriving
        current_time += 10.0
        await pilot.pause()
        assert str(timer_label.render()) == "Elapsed: 01:01:15"

        # Append tokens - spinner should STAY visible
        panel.append_token(
            "Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False
        )
        assert "hidden" not in spinner_area.classes

        panel.append_token(
            " world", "AI", is_new_message=False, is_reasoning=False, is_terminal=True
        )
        assert "hidden" not in spinner_area.classes

        # Verify set_response_finished does NOT hide spinner or stop timer
        panel.set_response_finished()
        assert "hidden" not in spinner_area.classes

        current_time += 5.0
        await asyncio.sleep(0.2)
        assert str(timer_label.render()) == "Elapsed: 01:10"

        # Explicit job finish - hides area and clears timer
        panel.set_job_running(False)
        await pilot.pause()
        assert "hidden" in spinner_area.classes
        assert not spinner_area.visible
        # Wait for worker to exit and check final state
        await asyncio.sleep(0.1)
        assert str(timer_label.render()) == ""


@pytest.mark.asyncio
async def test_token_usage_update():
    """Verify that updating token usage updates the widget text."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        usage_label = panel.query_one("#chat-token-usage", Static)

        # Initial empty
        assert str(usage_label.render()) == ""

        # Update with used and max
        panel.set_token_usage(1500, 100000)
        # 1,500 / 100,000 is 1.5%. In a 20-char bar, that's 0 blocks filled.
        # bar_width = 20, ratio = 0.015, filled_len = 0
        expected_bar = "░" * 20
        assert str(usage_label.render()) == f"[{expected_bar}] 1,500 / 100,000"

        # Update with half usage
        panel.set_token_usage(50000, 100000)
        expected_half_bar = "█" * 10 + "░" * 10
        assert str(usage_label.render()) == f"[{expected_half_bar}] 50,000 / 100,000"

        # Update with only used
        panel.set_token_usage(2500)
        assert str(usage_label.render()) == "Tokens: 2,500"

        # Update with 0 clears it
        panel.set_token_usage(0)
        assert str(usage_label.render()) == ""
