from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


def _close_coro(coro):
    coro.close()


def test_task_command_opens_panel():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.display = False

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target is TaskListPanel:
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    app._handle_command("/task")

    assert mock_panel.display is True
    mock_panel.focus.assert_called_once()


def test_task_command_next_moves_selection():
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.move_selection.return_value = True

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target is TaskListPanel:
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
        if target is TaskListPanel:
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    app._handle_command("/task toggle")

    assert app.run_worker.call_count == 1
