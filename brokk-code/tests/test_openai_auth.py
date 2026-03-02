from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


class MockExecutor(MagicMock):
    """Mock executor that tracks OAuth calls."""

    def __init__(self, *args, **kwargs):
        super().__init__(spec=ExecutorManager, *args, **kwargs)
        self.start_openai_oauth = AsyncMock(return_value={"status": "started"})
        self.get_openai_oauth_status = AsyncMock(return_value={"connected": False})
        self.workspace_dir = MagicMock()
        self.session_id = "test-session"


@pytest.mark.asyncio
async def test_login_openai_not_ready():
    """Test that /login-openai shows warning when executor is not ready."""
    executor = MockExecutor()

    # Create app with minimal initialization by patching heavy initialization
    with patch.object(BrokkApp, "__init__", lambda self, **kwargs: None):
        app = BrokkApp()
        app.executor = executor
        app._executor_ready = False

        # Mock _maybe_chat to return a mock chat panel
        mock_chat = MagicMock()
        app._maybe_chat = MagicMock(return_value=mock_chat)

        # Call _login_openai
        await app._login_openai()

        # Verify executor was NOT called
        executor.start_openai_oauth.assert_not_called()

        # Verify warning message was shown
        mock_chat.add_system_message.assert_called_once()
        call_args = mock_chat.add_system_message.call_args
        assert "not yet ready" in call_args[0][0].lower() or "not yet ready" in str(call_args)
        assert call_args[1].get("level") == "WARNING"


@pytest.mark.asyncio
async def test_login_openai_success():
    """Test that /login-openai calls executor and shows success message when ready."""
    executor = MockExecutor()

    with patch.object(BrokkApp, "__init__", lambda self, **kwargs: None):
        app = BrokkApp()
        app.executor = executor
        app._executor_ready = True

        mock_chat = MagicMock()
        app._maybe_chat = MagicMock(return_value=mock_chat)

        await app._login_openai()

        # Verify executor WAS called exactly once
        executor.start_openai_oauth.assert_called_once()

        # Verify success message was shown (no level means INFO)
        mock_chat.add_system_message.assert_called()
        call_args = mock_chat.add_system_message.call_args
        assert "browser" in call_args[0][0].lower() or "openai" in call_args[0][0].lower()


@pytest.mark.asyncio
async def test_login_openai_error():
    """Test that /login-openai handles executor errors gracefully."""
    executor = MockExecutor()
    executor.start_openai_oauth.side_effect = Exception("Connection failed")

    with patch.object(BrokkApp, "__init__", lambda self, **kwargs: None):
        app = BrokkApp()
        app.executor = executor
        app._executor_ready = True

        mock_chat = MagicMock()
        app._maybe_chat = MagicMock(return_value=mock_chat)

        await app._login_openai()

        # Verify error message was shown
        mock_chat.add_system_message.assert_called()
        call_args = mock_chat.add_system_message.call_args
        assert "failed" in call_args[0][0].lower() or "error" in call_args[0][0].lower()
        assert call_args[1].get("level") == "ERROR"


@pytest.mark.asyncio
async def test_login_openai_no_chat_panel():
    """Test that /login-openai handles missing chat panel gracefully."""
    executor = MockExecutor()

    with patch.object(BrokkApp, "__init__", lambda self, **kwargs: None):
        app = BrokkApp()
        app.executor = executor
        app._executor_ready = True

        # _maybe_chat returns None (no chat panel mounted)
        app._maybe_chat = MagicMock(return_value=None)

        # Should not raise, just return early
        await app._login_openai()

        # Executor should NOT be called since we return early when chat is None
        executor.start_openai_oauth.assert_not_called()
