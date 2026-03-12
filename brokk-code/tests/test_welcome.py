from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_welcome_message_shown_on_startup(tmp_path: Path):
    """Verify welcome message appears on every startup."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_ready = AsyncMock(return_value=True)
    mock_executor.check_alive = MagicMock(return_value=True)
    mock_executor.get_health_live = AsyncMock(return_value={})
    mock_executor.create_session = AsyncMock()
    mock_executor.session_id = "test-session"
    mock_executor.stop = AsyncMock()
    mock_executor.get_context = AsyncMock(return_value={"usedTokens": 0})
    mock_executor.get_tasklist = AsyncMock(return_value={"tasks": []})

    for history in ([], ["previous prompt"]):
        with patch("brokk_code.app.load_history", return_value=history):
            app = BrokkApp(executor=mock_executor)
            async with app.run_test() as pilot:
                await pilot.pause()

                chat_log = app.query_one("#chat-log")
                content = "".join(str(line) for line in chat_log.lines)
                assert "Welcome to Brokk" in content, f"Expected welcome with history={history!r}"
                assert "Context Engineering" in content


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
    mock_executor.wait_ready = AsyncMock(return_value=True)

    with patch(
        "brokk_code.app.BrokkApp._fetch_latest_pypi_version", new_callable=AsyncMock
    ) as mock_fetch:
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

    # Mock _maybe_chat to return a mock that raises NoMatches
    mock_chat = MagicMock()
    mock_chat.update_welcome.side_effect = NoMatches("No nodes match '#chat-log'")
    mock_chat.add_welcome.side_effect = NoMatches("No nodes match '#chat-log'")

    with patch.object(app, "_maybe_chat", return_value=mock_chat):
        # Should not raise
        app._show_welcome_message(refresh=False)
        app._show_welcome_message(refresh=True)

    mock_chat.add_welcome.assert_called_once()
    mock_chat.update_welcome.assert_called_once()
