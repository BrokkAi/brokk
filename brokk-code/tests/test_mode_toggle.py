from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel


def test_action_toggle_mode_cycles_correctly():
    # Setup app with mocked executor and UI components
    app = BrokkApp(executor=MagicMock())

    # Mock query_one to return a mock ChatPanel
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # Initial state
    assert app.agent_mode == "LUTZ"
    assert app.sub_title == "Mode: LUTZ"

    # Cycle 1: LUTZ -> ASK
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"
    assert app.sub_title == "Mode: ASK"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]ASK[/]", level="WARNING"
    )

    # Cycle 2: ASK -> SEARCH
    app.action_toggle_mode()
    assert app.agent_mode == "SEARCH"
    assert app.sub_title == "Mode: SEARCH"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]SEARCH[/]", level="WARNING"
    )

    # Cycle 3: SEARCH -> LUTZ
    app.action_toggle_mode()
    assert app.agent_mode == "LUTZ"
    assert app.sub_title == "Mode: LUTZ"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]LUTZ[/]", level="WARNING"
    )


def test_handle_command_updates_mode_and_subtitle():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    # Test /ask
    app._handle_command("/ask")
    assert app.agent_mode == "ASK"
    assert app.sub_title == "Mode: ASK"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]ASK[/]", level="WARNING"
    )

    # Test /search
    app._handle_command("/search")
    assert app.agent_mode == "SEARCH"
    assert app.sub_title == "Mode: SEARCH"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]SEARCH[/]", level="WARNING"
    )

    # Test /lutz
    app._handle_command("/lutz")
    assert app.agent_mode == "LUTZ"
    assert app.sub_title == "Mode: LUTZ"
    mock_chat.add_system_message_markup.assert_called_with(
        "Mode changed to: [bold]LUTZ[/]", level="WARNING"
    )


def test_mode_toggle_bindings_exist():
    app = BrokkApp(executor=MagicMock())
    # Verify the bindings are present and mapped to toggle_mode
    bindings = {b.key: b.action for b in app.BINDINGS}
    assert bindings["ctrl+g"] == "toggle_mode"
    assert bindings["f3"] == "toggle_mode"


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


def test_ctrl_e_binding_is_reasoning():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: (b.action, b.description, b.show) for b in app.BINDINGS}
    assert bindings["ctrl+e"] == ("select_reasoning", "Reasoning", True)


def test_textual_command_palette_is_enabled():
    app = BrokkApp(executor=MagicMock())
    assert app.ENABLE_COMMAND_PALETTE is True


def test_statusline_binding_present_and_toggle_invokes_widget():
    from brokk_code.widgets.status_line import StatusLine

    app = BrokkApp(executor=MagicMock())

    # a) Binding presence
    bindings = {b.key: (b.action, b.description, b.show) for b in app.BINDINGS}
    assert bindings["ctrl+s"] == ("toggle_statusline", "Status", True)

    # b) Toggle behavior: mock query_one to return a mock status widget
    mock_status = MagicMock(spec=StatusLine)
    app.query_one = MagicMock(return_value=mock_status)

    app.action_toggle_statusline()
    mock_status.toggle_class.assert_called_with("hidden")


def test_statusline_live_update_called_on_mode_and_changes():
    # Use MagicMock statusline and patch _maybe_status_line to return it
    app = BrokkApp(executor=MagicMock())
    mock_status = MagicMock()
    # Provide _maybe_status_line that returns our mock
    app._maybe_status_line = MagicMock(return_value=mock_status)

    # Trigger a mode change (without announce)
    app._set_mode("ASK", announce=False)
    # _set_mode calls _update_status_line -> _maybe_status_line -> update_status
    mock_status.update_status.assert_called()
    called_args = mock_status.update_status.call_args[1]  # kwargs
    assert "mode" in called_args and "ASK" in called_args["mode"]

    # Change model and reasoning and call explicit update
    app.current_model = "example-model"
    app.reasoning_level = "high"
    app._update_status_line()
    mock_status.update_status.assert_called_with(
        mode=app.current_mode,
        model=app.current_model,
        reasoning=app.reasoning_level or "",
        directory=str(app.executor.workspace_dir),
    )


def test_action_toggle_mode_handles_unknown_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.agent_mode = "UNKNOWN"
    # Should default to first mode in cycle after first (index 0 + 1)
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"
    assert app.sub_title == "Mode: ASK"
