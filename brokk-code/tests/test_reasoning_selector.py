from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ReasoningSuggestions


@pytest.mark.asyncio
async def test_reasoning_menu_keyboard_navigation_selects_level():
    """Verify that submitting /reasoning opens the inline popup and updates state."""
    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True
    # Force a known starting level
    app.reasoning_level = "low"

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # 1. Trigger via /reasoning command submission
            await pilot.press(*list("/reasoning"))
            await pilot.press("enter")
            await pilot.pause()

            reasoning_suggestions = app.query_one(ReasoningSuggestions)
            assert reasoning_suggestions.display is True

            # 2. Navigation inside ListView:
            # Levels: ["disable", "low", "medium", "high"]
            # Current is "low" (index 1). Press down to "medium" (index 2).
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            # 3. Verify app state updated
            assert app.reasoning_level == "medium"
            assert reasoning_suggestions.display is False


@pytest.mark.asyncio
async def test_reasoning_code_menu_keyboard_navigation_selects_level():
    """Verify that submitting /reasoning-code opens the inline popup and updates code state."""
    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.reasoning_level_code = "disable"

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # 1. Trigger via /reasoning-code command submission
            await pilot.press(*list("/reasoning-code"))
            await pilot.press("enter")
            await pilot.pause()

            reasoning_suggestions = app.query_one(ReasoningSuggestions)
            assert reasoning_suggestions.display is True

            # 2. Navigation: Levels: ["disable", "low", "medium", "high"]
            # Current is "disable" (index 0). Press down to "low" (index 1).
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            # 3. Verify app state updated
            assert app.reasoning_level_code == "low"
            assert reasoning_suggestions.display is False


@pytest.mark.asyncio
async def test_reasoning_code_menu_keyboard_navigation_selects_level():
    """Verify that submitting /reasoning-code opens the inline popup and updates code state."""
    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.reasoning_level_code = "disable"

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # 1. Trigger via /reasoning-code command submission
            await pilot.press(*list("/reasoning-code"))
            await pilot.press("enter")
            await pilot.pause()

            reasoning_suggestions = app.query_one(ReasoningSuggestions)
            assert reasoning_suggestions.display is True

            # 2. Navigation: Levels: ["disable", "low", "medium", "high"]
            # Current is "disable" (index 0). Press down to "low" (index 1).
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            # 3. Verify app state updated
            assert app.reasoning_level_code == "low"
            assert reasoning_suggestions.display is False
