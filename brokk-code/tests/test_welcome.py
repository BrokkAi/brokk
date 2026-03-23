from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_welcome_message_shown_on_first_run(tmp_path: Path):
    """Verify welcome message appears when no API key is present (first run)."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)
    mock_executor.check_alive = MagicMock(return_value=True)
    mock_executor.get_health_live = AsyncMock(return_value={})
    mock_executor.create_session = AsyncMock()
    mock_executor.session_id = "test-session"
    mock_executor.stop = AsyncMock()
    mock_executor.get_context = AsyncMock(return_value={"usedTokens": 0})
    mock_executor.get_tasklist = AsyncMock(return_value={"tasks": []})

    with patch("brokk_code.app.load_history", return_value=[]), \
         patch("brokk_code.app.Settings.get_brokk_api_key", return_value=None):
        app = BrokkApp(executor=mock_executor)
        async with app.run_test() as pilot:
            await pilot.pause()

            chat_log = app.query_one("#chat-log")
            content = "".join(str(line) for line in chat_log.lines)
            assert "Welcome to Brokk" in content
            assert "Context Engineering" in content


@pytest.mark.asyncio
async def test_welcome_message_not_shown_for_returning_user(tmp_path: Path):
    """Verify welcome message is suppressed for returning users with key and history."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)
    mock_executor.check_alive = MagicMock(return_value=True)
    mock_executor.get_health_live = AsyncMock(return_value={})
    mock_executor.create_session = AsyncMock()
    mock_executor.session_id = "test-session"
    mock_executor.stop = AsyncMock()
    mock_executor.get_context = AsyncMock(return_value={"usedTokens": 0})
    mock_executor.get_tasklist = AsyncMock(return_value={"tasks": []})

    with patch("brokk_code.app.load_history", return_value=["prior command"]), \
         patch("brokk_code.app.Settings.get_brokk_api_key", return_value="sk-valid"):
        app = BrokkApp(executor=mock_executor)
        async with app.run_test() as pilot:
            await pilot.pause()

            chat_log = app.query_one("#chat-log")
            content = "".join(str(line) for line in chat_log.lines)
            assert "Welcome to Brokk" not in content


def test_build_welcome_message_content():
    """Verify the welcome message contains expected branded content and commands."""
    from brokk_code import __version__
    from brokk_code.welcome import build_welcome_message

    mock_commands = [
        {"command": "/context", "description": "test"},
        {"command": "/task", "description": "test"},
    ]

    msg = build_welcome_message(mock_commands)

    assert "Welcome to Brokk" in msg
    assert f"v{__version__}" in msg
    assert "context engineering" in msg.lower()
    assert "https://brokk.ai/" in msg
    assert "/context" in msg
    assert "/task" in msg


def test_get_braille_icon_contains_braille():
    """Verify the icon helper returns characters in the Braille Unicode block."""
    from brokk_code.welcome import get_braille_icon

    icon = get_braille_icon()
    assert isinstance(icon, str)
    assert len(icon) > 0
    # Check for at least one Braille character (U+2800 to U+28FF)
    has_braille = any("\u2800" <= char <= "\u28ff" for char in icon)
    assert has_braille, "Icon should contain Unicode Braille characters"


def test_build_welcome_message_with_pypi_version():
    """Verify welcome message includes latest version notice when provided."""
    from brokk_code import __version__
    from brokk_code.welcome import build_welcome_message

    # Same version - no extra notice
    msg = build_welcome_message([], latest_pypi_version=__version__)
    assert f"Welcome to Brokk v{__version__}" in msg
    assert "Latest:" not in msg

    # Newer version available
    msg = build_welcome_message([], latest_pypi_version="99.9.9")
    assert f"Welcome to Brokk v{__version__} (Latest: 99.9.9)" in msg


@pytest.mark.asyncio
async def test_welcome_message_updates_after_pypi_fetch(tmp_path: Path):
    """Verify the app fetches PyPI version and updates the welcome message."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)

    with patch(
        "brokk_code.app.BrokkApp._fetch_latest_pypi_version", new_callable=AsyncMock
    ) as mock_fetch, \
         patch("brokk_code.app.Settings.get_brokk_api_key", return_value=None):
        mock_fetch.return_value = "1.2.3"
        app = BrokkApp(executor=mock_executor)

        async with app.run_test() as pilot:
            await pilot.pause()
            # Wait for background check_for_updates worker
            for _ in range(10):
                chat_log = app.query_one("#chat-log")
                content = "".join(str(line) for line in chat_log.lines)
                if "(Latest: 1.2.3)" in content:
                    break
                await pilot.pause(0.1)

            assert "(Latest: 1.2.3)" in content


@pytest.mark.asyncio
async def test_show_welcome_message_handles_nomatches_gracefully(tmp_path: Path):
    """Verify _show_welcome_message does not crash if UI
    widgets are missing (e.g. during unmount)."""
    from textual.css.query import NoMatches

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    app = BrokkApp(executor=mock_executor)

    # Mock _maybe_chat to return a mock that raises NoMatches.
    # We use a side_effect for has_welcome_message to simulate state transition:
    # 1. Non-refresh call: returns False -> triggers add_welcome path.
    # 2. Refresh call (pre-check): returns True -> allows proceeding to update.
    mock_chat = MagicMock()
    mock_chat.has_welcome_message.side_effect = [False, True]
    mock_chat.update_welcome.side_effect = NoMatches("No nodes match log")
    mock_chat.add_welcome.side_effect = NoMatches("No nodes match log")

    with patch.object(app, "_maybe_chat", return_value=mock_chat):
        # Should not raise despite NoMatches side effects
        # Path 1: Initial add (non-refresh)
        app._show_welcome_message(refresh=False)
        # Path 2: Update (refresh)
        app._show_welcome_message(refresh=True)

    # Verify both paths were attempted exactly once
    mock_chat.add_welcome.assert_called_once()
    mock_chat.update_welcome.assert_called_once()
