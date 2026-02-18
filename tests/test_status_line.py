import asyncio
import time
import pytest
from textual.app import App, ComposeResult
from brokk_code.widgets.status_line import StatusLine

class StatusApp(App):
    def compose(self) -> ComposeResult:
        yield StatusLine(id="status")

@pytest.mark.asyncio
async def test_status_line_timer_lifecycle():
    """Verify that the timer in StatusLine updates correctly when job is running."""
    app = StatusApp()
    async with app.run_test() as pilot:
        status = app.query_one(StatusLine)
        timer_wrap = status.query_one("#status-timer-wrap")
        timer_label = status.query_one("#status-timer")

        # Setup deterministic clock
        current_time = 1000.0
        status._get_now = lambda: current_time

        async def wait_for_timer(expected: str, timeout: float = 1.0):
            start = time.time()
            while time.time() - start < timeout:
                if str(timer_label.render()) == expected:
                    return
                await pilot.pause()
                await asyncio.sleep(0.01)
            assert str(timer_label.render()) == expected

        # Initial state
        assert timer_wrap.has_class("hidden")
        assert str(timer_label.render()) == ""

        # Start job
        status.set_job_running(True)
        await pilot.pause()
        assert not timer_wrap.has_class("hidden")
        await wait_for_timer("Elapsed: 00:00")

        # Advance 65s
        current_time += 65.0
        await wait_for_timer("Elapsed: 01:05")

        # Finish job
        status.set_job_running(False)
        await pilot.pause()
        assert timer_wrap.has_class("hidden")

@pytest.mark.asyncio
async def test_chat_panel_status_integration():
    """Test that ChatPanel correctly delegates job state to its StatusLine."""
    from brokk_code.widgets.chat_panel import ChatPanel
    
    class ChatApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = ChatApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        status_line = chat_panel.query_one(StatusLine)
        timer_wrap = status_line.query_one("#status-timer-wrap")
        timer_label = status_line.query_one("#status-timer")

        # Setup deterministic clock
        current_time = 2000.0
        status_line._get_now = lambda: current_time

        # Initial state: hidden
        assert "hidden" in timer_wrap.classes

        # Trigger via ChatPanel
        chat_panel.set_job_running(True)
        await pilot.pause()

        # Verify visibility and timer initialization
        assert "hidden" not in timer_wrap.classes
        assert "Elapsed: 00:00" in str(timer_label.render())

        # Verify help spinner is also visible
        help_spinner = chat_panel.query_one("#help-spinner")
        assert not help_spinner.has_class("hidden")

        # Advance time and verify update
        current_time += 5.0
        # The interval is 0.2s, so we wait briefly for the next tick
        await asyncio.sleep(0.3)
        await pilot.pause()
        assert "Elapsed: 00:05" in str(timer_label.render())

        # Stop via ChatPanel
        chat_panel.set_job_running(False)
        await pilot.pause()
        assert "hidden" in timer_wrap.classes
