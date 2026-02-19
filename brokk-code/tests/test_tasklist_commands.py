from unittest.mock import MagicMock

from brokk_code.app import BrokkApp, TaskListModalScreen
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


def _close_coro(coro):
    """Helper to immediately close background coroutines started by run_worker."""
    coro.close()


def test_app_has_no_global_tasklist_bindings() -> None:
    keys = {b.key for b in BrokkApp.BINDINGS}
    assert "ctrl+j" not in keys
    assert "ctrl+k" not in keys
    assert "ctrl+space" not in keys


def test_task_command_opens_modal_and_focuses_tasklist_panel() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)
    app.push_screen = MagicMock()

    # Open modal via /task
    app._handle_command("/task")

    # Assert a TaskListModalScreen instance was pushed
    assert app.push_screen.call_count == 1
    pushed_screen = app.push_screen.call_args.args[0]
    assert isinstance(pushed_screen, TaskListModalScreen)

    # Simulate the modal being mounted and ensure it focuses the TaskListPanel
    mock_tasklist_panel = MagicMock(spec=TaskListPanel)
    pushed_screen.query_one = MagicMock(return_value=mock_tasklist_panel)

    pushed_screen.on_mount()

    mock_tasklist_panel.focus.assert_called_once()


def test_task_command_closes_modal_and_restores_focus() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    # Pretend something else currently has focus and should be restored
    restore_focus_widget = MagicMock()
    app._tasklist_restore_focus_widget = restore_focus_widget

    # Simulate the modal already being open
    modal = TaskListModalScreen(on_close=lambda: None)
    modal.dismiss = MagicMock()
    app.screen = modal

    # /task should close the modal and restore focus
    app._handle_command("/task")

    restore_focus_widget.focus.assert_called_once()
    modal.dismiss.assert_called_once_with(None)


def test_task_command_next_moves_selection() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.move_selection.return_value = True

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target == "#side-tasklist":
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    app._handle_command("/task next")

    mock_panel.move_selection.assert_called_once_with(1)


def test_task_command_toggle_dispatches_worker() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target == "#side-tasklist":
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    app._handle_command("/task toggle")

    # Toggling dispatches a background worker to update the selected task
    assert app.run_worker.call_count == 1
