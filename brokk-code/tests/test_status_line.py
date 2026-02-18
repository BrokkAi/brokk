import asyncio
import time
import pytest
from textual.app import App, ComposeResult
from brokk_code.widgets.status_line import StatusLine

class StatusApp(App):
    def compose(self) -> ComposeResult:
        yield StatusLine(id="status")

@pytest.mark.asyncio
async def test_chat_panel_timer_lifecycle():
    """Verify that the timer in ChatPanel help row updates correctly when job is running."""
    from brokk_code.widgets.chat_panel import ChatPanel
    from textual.widgets import Static

    class ChatApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = ChatApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        elapsed_label = chat_panel.query_one("#help-elapsed", Static)
        help_spinner = chat_panel.query_one("#help-spinner")

        # Setup deterministic clock
        current_time = 2000.0
        chat_panel._get_now = lambda: current_time

        async def wait_for_timer(expected: str, timeout: float = 1.0):
            start = time.time()
            while time.time() - start < timeout:
                # Textual Static.render() returns a Renderable, cast to string for comparison
                if str(elapsed_label.render()) == expected:
                    return
                await pilot.pause()
                await asyncio.sleep(0.01)
            assert str(elapsed_label.render()) == expected

        # Initial state: hidden
        assert elapsed_label.has_class("hidden")
        assert help_spinner.has_class("hidden")

        # Trigger via ChatPanel
        chat_panel.set_job_running(True)
        await pilot.pause()

        # Verify visibility and timer initialization
        assert not elapsed_label.has_class("hidden")
        assert not help_spinner.has_class("hidden")
        await wait_for_timer("Elapsed: 00:00")

        # Advance time and verify update
        current_time += 65.0
        await wait_for_timer("Elapsed: 01:05")

        # Stop via ChatPanel
        chat_panel.set_job_running(False)
        await pilot.pause()
        assert elapsed_label.has_class("hidden")
        assert help_spinner.has_class("hidden")
