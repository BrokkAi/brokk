import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from pathlib import Path

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel

@pytest.mark.asyncio
async def test_welcome_message_shown_on_first_run(tmp_path: Path):
    """Verify welcome message appears when history is empty."""
    # Mock executor to be ready immediately
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

    # Ensure load_history returns empty
    with patch("brokk_code.app.load_history", return_value=[]):
        app = BrokkApp(executor=mock_executor)
        async with app.run_test() as pilot:
            # Wait for executor to be ready and logic to trigger
            await pilot.pause()
            
            chat_log = app.query_one("#chat-log")
            # The welcome message starts with the Braille icon '⣿'
            content = "".join(str(line) for line in chat_log.lines)
            assert "Welcome to Brokk" in content
            assert "Context Engineering" in content

@pytest.mark.asyncio
async def test_welcome_message_suppressed_on_subsequent_run(tmp_path: Path):
    """Verify welcome message is NOT shown when history exists."""
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

    # Simulate existing history
    with patch("brokk_code.app.load_history", return_value=["previous prompt"]):
        app = BrokkApp(executor=mock_executor)
        async with app.run_test() as pilot:
            await pilot.pause()
            
            chat_log = app.query_one("#chat-log")
            content = "".join(str(line) for line in chat_log.lines)
            assert "Welcome to Brokk" not in content
