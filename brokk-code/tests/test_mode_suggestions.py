import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ModeSuggestions, ChatInput

@pytest.mark.asyncio
async def test_mode_menu_selection_updates_app_state():
    """Verify that selecting a mode from the ModeSuggestions popup updates BrokkApp state."""
    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.agent_mode = "LUTZ"

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # 1. Trigger the mode menu
            app._handle_command("/mode")
            await pilot.pause()

            mode_suggestions = app.query_one(ModeSuggestions)
            assert mode_suggestions.display is True
            
            # 2. Navigate to "CODE" (usually first in ["CODE", "ASK", "LUTZ"])
            # Index might vary based on implementation, but let's select index 0
            mode_suggestions.index = 0
            await pilot.press("enter")
            await pilot.pause()

            # 3. Verify app state updated
            assert app.agent_mode == "CODE"
            assert mode_suggestions.display is False

@pytest.mark.asyncio
async def test_mode_menu_exclusivity_with_slash_commands():
    """Verify that opening the mode menu closes slash suggestions and vice-versa."""
    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # 1. Show slash suggestions
            await pilot.press("/")
            await pilot.pause()
            
            slash_suggestions = app.query_one("#slash-suggestions")
            mode_suggestions = app.query_one("#mode-suggestions")
            
            assert slash_suggestions.display is True
            assert mode_suggestions.display is False

            # 2. Trigger mode menu via command
            app._handle_command("/mode")
            await pilot.pause()
            
            assert slash_suggestions.display is False
            assert mode_suggestions.display is True

            # 3. Typing something else (or backspacing and typing slash) should flip it back
            await pilot.press("backspace") # Clear slash
            await pilot.press("/")
            await pilot.pause()
            
            assert slash_suggestions.display is True
            assert mode_suggestions.display is False

@pytest.mark.asyncio
async def test_mode_menu_esc_hides():
    """Verify Escape key hides the mode menu."""
    app = BrokkApp(executor=MagicMock())
    async with app.run_test() as pilot:
        app._handle_command("/mode")
        await pilot.pause()
        
        mode_suggestions = app.query_one(ModeSuggestions)
        assert mode_suggestions.display is True
        
        await pilot.press("escape")
        assert mode_suggestions.display is False
