from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.context_panel import ContextPanel


@pytest.mark.asyncio
async def test_context_panel_shows_clear_selection_state():
    mock_executor = MagicMock()
    mock_executor.stop = AsyncMock()
    mock_executor.get_context = AsyncMock(
        return_value={
            "usedTokens": 321,
            "maxTokens": 100000,
            "fragments": [
                {
                    "id": "f-1",
                    "chipKind": "EDIT",
                    "shortDescription": "Editable file",
                    "pinned": True,
                    "readonly": False,
                    "editable": True,
                    "tokens": 100,
                },
                {
                    "id": "f-2",
                    "chipKind": "HISTORY",
                    "shortDescription": "Conversation history",
                    "pinned": False,
                    "readonly": False,
                    "editable": False,
                    "tokens": 221,
                },
            ],
        }
    )
    app = BrokkApp(executor=mock_executor)

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            app._executor_ready = True

            await pilot.press("ctrl+l")
            await app._refresh_context_panel()
            await pilot.pause()

            panel = app.screen.query_one("#context-panel", ContextPanel)
            assert len(panel.query(".context-chip")) >= 2

            selection_status = panel.query_one("#context-selection-status")
            active_status = panel.query_one("#context-active-status")
            assert "Selected: 0" in str(selection_status.render())
            assert "Active: Editable file" in str(active_status.render())

            # Select the cursor chip (first fragment).
            await pilot.press("enter")
            await pilot.pause()

            assert "Selected: 1" in str(selection_status.render())
            selected_chip_texts = " ".join(
                item.render().plain for item in panel.query(".context-chip")
            )
            assert "[SELECTED]" in selected_chip_texts
            assert "[ACTIVE]" in selected_chip_texts

            # Move active cursor to next chip.
            await pilot.press("right")
            await pilot.pause()
            assert "Active: Conversation history" in str(active_status.render())
