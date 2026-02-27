from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_sessions_command_in_catalog():
    """Verify /sessions appears in the slash command list."""
    commands = BrokkApp.get_slash_commands()
    assert any(c["command"] == "/sessions" for c in commands)


@pytest.mark.asyncio
async def test_show_sessions_flow(tmp_path):
    """Verify _show_sessions logic with a stub executor."""
    # Setup stub app and executor
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app.executor.workspace_dir = tmp_path
    app._executor_ready = True

    # Mock list_sessions response
    sessions_data = {
        "sessions": [{"id": "s1", "name": "Session 1"}, {"id": "s2", "name": "Session 2"}],
        "currentSessionId": "s1",
    }
    app.executor.list_sessions = AsyncMock(return_value=sessions_data)

    # Mock chat and screen pushing
    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)
    app.push_screen = MagicMock()

    await app._show_sessions()

    # Verify interaction
    app.executor.list_sessions.assert_called_once()
    app.push_screen.assert_called_once()

    # Extract the callback passed to push_screen
    args, kwargs = app.push_screen.call_args
    callback = args[1]

    # Simulate selecting session s2
    app.run_worker = MagicMock()
    callback("s2")

    # Verify switch worker was triggered
    app.run_worker.assert_called_once()
