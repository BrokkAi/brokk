import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


class AutonameStubExecutor:
    def __init__(self):
        self.session_id = "test-session-123"
        self.workspace_dir = MagicMock()
        self.list_sessions = AsyncMock(
            return_value={
                "currentSessionId": "test-session-123",
                "sessions": [{"id": "test-session-123", "name": "TUI Session"}],
            }
        )
        self.rename_session = AsyncMock(return_value={"status": "ok"})
        self.submit_job = AsyncMock(return_value="job-1")
        self.stream_events = MagicMock()

        async def empty_gen(*args, **kwargs):
            if False:
                yield {}

        self.stream_events.return_value = empty_gen()


@pytest.mark.asyncio
async def test_auto_rename_on_first_prompt(tmp_path):
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True
    app._auto_rename_eligible_sessions.add("test-session-123")

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    # Submit first prompt
    prompt = "Implement a new login system\nwith oauth support"
    await app._run_job(prompt)

    # Wait for background worker
    for _ in range(10):
        if stub.rename_session.call_count > 0:
            break
        await asyncio.sleep(0.05)

    # Verify rename called with derived name
    stub.rename_session.assert_called_once_with("test-session-123", "Implement a new login system")
    assert "test-session-123" in app._renamed_sessions


@pytest.mark.asyncio
async def test_auto_rename_skipped_after_manual_switch(tmp_path):
    """Switching to an existing session should not trigger auto-rename on first prompt."""
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True
    app._maybe_chat = MagicMock(return_value=MagicMock())

    # Simulate switching to an existing session
    # (We don't add it to _auto_rename_eligible_sessions)
    app.executor.session_id = "switched-session"

    # Submit prompt
    await app._run_job("Prompt in switched session")
    await asyncio.sleep(0.2)

    # Should NOT rename
    stub.rename_session.assert_not_called()
    assert "switched-session" not in app._renamed_sessions


@pytest.mark.asyncio
async def test_auto_rename_skips_if_not_default(tmp_path):
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    stub.list_sessions = AsyncMock(
        return_value={
            "currentSessionId": "test-session-123",
            "sessions": [{"id": "test-session-123", "name": "Custom Name"}],
        }
    )
    app.executor = stub
    app._executor_ready = True
    app._auto_rename_eligible_sessions.add("test-session-123")

    await app._run_job("Another prompt")
    await asyncio.sleep(0.2)

    stub.rename_session.assert_not_called()
    assert "test-session-123" in app._renamed_sessions


@pytest.mark.asyncio
async def test_derive_session_name_logic(tmp_path):
    app = BrokkApp(workspace_dir=tmp_path)

    assert app._derive_session_name("Simple prompt") == "Simple prompt"
    assert app._derive_session_name("@lutz fix this") == "fix this"
    assert app._derive_session_name("/ask how does this work?") == "how does this work?"

    long_prompt = "A" * 100
    derived = app._derive_session_name(long_prompt)
    assert len(derived) == 60
    assert derived.endswith("...")


@pytest.mark.asyncio
async def test_concurrent_auto_rename_only_fires_once(tmp_path):
    """Two concurrent _maybe_rename_session calls should only rename once."""
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True
    app._auto_rename_eligible_sessions.add("test-session-123")
    app._maybe_chat = MagicMock(return_value=MagicMock())

    # Fire two concurrent rename attempts
    await asyncio.gather(
        app._maybe_rename_session("First prompt"),
        app._maybe_rename_session("Second prompt"),
    )

    # Only one rename should have happened
    assert stub.rename_session.call_count == 1
    assert "test-session-123" in app._renamed_sessions


@pytest.mark.asyncio
async def test_manual_rename_prevents_auto_rename(tmp_path):
    """After a manual _rename_session, auto-rename should be skipped."""
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True
    app._auto_rename_eligible_sessions.add("test-session-123")
    app._maybe_chat = MagicMock(return_value=MagicMock())

    # Manual rename first
    await app._rename_session("test-session-123", "My Custom Name")

    # Now auto-rename should be skipped
    await app._maybe_rename_session("Some prompt")

    # rename_session called only once (the manual one)
    stub.rename_session.assert_called_once_with("test-session-123", "My Custom Name")


@pytest.mark.asyncio
async def test_auto_rename_delayed_until_switch_complete(tmp_path):
    """Auto-rename should not trigger for a queued prompt until the session switch finishes."""
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    # 1. Start a slow switch
    switch_event = asyncio.Event()

    async def slow_switch(session_id: str):
        await switch_event.wait()
        stub.session_id = session_id
        return {}

    stub.switch_session = AsyncMock(side_effect=slow_switch)
    stub.get_conversation = AsyncMock(return_value={"entries": []})

    # Target session is eligible for auto-rename
    app._auto_rename_eligible_sessions.add("target-sid")

    # Ensure stub reports target-sid with default name so _maybe_rename_session proceeds
    stub.list_sessions.return_value = {
        "currentSessionId": "target-sid",
        "sessions": [{"id": "target-sid", "name": "TUI Session"}],
    }

    switch_task = asyncio.create_task(app._switch_to_session("target-sid"))
    await asyncio.sleep(0.01)
    assert app.session_switch_in_progress is True

    # 2. Submit prompt during switch
    msg = MagicMock()
    msg.text = "First prompt in new session"
    app.on_chat_panel_submitted(msg)

    assert app._pending_switch_prompt == ("target-sid", "First prompt in new session")

    # 3. Verify no rename or job submission yet
    stub.rename_session.assert_not_called()
    stub.submit_job.assert_not_called()

    # 4. Complete switch
    switch_event.set()
    await switch_task

    # 5. Wait for the queued job to start and trigger rename
    start_wait = asyncio.get_event_loop().time()
    while stub.submit_job.call_count == 0 and (asyncio.get_event_loop().time() - start_wait < 1.0):
        await asyncio.sleep(0.01)

    stub.submit_job.assert_called_once()

    # The rename happens in a separate worker spawned by _run_job
    start_wait = asyncio.get_event_loop().time()
    while stub.rename_session.call_count == 0 and (
        asyncio.get_event_loop().time() - start_wait < 2.0
    ):
        await asyncio.sleep(0.05)

    stub.rename_session.assert_called_once_with("target-sid", "First prompt in new session")
