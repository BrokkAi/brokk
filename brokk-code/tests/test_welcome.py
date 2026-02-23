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
    from brokk_code.welcome import build_welcome_message

    mock_commands = [
        {"command": "/context", "description": "test"},
        {"command": "/task", "description": "test"},
        {"command": "/help", "description": "test"},
    ]

    msg = build_welcome_message(mock_commands)

    assert "Welcome to Brokk" in msg
    assert "context engineering" in msg.lower()
    assert "https://brokk.ai/" in msg
    assert "/context" in msg
    assert "/task" in msg
    assert "/help" in msg


def test_get_braille_icon_contains_braille():
    """Verify the icon helper returns characters in the Braille Unicode block."""
    from brokk_code.welcome import get_braille_icon

    icon = get_braille_icon()
    assert isinstance(icon, str)
    assert len(icon) > 0
    # Check for at least one Braille character (U+2800 to U+28FF)
    has_braille = any("\u2800" <= char <= "\u28ff" for char in icon)
    assert has_braille, "Icon should contain Unicode Braille characters"
