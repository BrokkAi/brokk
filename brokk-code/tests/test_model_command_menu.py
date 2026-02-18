from unittest.mock import AsyncMock, MagicMock, patch

import pytest

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
