from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp, ContextModalScreen
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.context_panel import ContextPanel


@pytest.mark.asyncio
async def test_toggle_context_opens_fullscreen_modal_and_syncs_token_bar_visibility():
    """
    Verify that Ctrl+L toggles a full-screen context modal and token bar visibility.
    """
    mock_executor = MagicMock()
    mock_executor.stop = AsyncMock()
    app = BrokkApp(executor=mock_executor)

    async with app.run_test() as pilot:
        chat_panel = app.query_one("#chat-main", ChatPanel)
        token_usage = chat_panel.query_one("#chat-token-usage")

        # Initial state: no context modal on top; token usage bar visible.
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")

        # Toggle 1: Open context modal -> hide token bar.
        await pilot.press("ctrl+l")
        assert isinstance(app.screen, ContextModalScreen)
        app.screen.query_one("#context-panel", ContextPanel)
        assert token_usage.has_class("hidden")

        # Toggle 2: Close context modal -> show token bar.
        await pilot.press("ctrl+l")
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")
