import asyncio
from typing import Any, AsyncIterator, Dict, List, Optional
from pathlib import Path

import pytest
from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager
from brokk_code.widgets.chat_panel import ChatPanel


class StubExecutor(ExecutorManager):
    def __init__(self):
        super().__init__(workspace_dir=Path("."))
        self.calls: List[Dict[str, Any]] = []
        self.submit_count = 0
        # Event to control when the stream finishes
        self.release_stream = asyncio.Event()
        # Event to notify test that stream_events has started
        self.stream_started = asyncio.Event()

    async def start(self): pass
    async def stop(self): pass
    async def create_session(self, name: str = "TUI Session") -> str: return "session-1"
    async def wait_ready(self, timeout: float = 30.0) -> bool: return True
    def check_alive(self) -> bool: return True
    async def get_context(self) -> Dict[str, Any]: return {}
    async def get_tasklist(self) -> Dict[str, Any]: return {}

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
        await self.release_stream.wait()
        self.release_stream.clear()
        
        yield {"type": "LLM_TOKEN", "data": {"token": "done", "isTerminal": True}}


@pytest.mark.asyncio
async def test_cancel_and_resubmit_flow():
    """
    Verify that submitting a new prompt while a job is running:
    1. Cancels the current job.
    2. Submits the new job only after the first one finishes.
    3. Handles multiple rapid submissions by only keeping the latest.
    """
    stub = StubExecutor()
    app = BrokkApp(executor=stub)

    async with app.run_test() as pilot:
        # 1. Submit first job
        await pilot.click("#chat-input")
        await pilot.press_ascii("first")
        await pilot.press("enter")
        
        # Wait for job to start streaming
        await asyncio.wait_for(stub.stream_started.wait(), timeout=2.0)
        stub.stream_started.clear()
        
        assert app.job_in_progress is True
        assert app.current_job_id == "job-1"

        # 2. Submit second and third job quickly while job-1 is "running"
        # The first 'enter' for 'second' triggers cancel_job('job-1') and sets _pending_prompt='second'
        # The second 'enter' for 'third' triggers cancel_job('job-1') again and updates _pending_prompt='third'
        await pilot.press_ascii("second")
        await pilot.press("enter")
        await pilot.press_ascii("third")
        await pilot.press("enter")

        # Now we allow the first job loop to terminate
        # app._run_job("first") will finish its finally block and see _pending_prompt="third"
        stub.release_stream.set()

        # Wait for the second job (the one for "third") to start
        await asyncio.wait_for(stub.stream_started.wait(), timeout=2.0)
        
        # Allow the second job to finish too so app settles
        stub.release_stream.set()
        await pilot.pause()

        # Assertions on ordering and content
        actions = [c["type"] for c in stub.calls]
        # We expect: 
        # submit(job-1)
        # cancel(job-1) <- from 'second'
        # cancel(job-1) <- from 'third'
        # submit(job-2) <- which actually contains the "third" prompt text
        
        assert actions == ["submit", "cancel", "cancel", "submit"]
        
        submits = [c for c in stub.calls if c["type"] == "submit"]
        cancels = [c for c in stub.calls if c["type"] == "cancel"]
        
        assert submits[0]["input"] == "first"
        assert cancels[0]["job_id"] == "job-1"
        assert cancels[1]["job_id"] == "job-1"
        assert submits[1]["input"] == "third"
        assert len(submits) == 2  # "second" must have been dropped
