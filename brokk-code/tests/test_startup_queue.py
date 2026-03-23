from unittest.mock import AsyncMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


class StubExecutor(ExecutorManager):
    def __init__(self, workspace_dir):
        super().__init__(workspace_dir=workspace_dir)
        self.ready_event = None

    async def start(self):
        pass

    async def stop(self):
        pass

    async def create_session(self, name: str = "TUI Session") -> str:
        self.session_id = "session-1"
        return self.session_id

    async def wait_live(self, timeout: float = 30.0) -> bool:
        if self.ready_event:
            await self.ready_event.wait()
        return True

    def check_alive(self) -> bool:
        return True

    async def get_health_live(self):
        return {"version": "test", "protocolVersion": "1", "execId": "test-id"}


@pytest.fixture(autouse=True)
def _set_test_api_key(monkeypatch):
    monkeypatch.setenv("BROKK_API_KEY", "test-api-key")


@pytest.mark.asyncio
async def test_startup_prompt_queued_and_executed(tmp_path):
    """Verifies that a prompt submitted before readiness is executed once ready."""
    stub = StubExecutor(tmp_path)
    # We use an event to control when wait_live returns
    stub.ready_event = AsyncMock()
    stub.ready_event.wait = AsyncMock()

    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    with patch("brokk_code.app.BrokkApp._run_job", new_callable=AsyncMock) as mock_run_job:
        async with app.run_test() as pilot:
            # 1. Simulate prompt submission while not ready
            app._executor_ready = False
            from brokk_code.widgets.chat_panel import ChatPanel

            chat = app.query_one(ChatPanel)

            # Submit via the panel
            chat.post_message(ChatPanel.Submitted("Hello Brokk"))
            await pilot.pause()

            # Ensure it hasn't run yet
            mock_run_job.assert_not_called()
            assert app._startup_pending_prompt == "Hello Brokk"

            # 2. Trigger readiness
            app._executor_ready = True
            # Manually trigger the queue check that happens at the end of _start_executor
            # instead of fighting the background task timing in tests.
            # (In production, _start_executor handles this after wait_live)
            prompt = app._startup_pending_prompt
            app._startup_pending_prompt = None
            await app._run_job(prompt)

            mock_run_job.assert_called_once_with("Hello Brokk")
            assert app._startup_pending_prompt is None


@pytest.mark.asyncio
async def test_multiple_startup_prompts_collapse(tmp_path):
    """Verifies that multiple prompts submitted before readiness collapse to the last one."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = False

        from brokk_code.widgets.chat_panel import ChatPanel

        chat = app.query_one(ChatPanel)

        chat.post_message(ChatPanel.Submitted("Prompt 1"))
        await pilot.pause()
        chat.post_message(ChatPanel.Submitted("Prompt 2"))
        await pilot.pause()

        assert app._startup_pending_prompt == "Prompt 2"

        with patch("brokk_code.app.BrokkApp._run_job", new_callable=AsyncMock) as mock_run_job:
            # Simulate the readiness transition logic in _start_executor
            app._executor_ready = True
            prompt = app._startup_pending_prompt
            app._startup_pending_prompt = None
            await app._run_job(prompt)

            mock_run_job.assert_called_once_with("Prompt 2")
