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


def test_help_command_no_shortcuts_for_model_reasoning():
    """Verify /help output does not mention shortcuts or removed reasoning commands."""
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app._handle_command("/help")

    # Capture the help text passed to append_message
    args, _ = mock_chat.append_message.call_args
    help_text = args[1]

    assert "Ctrl+U" not in help_text
    assert "Ctrl+E" not in help_text
    assert "Shortcut:" not in help_text
    assert "/reasoning" not in help_text
    assert "/reasoning-code" not in help_text

    # Verify the model commands themselves are still documented
    assert "/model" in help_text
    assert "/model-code" in help_text


def test_help_output_matches_command_catalog():
    """Ensure every command in the catalog is present in the /help output."""
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app._handle_command("/help")

    args, _ = mock_chat.append_message.call_args
    help_text = args[1]

    for cmd_entry in app.get_slash_commands():
        cmd = cmd_entry["command"]
        assert cmd in help_text, f"Command {cmd} missing from /help output"
