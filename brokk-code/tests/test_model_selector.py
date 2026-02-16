from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel


def test_model_selector_binding_exists():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: (b.action, b.description, b.show) for b in app.BINDINGS}
    # Verify Ctrl+U shortcut exists and is visible.
    assert "ctrl+u" in bindings
    assert bindings["ctrl+u"] == ("select_model", "Model", True)


@pytest.mark.asyncio
async def test_action_select_model_not_ready():
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = False

    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    await app.action_select_model()

    mock_chat.add_system_message.assert_called_with(
        "Executor is not ready. Cannot select model.", level="ERROR"
    )


@pytest.mark.asyncio
async def test_action_select_model_updates_state():
    # Setup app and executor mock
    executor = MagicMock()
    executor.get_models = AsyncMock(
        return_value={
            "models": [
                {"name": "gpt-4", "location": "x"},
                {"name": "claude-3", "location": "y"},
            ]
        }
    )
    app = BrokkApp(executor=executor)
    app._executor_ready = True

    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # We mock push_screen to capture the callback and invoke it immediately
    # as if the user selected a model in the modal.
    def mock_push_screen(screen, callback=None):
        if callback:
            callback("claude-3")

    app.push_screen = MagicMock(side_effect=mock_push_screen)

    await app.action_select_model()

    assert app.current_model == "claude-3"
    mock_chat.add_system_message_markup.assert_called_with("Model changed to: [bold]claude-3[/]")


@pytest.mark.asyncio
async def test_action_select_model_handles_dotted_model_names():
    executor = MagicMock()
    executor.get_models = AsyncMock(
        return_value={
            "models": [
                {"name": "gemini-2.0-flash", "location": "test"},
            ]
        }
    )
    executor.stop = AsyncMock()
    executor.session_id = None

    app = BrokkApp(executor=executor)
    app._executor_ready = True

    from unittest.mock import patch

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            await app.action_select_model()
            await pilot.pause()
            assert app.screen.__class__.__name__ == "ModelSelectModal"


@pytest.mark.asyncio
async def test_model_modal_keyboard_navigation_selects_model():
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
    executor.session_id = None

    app = BrokkApp(executor=executor)
    app._executor_ready = True

    from unittest.mock import patch

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            await app.action_select_model()
            await pilot.pause()

            # Move from first to second row and select.
            await pilot.press("down")
            await pilot.press("enter")
            await pilot.pause()

            assert app.current_model == "beta-model"
