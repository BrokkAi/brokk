from unittest.mock import MagicMock, PropertyMock, patch

import pytest
from rich.text import Text
from textual.app import App, ComposeResult
from textual.widgets import Static

from brokk_code.app import BrokkApp, TaskListModalScreen, TaskTitleModalScreen
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


def _close_coro(coro):
    """Helper to immediately close background coroutines started by run_worker."""
    coro.close()


def _static_rendered_text(widget: Static) -> str:
    rendered = widget.render()
    if isinstance(rendered, Text):
        return rendered.plain
    return str(rendered)


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

    # Simulate the modal already being open (Textual's `screen` has no setter)
    modal = TaskListModalScreen(on_close=lambda: None)
    modal.dismiss = MagicMock()

    with patch.object(type(app), "screen", new_callable=PropertyMock, return_value=modal):
        # /task should close the modal and restore focus
        app._handle_command("/task")

    restore_focus_widget.focus.assert_called_once()
    modal.dismiss.assert_called_once_with(None)


@pytest.mark.asyncio
async def test_tasklist_panel_keybindings_call_app_actions() -> None:
    class TestApp(App):
        def __init__(self) -> None:
            super().__init__()
            self.action_task_add = MagicMock()
            self.action_task_edit = MagicMock()
            self.action_task_delete = MagicMock()
            self.action_task_toggle = MagicMock()

        def compose(self) -> ComposeResult:
            yield TaskListPanel(id="tl")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#tl", TaskListPanel)
        panel.update_tasklist_details(
            {
                "bigPicture": "x",
                "tasks": [
                    {"id": "1", "title": "One", "text": "One", "done": False},
                ],
            }
        )
        panel.focus()
        await pilot.pause()

        await pilot.press("a")
        await pilot.pause()
        app.action_task_add.assert_called_once()

        await pilot.press("e")
        await pilot.pause()
        app.action_task_edit.assert_called_once()

        await pilot.press("d")
        await pilot.pause()
        app.action_task_delete.assert_called_once()

        await pilot.press("space")
        await pilot.pause()
        app.action_task_toggle.assert_called_once()


@pytest.mark.asyncio
async def test_tasklist_panel_help_line_contains_expected_keybindings() -> None:
    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield TaskListPanel(id="tl")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#tl", TaskListPanel)
        panel.update_tasklist_details(
            {
                "bigPicture": "x",
                "tasks": [
                    {"id": "1", "title": "One", "text": "One", "done": False},
                ],
            }
        )
        await pilot.pause()

        help_line = panel.query_one("#tasklist-help-line", Static)
        rendered = _static_rendered_text(help_line)

        # Esc is now first and highlighted; assert content + ordering without
        # overfitting spacing/alignment.
        assert "Esc Close" in rendered
        assert rendered.index("Esc") < rendered.index("Space")
        assert rendered.index("Esc") < rendered.index("Enter")

        # Basic expected entries still present.
        assert "Up/Down" in rendered
        assert "Space" in rendered
        assert "Enter" in rendered
        assert "A" in rendered
        assert "E" in rendered
        assert "D" in rendered
        assert "Toggle" in rendered


def test_app_task_add_opens_modal_and_dispatches_add_worker_on_submit() -> None:
    app = BrokkApp(executor=MagicMock())
    app.run_worker = MagicMock(side_effect=_close_coro)
    app.push_screen = MagicMock()

    app.action_task_add()

    assert app.push_screen.call_count == 1
    pushed_screen = app.push_screen.call_args.args[0]
    callback = app.push_screen.call_args.args[1]
    assert isinstance(pushed_screen, TaskTitleModalScreen)

    callback("New Task")

    assert app.run_worker.call_count == 1
    worker_coro = app.run_worker.call_args.args[0]
    assert worker_coro.__name__ == "_add_task"


def test_app_task_edit_opens_modal_with_initial_and_dispatches_edit_worker_on_submit() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = {
        "id": "1",
        "title": "Old",
        "text": "Old",
        "done": False,
    }

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target == "#side-tasklist":
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)
    app.push_screen = MagicMock()

    app.action_task_edit()

    assert app.push_screen.call_count == 1
    pushed_screen = app.push_screen.call_args.args[0]
    callback = app.push_screen.call_args.args[1]
    assert isinstance(pushed_screen, TaskTitleModalScreen)

    callback("Updated")

    assert app.run_worker.call_count == 1
    worker_coro = app.run_worker.call_args.args[0]
    assert worker_coro.__name__ == "_edit_selected_task"


def test_task_command_open_when_executor_ready_triggers_immediate_tasklist_fetch() -> None:
    app = BrokkApp(executor=MagicMock())
    mock_chat = MagicMock(spec=ChatPanel)

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)
    app.push_screen = MagicMock()
    app._executor_ready = True

    app._handle_command("/task")

    assert app.push_screen.call_count == 1
    assert app.run_worker.call_count == 2

    worker_coros = [c.args[0] for c in app.run_worker.call_args_list]
    worker_names = {coro.__name__ for coro in worker_coros}
    assert worker_names == {"_ensure_tasklist_data", "_refresh_context_panel"}


def test_app_task_delete_dispatches_delete_worker() -> None:
    app = BrokkApp(executor=MagicMock())
    app.run_worker = MagicMock(side_effect=_close_coro)

    app.action_task_delete()

    assert app.run_worker.call_count == 1
    worker_coro = app.run_worker.call_args.args[0]
    assert worker_coro.__name__ == "_delete_selected_task"
