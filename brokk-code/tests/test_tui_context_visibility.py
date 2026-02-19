from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp, ContextModalScreen
from brokk_code.widgets.chat_panel import ChatPanel


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
        token_usage = chat_panel.query_one("#chat-token-bar")

        # Initial state: no context modal on top; token usage bar visible.
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")

        # Toggle 1: Open context modal -> hide token bar.
        await pilot.press("ctrl+l")
        assert isinstance(app.screen, ContextModalScreen)
        assert token_usage.has_class("hidden")

        # Toggle 2: Close context modal -> show token bar.
        await pilot.press("ctrl+l")
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")

        # Toggle 3: Open via slash command -> hide token bar.
        await pilot.press("/")
        await pilot.type("context")
        await pilot.press("enter")
        assert isinstance(app.screen, ContextModalScreen)
        assert token_usage.has_class("hidden")

        # Toggle 4: Close via slash command -> show token bar.
        # Note: ChatInput in app.py handles '/' commands, but while modal is open
        # the modal's bindings or clicking back might be needed.
        # However, slash commands are submitted via the ChatPanel's input.
        # If the modal is open, we can still use ctrl+l to close as per ContextModalScreen.BINDINGS.
        await pilot.press("ctrl+l")
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")
