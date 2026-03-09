from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel


def test_model_selector_bindings_absent():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key for b in app.BINDINGS}
    assert "ctrl+u" not in bindings


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
