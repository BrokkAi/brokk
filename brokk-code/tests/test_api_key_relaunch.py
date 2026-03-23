import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorError


class RelaunchFakeExecutor:
    def __init__(self, workspace_dir: Path):
        self.workspace_dir = workspace_dir
        self.brokk_api_key = "initial-key"
        self.session_id = "sess-1"
        self.stop_calls = 0
        self.start_calls = 0
        self.download_calls = 0
        self.import_calls = 0
        self._alive = True
        self.fail_start = False

    async def stop(self):
        self.stop_calls += 1
        self._alive = False

    async def start(self):
        self.start_calls += 1
        if self.fail_start:
            raise ExecutorError("Simulated start failure")
        self._alive = True

    def check_alive(self):
        return self._alive

    async def wait_live(self, timeout=30.0):
        return self._alive

    async def download_session_zip(self, session_id):
        self.download_calls += 1
        return b"fake-zip-data"

    async def import_session_zip(self, zip_bytes, session_id=None):
        self.import_calls += 1
        return session_id or "new-sess"

    async def get_health_live(self):
        return {"version": "1.0", "protocolVersion": "1", "execId": "test-exec"}

    async def get_context(self):
        return {"totalCost": 0.0, "usedTokens": 0}


@pytest.mark.asyncio
async def test_api_key_update_triggers_relaunch(tmp_path):
    """Verify that updating the API key triggers a stop/start cycle with session restoration."""
    fake = RelaunchFakeExecutor(tmp_path)
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)
    app._executor_ready = True

    # Mock chat to avoid UI dependency issues
    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    # We trigger the internal relaunch logic directly as it would be from the modal callback
    await app._relaunch_executor()

    # Assertions
    assert fake.stop_calls == 1
    assert fake.start_calls == 1
    assert fake.download_calls == 1
    assert fake.import_calls == 1
    assert app._executor_ready is True

    # Verify success message shown
    calls = [args[0] for args, kwargs in mock_chat.add_system_message.call_args_list]
    assert any("relaunched successfully" in m for m in calls)


@pytest.mark.asyncio
async def test_relaunch_propagates_new_key(tmp_path, monkeypatch):
    """Verify the new key is set on the executor manager before start is called."""
    fake = RelaunchFakeExecutor(tmp_path)
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)

    # Mock settings writing
    monkeypatch.setattr("brokk_code.app.write_brokk_api_key", lambda k: None)

    # Use a spy to check key at start time
    original_start = fake.start
    captured_key_at_start = None

    async def start_spy():
        nonlocal captured_key_at_start
        captured_key_at_start = fake.brokk_api_key
        await original_start()

    fake.start = start_spy

    # Simulate modal submission callback
    # Finding the callback in /api-key handler
    async def simulate_modal_submit(key):
        fake.brokk_api_key = key  # This is what the app does
        await app._relaunch_executor()

    await simulate_modal_submit("sk-new-key")

    assert captured_key_at_start == "sk-new-key"


@pytest.mark.asyncio
async def test_relaunch_failure_leaves_app_usable(tmp_path):
    """Verify that if relaunch fails, the error is surfaced but app state allows retrying."""
    fake = RelaunchFakeExecutor(tmp_path)
    fake.fail_start = True
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    await app._relaunch_executor()

    # Verify error was reported
    calls = [args[0] for args, kwargs in mock_chat.add_system_message.call_args_list]
    assert any("Failed to relaunch executor" in m for m in calls)

    # App state should reflect that job-submission is no longer blocked even though executor is dead
    assert app.job_in_progress is False
    assert app._executor_ready is False


@pytest.mark.asyncio
async def test_relaunch_failure_after_start_leaves_app_not_ready(tmp_path):
    """Verify that if relaunch fails in a post-start step (like refresh), readiness is False."""
    fake = RelaunchFakeExecutor(tmp_path)
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    # Simulate a failure during context refresh
    app._refresh_context_panel = AsyncMock(side_effect=Exception("Refresh failed"))

    await app._relaunch_executor()

    # Verify failure message surfaced
    calls = [args[0] for args, kwargs in mock_chat.add_system_message.call_args_list]
    assert any("Failed to relaunch executor" in m for m in calls)
    assert any("Refresh failed" in m for m in calls)

    # Crucial assertions:
    assert app._executor_ready is False, "App should not be ready if post-start steps fail"
    assert app.job_in_progress is False, "App should remain usable for retries"


@pytest.mark.asyncio
async def test_relaunch_sequencing_restores_before_ready_poll(tmp_path):
    """Verify that session restoration happens BEFORE wait_live is called.
    This keeps restored state available before we resume traffic.
    """
    fake = RelaunchFakeExecutor(tmp_path)
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)
    app._executor_ready = True

    # Track call order
    call_log = []

    original_start = fake.start
    original_import = fake.import_session_zip
    original_wait = fake.wait_live

    async def start_spy():
        call_log.append("start")
        await original_start()

    async def import_spy(zip_bytes, session_id=None):
        call_log.append("import")
        return await original_import(zip_bytes, session_id)

    async def wait_spy(timeout=30.0):
        call_log.append("wait_live")
        return await original_wait(timeout)

    fake.start = start_spy
    fake.import_session_zip = import_spy
    fake.wait_live = wait_spy

    # Mock chat
    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    await app._relaunch_executor()

    # The expected sequence for a healthy relaunch with existing session is:
    # 1. start
    # 2. import (or create_session)
    # 3. wait_live
    expected_sequence = ["start", "import", "wait_live"]

    # Filter call_log to just these three so unrelated calls don't break the test
    relevant_calls = [c for c in call_log if c in expected_sequence]
    assert relevant_calls == expected_sequence, f"Incorrect relaunch sequence: {relevant_calls}"


@pytest.mark.asyncio
async def test_concurrent_relaunch_short_circuits(tmp_path):
    """Verify that overlapping relaunch calls short-circuit to avoid interleaving."""
    fake = RelaunchFakeExecutor(tmp_path)
    app = BrokkApp(executor=fake, workspace_dir=tmp_path)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    # Delay the first relaunch to ensure the second one hits the lock
    original_stop = fake.stop

    async def delayed_stop():
        await asyncio.sleep(0.1)
        await original_stop()

    fake.stop = delayed_stop

    # Trigger two relaunches concurrently
    await asyncio.gather(
        app._relaunch_executor(),
        app._relaunch_executor(),
    )

    # Assertions
    # Only one relaunch should have actually run
    assert fake.stop_calls == 1
    assert fake.start_calls == 1

    # Verify short-circuit message was shown
    calls = [args[0] for args, kwargs in mock_chat.add_system_message.call_args_list]
    assert any("already in progress" in m for m in calls)
