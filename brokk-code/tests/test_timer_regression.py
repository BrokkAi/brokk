import asyncio
from pathlib import Path
from typing import Any, AsyncIterator, Dict, Optional
from unittest.mock import MagicMock

import pytest
from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager
from brokk_code.widgets.chat_panel import ChatPanel
from textual.widgets import Static


class FakeExecutor(ExecutorManager):
    """A fake executor that succeeds quickly and controls event streaming."""

    def __init__(self):
        # Initialize with dummy paths
        super().__init__(workspace_dir=Path("/tmp"))
        self.stream_delay = 0.5  # How long to wait before finishing the job
        self.events_to_yield = []

    async def start(self):
        pass

    async def stop(self):
        pass

    def check_alive(self) -> bool:
        return True

    async def create_session(self, name: str = "Test Session") -> str:
        self.session_id = "test-session"
        return self.session_id

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        return True

    async def get_health_live(self) -> Dict[str, Any]:
        return {"version": "test", "protocolVersion": "1", "execId": "test-id"}

    async def get_context(self) -> Dict[str, Any]:
        return {"fragments": [], "usedTokens": 0}

    async def get_tasklist(self) -> Dict[str, Any]:
        return {"tasks": []}

    async def submit_job(self, *args, **kwargs) -> str:
        return "test-job-id"

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        """
        Yields no tokens for a window to allow the timer to tick, 
        then yields any programmed events.
        """
        await asyncio.sleep(self.stream_delay)
        for event in self.events_to_yield:
            yield event


@pytest.mark.asyncio
async def test_timer_ticks_during_run_job():
    """
    Regression test: Verify that the elapsed timer increments while a job is 
    running in BrokkApp._run_job, even if no tokens are being received.
    """
    fake_executor = FakeExecutor()
    fake_executor.stream_delay = 0.5  # Job takes 0.5s of real time to finish
    
    app = BrokkApp(executor=fake_executor)
    
    # Mock settings to avoid disk IO
    app.settings = MagicMock()
    app.settings.theme = "textual-dark"
    app.settings.prompt_history_size = 50

    async with app.run_test() as pilot:
        chat_panel = app.query_one("#chat-main", ChatPanel)
        timer_label = chat_panel.query_one("#chat-timer", Static)
        
        # Setup deterministic clock on the panel
        current_time = 1000.0
        def mock_now():
            return current_time
        chat_panel._get_now = mock_now

        # Initial state
        assert str(timer_label.renderable) == ""
        
        # Manually trigger a job via the app's internal method
        # This simulates what happens when a user submits a prompt
        app.run_worker(app._run_job("Hello Timer"))
        
        # Wait for the job to start and set the start time
        # We need a small sleep to let the worker start and call set_job_running
        await asyncio.sleep(0.1)
        
        # Check that timer started
        assert app.job_in_progress is True
        assert str(timer_label.renderable) == "Elapsed: 00:00"

        # Advance deterministic time by 2 seconds
        current_time += 2.0
        
        # Process the Textual interval callback
        await pilot.pause()
        
        # The timer should have updated to 00:02 even though FakeExecutor 
        # is still sleeping (no tokens yielded yet).
        assert str(timer_label.renderable) == "Elapsed: 00:02"

        # Finish the job
        current_time += 1.0
        # We need to wait for the stream_delay (0.5s) in real time since 
        # FakeExecutor uses real asyncio.sleep
        await asyncio.sleep(0.6)
        await pilot.pause()
        
        # Verify cleanup
        assert app.job_in_progress is False
        assert str(timer_label.renderable) == ""
