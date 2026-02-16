from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


def _close_coro(coro):
    coro.close()


def test_session_command_no_args_shows_help():
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

    app._handle_command("/session")

    mock_chat.add_system_message.assert_called_with(
        "Session commands: /session list | new <name> | "
        "switch <id> | rename <id> <name> | delete <id>"
    )


def test_session_command_list_dispatches_worker():
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

    app._handle_command("/session list")

    assert app.run_worker.call_count == 1


def test_session_command_new_requires_name():
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

    app._handle_command("/session new")

    mock_chat.add_system_message.assert_called_with("Usage: /session new <name>")


def test_session_command_new_with_name_dispatches_worker():
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

    app._handle_command("/session new my session")

    assert app.run_worker.call_count == 1


def test_undo_command_dispatches_worker():
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

    app._handle_command("/undo")

    assert app.run_worker.call_count == 1


def test_redo_command_dispatches_worker():
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

    app._handle_command("/redo")

    assert app.run_worker.call_count == 1


def test_session_command_unknown_subcommand():
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

    app._handle_command("/session invalid")

    mock_chat.add_system_message.assert_called_with(
        "Unknown /session command. Use: list, new, switch, rename, delete."
    )
