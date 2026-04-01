import signal
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.status_line import StatusLine


def test_action_toggle_mode_cycles_correctly():
    # Setup app with mocked executor and UI components
    app = BrokkApp(executor=MagicMock())

    # Mock query_one to return a mock ChatPanel
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    expected_modes = ["LITE_AGENT", "LITE_PLAN", "CODE", "ASK", "LUTZ", "PLAN"]

    # Initial state
    assert app.agent_mode == "LITE_AGENT"

    # Cycle through all modes and back to the start
    for i in range(len(expected_modes)):
        next_mode = expected_modes[(i + 1) % len(expected_modes)]
        app.action_toggle_mode()
        assert app.agent_mode == next_mode
        expected = f"Mode changed to: [bold]{next_mode}[/]"
        mock_chat.add_system_message_markup.assert_called_with(expected)


def test_handle_command_modes_removed():
    """Verify that mode slash commands are treated as unknown."""
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    for cmd in ["/code", "/ask", "/lutz", "/plan", "/mode"]:
        app._handle_command(cmd)
        args, _ = mock_chat.append_message.call_args
        assert f"Unknown command: {cmd}" in args[1]


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


def test_ctrl_z_binding_is_suspend():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: (b.action, b.show, b.priority) for b in app.BINDINGS}
    assert bindings["ctrl+z"] == ("suspend_process", False, True)


def test_action_suspend_process_suspends_executor_and_calls_textual_suspend(monkeypatch):
    app = BrokkApp(executor=MagicMock())
    app.executor._process = SimpleNamespace(pid=4321, returncode=None)

    called: dict[str, tuple[int, int]] = {}

    def _fake_kill(pid: int, sig: int) -> None:
        called["kill"] = (pid, sig)

    monkeypatch.setattr("brokk_code.app.os.kill", _fake_kill)

    suspend_called = {"value": False}

    def _fake_textual_suspend(self) -> None:  # noqa: ANN001
        suspend_called["value"] = True

    monkeypatch.setattr("textual.app.App.action_suspend_process", _fake_textual_suspend)

    app.action_suspend_process()

    if hasattr(signal, "SIGTSTP"):
        assert called["kill"] == (4321, signal.SIGTSTP)
    else:
        assert "kill" not in called

    assert suspend_called["value"] is True


def test_on_app_resume_sends_sigcont_to_executor(monkeypatch):
    app = BrokkApp(executor=MagicMock())
    app.executor._process = SimpleNamespace(pid=9876, returncode=None)

    called: dict[str, tuple[int, int]] = {}

    def _fake_kill(pid: int, sig: int) -> None:
        called["kill"] = (pid, sig)

    monkeypatch.setattr("brokk_code.app.os.kill", _fake_kill)

    app._on_app_resume(app)

    if hasattr(signal, "SIGCONT"):
        assert called["kill"] == (9876, signal.SIGCONT)
    else:
        assert "kill" not in called


def test_no_notification_binding():
    app = BrokkApp(executor=MagicMock())
    # Verify ctrl+n is no longer in the app-level bindings
    bindings = {b.key for b in app.BINDINGS}
    assert "ctrl+n" not in bindings


def test_shift_tab_binding_toggles_mode():
    app = BrokkApp(executor=MagicMock())
    bindings = {b.key: b.action for b in app.BINDINGS}
    assert bindings["shift+tab"] == "toggle_mode"


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


@pytest.mark.asyncio
async def test_shift_tab_keypress_cycles_mode_in_running_app():
    app = BrokkApp(executor=MagicMock())

    async def _noop() -> None:
        return None

    # Avoid starting background workers / touching the executor during this keybinding test.
    app._start_executor = _noop  # type: ignore[method-assign]
    app._monitor_executor = _noop  # type: ignore[method-assign]
    app._poll_tasklist = _noop  # type: ignore[method-assign]
    app._poll_context = _noop  # type: ignore[method-assign]

    assert app.agent_mode == "LITE_AGENT"

    async with app.run_test() as pilot:
        # Cycle through all modes
        await pilot.press("shift+tab")
        assert app.agent_mode == "LITE_PLAN"

        await pilot.press("shift+tab")
        assert app.agent_mode == "CODE"

        await pilot.press("shift+tab")
        assert app.agent_mode == "ASK"

        await pilot.press("shift+tab")
        assert app.agent_mode == "LUTZ"

        await pilot.press("shift+tab")
        assert app.agent_mode == "PLAN"

        # Wraps back to LITE_AGENT
        await pilot.press("shift+tab")
        assert app.agent_mode == "LITE_AGENT"


def test_textual_command_palette_is_enabled():
    app = BrokkApp(executor=MagicMock())
    assert app.ENABLE_COMMAND_PALETTE is True


def test_action_toggle_mode_handles_unknown_mode():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.agent_mode = "UNKNOWN"
    # Defaults to idx 0 ("LITE_AGENT") then increments: (0+1)%6 = 1
    app.action_toggle_mode()
    assert app.agent_mode == "LITE_PLAN"


def test_status_line_is_composed_and_updates_on_mode_change():
    """
    Ensure that when the app mode changes the status line is asked
    to update with the new mode. This test uses a mock for query_one
    so we don't require a full Textual runtime or active app context.
    """
    app = BrokkApp(executor=MagicMock())
    assert app.agent_mode == "LITE_AGENT"

    # Replace the status widget with a mock that records updates.
    mock_status = MagicMock(spec=StatusLine)
    app.query_one = MagicMock(return_value=mock_status)

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


def test_action_toggle_output_state_and_refresh():
    """Verify that action_toggle_output toggles state and calls ChatPanel.refresh_log."""
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)
    app._maybe_chat = MagicMock(return_value=mock_chat)

    # Initial state
    assert app.show_verbose_output is False

    # Toggle 1: False -> True
    app.action_toggle_output()
    assert app.show_verbose_output is True
    mock_chat.refresh_log.assert_called_with(True)

    # Toggle 2: True -> False
    app.action_toggle_output()
    assert app.show_verbose_output is False
    mock_chat.refresh_log.assert_called_with(False)


@pytest.mark.asyncio
async def test_ctrl_o_keypress_toggles_output_in_running_app():
    """Verify that Ctrl+O keypress toggles output verbosity."""
    app = BrokkApp(executor=MagicMock())

    async def _noop() -> None:
        return None

    app._start_executor = _noop  # type: ignore[method-assign]
    app._monitor_executor = _noop  # type: ignore[method-assign]
    app._poll_tasklist = _noop  # type: ignore[method-assign]
    app._poll_context = _noop  # type: ignore[method-assign]

    assert app.show_verbose_output is False

    async with app.run_test() as pilot:
        await pilot.press("ctrl+o")
        assert app.show_verbose_output is True

        await pilot.press("ctrl+o")
        assert app.show_verbose_output is False
