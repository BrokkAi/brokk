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
    app = StatusApp()
    async with app.run_test() as pilot:
        status = app.query_one(StatusLine)
        progress = status.query_one("#status-progress")
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
        assert progress.has_class("hidden")
        assert str(timer_label.render()) == ""

        # Start job
        status.set_job_running(True)
        await pilot.pause()
        assert not progress.has_class("hidden")
        await wait_for_timer("Elapsed: 00:00")

        # Advance 65s
        current_time += 65.0
        await wait_for_timer("Elapsed: 01:05")

        # Finish job
        status.set_job_running(False)
        await pilot.pause()
        assert progress.has_class("hidden")
