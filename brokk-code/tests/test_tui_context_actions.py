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

            # Open context modal via slash command
            await pilot.press("/", *"context".split(), "enter")
            await app._refresh_context_panel()
            await pilot.pause()

            panel = app.screen.query_one("#context-panel", ContextPanel)
            assert len(panel.query(".context-chip")) >= 2

            # Verify help line is present and contains some expected keys
            help_line = panel.query_one("#context-help-line", Static)
            help_text = str(help_line.render())
            # Basic keys from _get_shortcuts_text()
            assert "Space" in help_text
            assert "Enter" in help_text
            assert "Drop" in help_text
            assert "U" in help_text  # clear_selection
            assert "CTRL+A" in help_text  # select_all
            assert "P" in help_text  # toggle_pin_selected

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
            assert "[ACTIVE]" not in selected_chip_texts

            # Move active cursor to next chip.
            await pilot.press("right")
            await pilot.pause()
            assert "Active: Conversation history" in str(active_status.render())


@pytest.mark.asyncio
async def test_context_panel_drop_others_action():
    mock_executor = MagicMock()
    mock_executor.stop = AsyncMock()
    mock_executor.drop_context_fragments = AsyncMock()
    mock_executor.get_context = AsyncMock(
        return_value={
            "usedTokens": 400,
            "maxTokens": 100000,
            "fragments": [
                {"id": "f-1", "chipKind": "EDIT", "shortDescription": "Keep Me", "tokens": 100},
                {"id": "f-2", "chipKind": "EDIT", "shortDescription": "Drop Me", "tokens": 100},
                {
                    "id": "f-3",
                    "chipKind": "HISTORY",
                    "shortDescription": "Keep History",
                    "tokens": 100,
                },
                {
                    "id": "f-4",
                    "chipKind": "EDIT",
                    "shortDescription": "Keep Pinned",
                    "pinned": True,
                    "tokens": 100,
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
            await pilot.press("/", *"context".split(), "enter")
            await app._refresh_context_panel()
            await pilot.pause()

            # Active is f-1 by default.
            # We will trigger 'drop_others' (key 'o').
            # Should drop f-2.
            # Should NOT drop f-1 (active), f-3 (history), f-4 (pinned).
            await pilot.press("o")
            await pilot.pause()

            mock_executor.drop_context_fragments.assert_called_once_with(["f-2"])
