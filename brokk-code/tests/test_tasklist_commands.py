from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


def _close_coro(coro):
    coro.close()


def test_app_has_no_global_tasklist_bindings() -> None:
    keys = {b.key for b in BrokkApp.BINDINGS}
    assert "ctrl+j" not in keys
    assert "ctrl+k" not in keys
    assert "ctrl+space" not in keys


def test_task_command_toggles_modal():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)

    app.query_one = MagicMock(
        side_effect=lambda target, *a, **k: mock_chat if target is ChatPanel else None
    )
    app.run_worker = MagicMock(side_effect=_close_coro)

    app.push_screen = MagicMock()

    # Open modal
    app._handle_command("/task")
    assert app.push_screen.call_count == 1
    assert app.push_screen.call_args.args[0].__class__.__name__ == "TaskListModalScreen"

    # Close modal when already open
    mock_modal = MagicMock()
    app.screen = mock_modal
    mock_modal.__class__ = type("TaskListModalScreen", (), {})
    mock_modal.dismiss = MagicMock()

    app._handle_command("/task")
    mock_modal.dismiss.assert_called_once()


def test_task_command_next_moves_selection():
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


def test_task_command_toggle_dispatches_worker():
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

    assert app.run_worker.call_count == 1
