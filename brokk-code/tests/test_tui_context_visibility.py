import pytest
from unittest.mock import MagicMock
from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.context_panel import ContextPanel


@pytest.mark.asyncio
async def test_toggle_context_syncs_token_bar_visibility():
    """
    Verify that toggling the context panel visibility correctly toggles
    the token bar visibility in the ChatPanel.
    """
    app = BrokkApp(executor=MagicMock())

    async with app.run_test() as pilot:
        context_panel = app.query_one("#context-panel", ContextPanel)
        chat_panel = app.query_one("#chat-main", ChatPanel)
        token_usage = chat_panel.query_one("#chat-token-usage")

        # Initial State: Context Panel is visible (not hidden),
        # Token usage is hidden.
        assert not context_panel.has_class("hidden")
        assert token_usage.has_class("hidden")

        # Toggle 1: Hide Context -> Show Token Bar
        await pilot.press("ctrl+l")
        assert context_panel.has_class("hidden")
        assert not token_usage.has_class("hidden")

        # Toggle 2: Show Context -> Hide Token Bar
        await pilot.press("ctrl+l")
        assert not context_panel.has_class("hidden")
        assert token_usage.has_class("hidden")
