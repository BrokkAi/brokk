import pytest
from unittest.mock import AsyncMock, MagicMock
from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_handle_command_login_openai_success():
    """Verify that /login-openai calls the executor and posts a success message."""
    app = BrokkApp()
    app.executor = MagicMock()
    app.executor.start_openai_oauth = AsyncMock(return_value={"status": "started"})
    app._executor_ready = True

    chat_mock = MagicMock()
    app.query_one = MagicMock(return_value=chat_mock)

    # Trigger command
    app._handle_command("/login-openai")

    # We need to wait for the worker to complete since _handle_command uses run_worker
    # In a real TUI test we'd use app.run_test, but for unit testing the dispatch:
    await app._login_openai()

    app.executor.start_openai_oauth.assert_called_once()
    chat_mock.add_system_message.assert_called_with(
        "Opening browser for OpenAI authorization. After completing the login flow, "
        "Codex-gated models will become available."
    )


@pytest.mark.asyncio
async def test_handle_command_login_openai_not_ready():
    """Verify that /login-openai shows a warning if the executor is not ready."""
    app = BrokkApp()
    app.executor = MagicMock()
    app._executor_ready = False

    chat_mock = MagicMock()
    app.query_one = MagicMock(return_value=chat_mock)

    await app._login_openai()

    app.executor.start_openai_oauth.assert_not_called()
    chat_mock.add_system_message.assert_called_with(
        "Brokk executor is not yet ready. Please wait a moment and try again.", level="WARNING"
    )


@pytest.mark.asyncio
async def test_handle_command_login_openai_error():
    """Verify error handling when the OAuth call fails."""
    app = BrokkApp()
    app.executor = MagicMock()
    app.executor.start_openai_oauth = AsyncMock(side_effect=RuntimeError("Connection refused"))
    app._executor_ready = True

    chat_mock = MagicMock()
    app.query_one = MagicMock(return_value=chat_mock)

    await app._login_openai()

    chat_mock.add_system_message.assert_called_with(
        "Failed to start OpenAI login: Connection refused", level="ERROR"
    )


def test_login_openai_present_in_commands():
    """Ensure /login-openai is in the command catalog."""
    cmds = BrokkApp.get_slash_commands()
    login_cmd = next((c for c in cmds if c["command"] == "/login-openai"), None)
    assert login_cmd is not None
    assert "OpenAI" in login_cmd["description"]
