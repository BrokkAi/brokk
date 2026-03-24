from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_github_status_not_ready():
    app = BrokkApp()
    app._executor_ready = False
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        await app._github_status()
        chat_panel.add_system_message.assert_called_with("Executor is not ready.", level="WARNING")


@pytest.mark.asyncio
async def test_github_status_connected():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()
    app.executor.get_github_oauth_status = AsyncMock(
        return_value={"connected": True, "username": "test-user"}
    )
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        await app._github_status()
        chat_panel.add_system_message.assert_called_with("GitHub: Connected as test-user")


@pytest.mark.asyncio
async def test_github_status_disconnected():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()
    app.executor.get_github_oauth_status = AsyncMock(return_value={"connected": False})
    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel):
        await app._github_status()
        chat_panel.add_system_message.assert_called_with("GitHub: Not connected")


@pytest.mark.asyncio
async def test_logout_github_logic():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()
    app.executor.disconnect_github_oauth = AsyncMock()
    chat_panel = MagicMock()

    with (
        patch.object(app, "query_one", return_value=chat_panel),
        patch("brokk_code.app.write_brokk_properties") as mock_write,
    ):
        await app._logout_github()
        mock_write.assert_called_once_with({"githubToken": None})
        app.executor.disconnect_github_oauth.assert_awaited_once()
        chat_panel.add_system_message.assert_called_with(
            "Logged out of GitHub and cleared stored token."
        )


@pytest.mark.asyncio
async def test_login_github_success_flow():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()

    # start response
    app.executor.start_github_oauth = AsyncMock(
        return_value={
            "verificationUri": "https://github.com/login/device",
            "userCode": "1234-5678",
            "interval": 0.01,
            "expiresIn": 100,
        }
    )

    # poll responses: first IDLE, then SUCCESS
    app.executor.get_github_oauth_status = AsyncMock(
        side_effect=[{"state": "IDLE"}, {"state": "SUCCESS", "username": "gh-user"}]
    )

    chat_panel = MagicMock()

    with (
        patch.object(app, "query_one", return_value=chat_panel),
        patch("asyncio.to_thread", side_effect=lambda f, *args: None),  # mock webbrowser.open
    ):
        await app._login_github()

        # check instructions
        args, _ = chat_panel.add_system_message_markup.call_args_list[0]
        assert "https://github.com/login/device" in args[0]
        assert "1234-5678" in args[0]

        # check final message
        chat_panel.add_system_message.assert_called_with(
            "Successfully connected to GitHub as gh-user.", level="SUCCESS"
        )


@pytest.mark.asyncio
async def test_login_github_failure_flow():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()

    app.executor.start_github_oauth = AsyncMock(
        return_value={
            "verificationUri": "https://uri",
            "userCode": "CODE",
            "interval": 0.01,
            "expiresIn": 100,
        }
    )

    app.executor.get_github_oauth_status = AsyncMock(
        return_value={"state": "DENIED", "message": "User said no"}
    )

    chat_panel = MagicMock()

    with patch.object(app, "query_one", return_value=chat_panel), patch("asyncio.to_thread"):
        await app._login_github()
        chat_panel.add_system_message.assert_called_with(
            "GitHub authentication failed: User said no", level="ERROR"
        )
