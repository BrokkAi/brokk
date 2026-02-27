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

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    # Submit first prompt
    prompt = "Implement a new login system\nwith oauth support"
    await app._run_job(prompt)

    # Wait for background worker
    await asyncio.sleep(0.1)

    # Verify rename called with derived name
    stub.rename_session.assert_called_once_with("test-session-123", "Implement a new login system")
    assert "test-session-123" in app._renamed_sessions


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

    await app._run_job("Another prompt")
    await asyncio.sleep(0.1)

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
