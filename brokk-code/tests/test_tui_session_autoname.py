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
async def test_client_no_longer_explicitly_renames_on_job(tmp_path):
    """The client should not call rename_session; the executor handles it."""
    app = BrokkApp(workspace_dir=tmp_path)
    stub = AutonameStubExecutor()
    app.executor = stub
    app._executor_ready = True

    mock_chat = MagicMock()
    mock_chat.get_commands_running.return_value = 0
    app._maybe_chat = MagicMock(return_value=mock_chat)

    # Submit a prompt that would previously trigger a client-side rename
    # (session name is "TUI Session")
    await app._run_job("Implement a new login system")

    # Wait for worker
    await asyncio.sleep(0.1)

    # Verify client did NOT emit a rename request
    stub.rename_session.assert_not_called()
    # Verify client DID emit the job
    stub.submit_job.assert_called_once()
