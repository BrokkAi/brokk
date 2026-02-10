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

    # Cycle 1: LUTZ -> ASK
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]ASK[/]")

    # Cycle 2: ASK -> SEARCH
    app.action_toggle_mode()
    assert app.agent_mode == "SEARCH"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]SEARCH[/]")

    # Cycle 3: SEARCH -> LUTZ
    app.action_toggle_mode()
    assert app.agent_mode == "LUTZ"
    mock_chat.add_system_message_markup.assert_called_with("Mode changed to: [bold]LUTZ[/]")


def test_action_toggle_mode_handles_unknown_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.agent_mode = "UNKNOWN"
    # Should default to first mode in cycle after first (index 0 + 1)
    app.action_toggle_mode()
    assert app.agent_mode == "ASK"
