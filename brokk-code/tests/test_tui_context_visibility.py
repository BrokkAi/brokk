from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp, ContextModalScreen
from brokk_code.widgets.chat_panel import ChatPanel


@pytest.mark.asyncio
async def test_slash_context_toggles_fullscreen_modal_and_syncs_token_bar_visibility():
    """
    Verify that the /context slash command opens the context modal and token bar visibility.
    """
    mock_executor = MagicMock()
    mock_executor.stop = AsyncMock()
    app = BrokkApp(executor=mock_executor)

    async with app.run_test() as pilot:
        chat_panel = app.query_one("#chat-main", ChatPanel)
        token_usage = chat_panel.query_one("#chat-token-bar")

        # Initial state
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")

        # Open via slash command
        await pilot.press("/", *"context".split(), "enter")
        assert isinstance(app.screen, ContextModalScreen)
        assert token_usage.has_class("hidden")

        # Close via Escape (as slash command isn't available while modal is focused)
        await pilot.press("escape")
        assert not isinstance(app.screen, ContextModalScreen)
        assert not token_usage.has_class("hidden")
