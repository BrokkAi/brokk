import asyncio
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


class StubExecutor(ExecutorManager):
    def __init__(self, auto_release: bool = False):
        super().__init__(workspace_dir=Path("."))
        self.calls: List[Dict[str, Any]] = []
        self.submit_count = 0
        # Event to control when the stream finishes
        self.release_stream = asyncio.Event()
        # Event to notify test that stream_events has started
        self.stream_started = asyncio.Event()
        self.auto_release = auto_release

    async def start(self):
        pass

    async def stop(self):
        pass

    async def create_session(self, name: str = "TUI Session") -> str:
        return "session-1"

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        return True

    def check_alive(self) -> bool:
        return True

    async def get_context(self) -> Dict[str, Any]:
        return {}

    async def get_tasklist(self) -> Dict[str, Any]:
        return {}

    async def submit_job(self, task_input: str, *args, **kwargs) -> str:
        self.submit_count += 1
        job_id = f"job-{self.submit_count}"
        self.calls.append({"type": "submit", "job_id": job_id, "input": task_input})
        return job_id

    async def cancel_job(self, job_id: str):
        self.calls.append({"type": "cancel", "job_id": job_id})
        # In this stub, cancellation just allows the stream to potentially end
        self.release_stream.set()

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        self.stream_started.set()
        if self.auto_release and not self.release_stream.is_set():
            self.release_stream.set()
        await self.release_stream.wait()
        self.release_stream.clear()

        yield {"type": "LLM_TOKEN", "data": {"token": "done", "isTerminal": True}}


async def type_text(pilot: Any, text: str) -> None:
    for ch in text:
        await pilot.press(ch)


@pytest.mark.asyncio
async def test_multiline_paste_and_submit():
    """
    Verify that multiline text (like a paste) is submitted with newlines intact.
    """
    stub = StubExecutor(auto_release=True)
    app = BrokkApp(executor=stub)

    async with app.run_test() as pilot:
        chat_input = app.query_one("#chat-input")
        await pilot.click("#chat-input")

        # 1. Test Shift+Enter behavior
        await type_text(pilot, "line1")
        await pilot.press("shift+enter")
        await type_text(pilot, "line2")

        # Verify UI state before submit
        assert chat_input.text == "line1\nline2"

        await pilot.press("enter")
        await pilot.pause()

        # 2. Test "Paste" behavior (direct text setting)
        multiline_paste = "first line\nsecond line\nthird line"
        chat_input.text = multiline_paste
        await pilot.press("enter")
        await pilot.pause()

        # Verify executor calls
        submits = [c for c in stub.calls if c["type"] == "submit"]
        assert len(submits) == 2
        assert submits[0]["input"] == "line1\nline2"
        assert submits[1]["input"] == multiline_paste


@pytest.mark.asyncio
async def test_large_paste_submits_as_job():
    """
    Verify that large inputs submit normally as jobs.
    """
    stub = StubExecutor(auto_release=True)
    app = BrokkApp(executor=stub)

    async with app.run_test() as pilot:
        chat_input = app.query_one("#chat-input")
        await pilot.click("#chat-input")

        # Create a large string (> 2000 chars)
        large_text = "This is a large paste.\n" * 100
        assert len(large_text) > 2000

        chat_input.text = large_text
        await pilot.press("enter")
        await pilot.pause()

        # Verify it was submitted as a job, not added to context
        submits = [c for c in stub.calls if c["type"] == "submit"]
        assert len(submits) == 1
        assert submits[0]["input"] == large_text

        # Verify it was submitted as a job
        assert all(c["type"] == "submit" for c in stub.calls)
