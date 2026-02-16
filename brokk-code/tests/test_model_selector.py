import pytest
from unittest.mock import AsyncMock, MagicMock
from brokk_code.app import BrokkApp, ModelSelectModal
from brokk_code.widgets.chat_panel import ChatPanel


def test_model_selector_binding_exists():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: (b.action, b.description, b.show) for b in app.BINDINGS}
    assert "ctrl+m" in bindings
    assert bindings["ctrl+m"] == ("select_model", "Model", True)


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
    executor.get_models = AsyncMock(return_value={"models": ["gpt-4", "claude-3"]})
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
