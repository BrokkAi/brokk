import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApiKeyModalScreen, BrokkApp


@pytest.mark.asyncio
async def test_slash_commands_catalog():
    commands = BrokkApp.get_slash_commands()
    cmd_names = [c["command"] for c in commands]

    assert "/login" in cmd_names
    assert "/logout" in cmd_names
    assert "/api-key" not in cmd_names


@pytest.mark.asyncio
async def test_handle_login_command_opens_modal():
    app = BrokkApp()
    app.push_screen = MagicMock()
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        # Verify /login works
        app._handle_command("/login")
        app.push_screen.assert_called_once()
        args, _ = app.push_screen.call_args
        assert isinstance(args[0], BrokkApiKeyModalScreen)

        # Verify /api-key alias works
        app.push_screen.reset_mock()
        app._handle_command("/api-key")
        app.push_screen.assert_called_once()
        args, _ = app.push_screen.call_args
        assert isinstance(args[0], BrokkApiKeyModalScreen)


@pytest.mark.asyncio
async def test_handle_login_command_with_args_shows_usage():
    app = BrokkApp()
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        app._handle_command("/login some-arg")
        chat_panel.add_system_message.assert_called_with(
            "Usage: /login (opens API key prompt)", level="WARNING"
        )


@pytest.mark.asyncio
async def test_handle_logout_command_logic():
    app = BrokkApp()
    app.executor = MagicMock()
    app.run_worker = MagicMock(side_effect=lambda coro: asyncio.create_task(coro))
    app._relaunch_executor = AsyncMock()
    chat_panel = MagicMock()

    with (
        patch.object(app, "query_one", return_value=chat_panel),
        patch("brokk_code.app.write_brokk_properties") as mock_write,
    ):
        # Trigger /logout
        app._handle_command("/logout")

        # Wait for the worker task (do_logout) to finish
        await asyncio.sleep(0.1)

        mock_write.assert_called_once_with({"brokkApiKey": None})
        assert app.executor.brokk_api_key is None
        app._relaunch_executor.assert_called_once()
        chat_panel.add_system_message.assert_any_call(
            "Logged out. Clearing API key and relaunching..."
        )


@pytest.mark.asyncio
async def test_handle_logout_command_with_args_shows_usage():
    app = BrokkApp()
    app.run_worker = MagicMock()
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        app._handle_command("/logout extra-arg")
        chat_panel.add_system_message.assert_called_with("Usage: /logout", level="WARNING")
        app.run_worker.assert_not_called()
