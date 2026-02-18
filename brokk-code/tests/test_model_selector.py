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
        # Verify it uses the model-only modal
        assert screen.__class__.__name__ == "ModelSelectModal"
        if callback:
            # ModelSelectModal returns a single string
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


def test_help_command_no_shortcuts_for_model_reasoning():
    """Verify /help output does not mention Ctrl+U or Ctrl+E."""
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
    # Verify the commands themselves are still documented
    assert "/model" in help_text
    assert "/reasoning" in help_text


@pytest.mark.asyncio
async def test_slash_autocomplete_filtering():
    """Verify slash suggestions filter correctly."""
    from textual.app import App, ComposeResult
    from brokk_code.widgets.chat_panel import ChatPanel, SlashCommandSuggestions, ChatInput
    from textual.widgets import Static

    class TestApp(App):
        def get_slash_commands(self):
            return [
                {"command": "/ask", "description": "d"},
                {"command": "/ask-more", "description": "d"},
                {"command": "/help", "description": "d"},
            ]

        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # Initially hidden
        assert suggestions.display is False

        # Type /a
        await pilot.press(*list("/a"))
        assert suggestions.display is True
        # matches /ask and /ask-more
        assert len(suggestions.children) == 2

        # Type sk-
        await pilot.press(*list("sk-"))
        assert len(suggestions.children) == 1
        assert "/ask-more" in str(suggestions.children[0].query_one(Static).renderable)

        # Esc hides
        await pilot.press("escape")
        assert suggestions.display is False
