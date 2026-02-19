from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


from brokk_code.widgets.chat_panel import ReasoningSuggestions


@pytest.mark.asyncio
async def test_reasoning_menu_keyboard_navigation_selects_level():
    executor = MagicMock()
    executor.stop = AsyncMock()
    executor.session_id = None

    app = BrokkApp(executor=executor)
    app._executor_ready = True

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # Trigger via /reasoning command
            app._handle_command("/reasoning")
            await pilot.pause()

            reasoning_suggestions = app.query_one(ReasoningSuggestions)
            assert reasoning_suggestions.display is True

            # Navigation inside ListView:
            # ["disable", "low", "medium", "high"]
            # Default for app is usually "low" (index 1).
            # To get to "medium" (index 2), we press down once.
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            assert app.reasoning_level == "medium"
            assert reasoning_suggestions.display is False
