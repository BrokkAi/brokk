from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from textual.widgets import ListView, Static

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_model_command_with_arg_sets_directly():
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
            # Simulate typing /model gpt-4-turbo
            app._handle_command("/model gpt-4-turbo")
            await pilot.pause()

            assert app.current_model == "gpt-4-turbo"


@pytest.mark.asyncio
async def test_model_command_no_arg_opens_modal():
    executor = MagicMock()
    executor.get_models = AsyncMock(return_value={"models": ["m1", "m2"]})
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
            # Simulate typing /model with no args
            app._handle_command("/model")
            await pilot.pause()

            assert app.screen.__class__.__name__ == "ModelReasoningSelectModal"


@pytest.mark.asyncio
async def test_combined_modal_navigation_updates_both_settings():
    executor = MagicMock()
    executor.get_models = AsyncMock(
        return_value={
            "models": [
                {"name": "alpha-model", "location": "x"},
                {"name": "beta-model", "location": "y"},
            ]
        }
    )
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
            # Trigger combined modal
            await app.action_select_model_and_reasoning()
            await pilot.pause()

            # 1. Selection Pane: Model (Focus is here by default)
            # Move to 'beta-model' and confirm. This moves focus to Reasoning pane.
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            # 2. Selection Pane: Reasoning (Focus should be here now)
            # Reasoning list is: disable, low, medium, high.
            # Highlight starts at current value 'low' (idx 1), so one Down moves to 'medium' (idx 2).
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            assert app.current_model == "beta-model"
            assert app.reasoning_level == "medium"


@pytest.mark.asyncio
async def test_combined_modal_initial_highlight_syncs_with_current_values():
    """Regression test: Ensure Enter immediately selects the already-active model/reasoning."""
    executor = MagicMock()
    executor.get_models = AsyncMock(
        return_value={
            "models": [
                {"name": "alpha-model", "location": "x"},
                {"name": "beta-model", "location": "y"},
            ]
        }
    )
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True

    # Pre-set values to something other than the first index (0)
    app.current_model = "beta-model"
    app.reasoning_level = "high"  # High is usually the last index

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # Trigger combined modal
            await app.action_select_model_and_reasoning()
            await pilot.pause()

            # 1. Selection Pane: Model
            # Press Enter immediately. If highlight is synced, it should stay 'beta-model'.
            # If highlight was stuck at index 0, it would change to 'alpha-model'.
            await pilot.press("enter")
            await pilot.pause()

            # 2. Selection Pane: Reasoning
            # Press Enter immediately. If highlight is synced, it should stay 'high'.
            await pilot.press("enter")
            await pilot.pause()

            assert app.current_model == "beta-model"
            assert app.reasoning_level == "high"


@pytest.mark.asyncio
async def test_combined_modal_checked_marker_is_visible():
    """Regression test: Ensure the [x] marker is literally present and not swallowed by markup."""
    executor = MagicMock()
    executor.get_models = AsyncMock(
        return_value={
            "models": [
                {"name": "alpha-model", "location": "x"},
                {"name": "beta-model", "location": "y"},
            ]
        }
    )
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)
    app._executor_ready = True

    # Pre-set values
    app.current_model = "beta-model"  # Index 1
    app.reasoning_level = "medium"  # Index 2 in ["disable", "low", "medium", "high"]

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            await app.action_select_model_and_reasoning()
            await pilot.pause()

            # Verify Model list marker
            model_list = app.screen.query_one("#model-select-list", ListView)
            selected_model_item = model_list.children[model_list.index]
            # render_line returns a Strip; converting to str gives the plain text
            model_label = str(selected_model_item.query_one(Static).render_line(0).text)
            assert "[x]" in model_label, f"Model marker '[x]' missing from: {model_label}"

            # Verify Reasoning list marker
            reasoning_list = app.screen.query_one("#reasoning-select-list", ListView)
            selected_reasoning_item = reasoning_list.children[reasoning_list.index]
            reasoning_label = str(selected_reasoning_item.query_one(Static).render_line(0).text)
            assert "[x]" in reasoning_label, (
                f"Reasoning marker '[x]' missing from: {reasoning_label}"
            )


@pytest.mark.asyncio
async def test_reasoning_command_with_arg_sets_directly():
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
            app._handle_command("/reasoning high")
            await pilot.pause()

            assert app.reasoning_level == "high"
