from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.status_line import StatusLine


def test_action_toggle_mode_cycles_correctly():
    # Setup app with mocked executor and UI components
    app = BrokkApp(executor=MagicMock())

    # Mock query_one to return a mock ChatPanel
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # Initial state
    assert app.agent_mode == "LUTZ"

    # Cycle 1: LUTZ -> CODE
    app.action_toggle_mode()
    assert app.agent_mode == "CODE"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]CODE[/]")

    # Cycle 2: CODE -> ASK
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]ASK[/]")

    # Cycle 3: ASK -> LUTZ
    app.action_toggle_mode()
    assert app.agent_mode == "LUTZ"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]LUTZ[/]")


def test_handle_command_updates_agent_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # Test /code
    app._handle_command("/code")
    assert app.agent_mode == "CODE"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]CODE[/]")

    # Test /ask
    app._handle_command("/ask")
    assert app.agent_mode == "ASK"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]ASK[/]")

    # Test /lutz
    app._handle_command("/lutz")
    assert app.agent_mode == "LUTZ"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]LUTZ[/]")


def test_mode_toggle_command_updates_agent_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # Initial state is LUTZ
    assert app.agent_mode == "LUTZ"

    # Test /mode cycles: LUTZ -> CODE
    app._handle_command("/mode")
    assert app.agent_mode == "CODE"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]CODE[/]")

    # Test /mode cycles: CODE -> ASK
    app._handle_command("/mode")
    assert app.agent_mode == "ASK"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]ASK[/]")

    # Test /mode cycles: ASK -> LUTZ
    app._handle_command("/mode")
    assert app.agent_mode == "LUTZ"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]LUTZ[/]")


def test_no_f2_settings_binding():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: b.action for b in app.BINDINGS}
    assert "f2" not in bindings


def test_command_palette_display_is_settings():
    app = BrokkApp(executor=MagicMock())
    assert app.COMMAND_PALETTE_DISPLAY == "Settings"


def test_ctrl_p_binding_is_settings():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: (b.action, b.description, b.show) for b in app.BINDINGS}
    assert bindings["ctrl+p"] == ("command_palette", "Settings", True)


def test_model_and_reasoning_bindings_do_not_exist():
    app = BrokkApp(executor=MagicMock())
    # Verify shortcuts no longer exist in app bindings.
    bindings = {b.key for b in app.BINDINGS}
    assert "ctrl+u" not in bindings
    assert "ctrl+e" not in bindings

    from brokk_code.widgets.chat_panel import ChatInput

    chat_input_bindings = {b.key for b in ChatInput.BINDINGS}
    assert "ctrl+u" not in chat_input_bindings
    assert "ctrl+e" not in chat_input_bindings


def test_textual_command_palette_is_enabled():
    app = BrokkApp(executor=MagicMock())
    assert app.ENABLE_COMMAND_PALETTE is True


def test_action_toggle_mode_handles_unknown_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.agent_mode = "UNKNOWN"
    # Should default to first mode in cycle after first (index 0 + 1)
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"


def test_status_line_is_composed_and_updates_on_mode_change():
    """
    Ensure that when the app mode changes the status line is asked
    to update with the new mode. This test uses a mock for query_one
    so we don't require a full Textual runtime or active app context.
    """
    app = BrokkApp(executor=MagicMock())
    assert app.agent_mode == "LUTZ"

    # Replace the status widget with a mock that records updates.
    mock_status = MagicMock(spec=StatusLine)
    app.query_one = MagicMock(return_value=mock_status)

    # Change mode and ensure the status line receives an update containing the new mode.
    app._set_mode("ASK", announce=False)

    # Since StatusLine is nested in ChatPanel, we need to ensure the query works.
    # We mock _maybe_chat to return a mock ChatPanel which returns our mock status.
    mock_chat = MagicMock(spec=ChatPanel)
    app._maybe_chat = MagicMock(return_value=mock_chat)
    mock_chat.query_one.return_value = mock_status

    # Change mode and ensure the status line receives an update containing the new mode.
    app._set_mode("ASK", announce=False)
    assert app.agent_mode == "ASK"

    # Ensure _update_statusline triggered update_status with correct keyword arguments.
    assert mock_status.update_status.called, (
        "StatusLine.update_status should be called on mode change"
    )
    kwargs = mock_status.update_status.call_args.kwargs
    assert kwargs["mode"] == "ASK", "Status line should reflect the new mode 'ASK'"
    assert "branch" in kwargs, "Status line update should include branch"
