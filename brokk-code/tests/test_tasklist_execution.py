"""Tests for the 'Run selected task' execution flow."""

from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional
from unittest.mock import MagicMock, PropertyMock, patch

import pytest

from brokk_code.app import BrokkApp, TaskListModalScreen
from brokk_code.executor import ExecutorManager
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.tasklist_panel import TaskListPanel


class StubExecutor(ExecutorManager):
    """Stub executor for testing task execution flow."""

    def __init__(self, workspace_dir: Path):
        super().__init__(workspace_dir=workspace_dir)
        self.submit_calls: List[Dict[str, Any]] = []
        self.set_tasklist_calls: List[Dict[str, Any]] = []
        self._tasklist_data: Dict[str, Any] = {"bigPicture": None, "tasks": []}

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    async def create_session(self, name: str = "TUI Session") -> str:
        self.session_id = "session-1"
        return self.session_id

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        return True

    def check_alive(self) -> bool:
        return True

    async def get_health_live(self) -> Dict[str, Any]:
        return {"version": "test", "protocolVersion": "1", "execId": "test-id"}

    async def get_context(self) -> Dict[str, Any]:
        return {"fragments": [], "usedTokens": 0, "maxTokens": 100000, "branch": "main"}

    async def get_tasklist(self) -> Dict[str, Any]:
        return self._tasklist_data

    async def set_tasklist(self, tasklist_data: Dict[str, Any]) -> Dict[str, Any]:
        self.set_tasklist_calls.append(tasklist_data)
        self._tasklist_data = tasklist_data
        return tasklist_data

    async def submit_job(
        self,
        task_input: str,
        planner_model: str,
        code_model: Optional[str] = None,
        reasoning_level: Optional[str] = None,
        reasoning_level_code: Optional[str] = None,
        mode: str = "LUTZ",
        tags: Optional[Dict[str, str]] = None,
        session_id: Optional[str] = None,
        auto_commit: bool = True,
        skip_verification: Optional[bool] = None,
        max_issue_fix_attempts: Optional[int] = None,
    ) -> str:
        self.submit_calls.append(
            {
                "task_input": task_input,
                "planner_model": planner_model,
                "code_model": code_model,
                "reasoning_level": reasoning_level,
                "reasoning_level_code": reasoning_level_code,
                "mode": mode,
                "auto_commit": auto_commit,
            }
        )
        return "job-1"

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        yield {"type": "LLM_TOKEN", "data": {"token": "done", "isTerminal": True}}

    async def cancel_job(self, job_id: str) -> None:
        pass


def _close_coro(coro):
    """Helper to immediately close background coroutines started by run_worker."""
    coro.close()


@pytest.mark.asyncio
async def test_run_selected_task_submits_job_with_task_text(tmp_path: Path) -> None:
    """Verify that running a task submits a job with the task's text."""
    executor = StubExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False},
            {"id": "task-2", "title": "Second Task", "text": "Do the second thing", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"
    app.code_model = "test-code-model"
    app.reasoning_level = "low"
    app.reasoning_level_code = "disable"
    app.auto_commit = True

    # Simulate the selected task
    task = {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False}

    await app._run_selected_task(task)

    # Verify submit was called with correct parameters
    assert len(executor.submit_calls) == 1
    call = executor.submit_calls[0]
    assert call["task_input"] == "Do the first thing"
    assert call["planner_model"] == "test-model"
    assert call["code_model"] == "test-code-model"
    assert call["mode"] == "CODE"
    assert call["auto_commit"] is True


@pytest.mark.asyncio
async def test_run_selected_task_falls_back_to_title_when_text_empty(tmp_path: Path) -> None:
    """Verify that running a task uses title when text is empty."""
    executor = StubExecutor(tmp_path)

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    # Task with empty text
    task = {"id": "task-1", "title": "Use this title", "text": "", "done": False}

    await app._run_selected_task(task)

    assert len(executor.submit_calls) == 1
    assert executor.submit_calls[0]["task_input"] == "Use this title"


@pytest.mark.asyncio
async def test_run_selected_task_marks_task_done_on_success(tmp_path: Path) -> None:
    """Verify that the task is marked done after successful execution."""
    executor = StubExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False},
            {"id": "task-2", "title": "Second Task", "text": "Do the second thing", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    task = {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False}

    await app._run_selected_task(task)

    # Verify set_tasklist was called
    assert len(executor.set_tasklist_calls) == 1
    saved_data = executor.set_tasklist_calls[0]

    # Find the task and verify it's marked done
    tasks = saved_data.get("tasks", [])
    task_1 = next((t for t in tasks if t.get("id") == "task-1"), None)
    task_2 = next((t for t in tasks if t.get("id") == "task-2"), None)

    assert task_1 is not None
    assert task_1["done"] is True

    # Other tasks should remain unchanged
    assert task_2 is not None
    assert task_2["done"] is False


@pytest.mark.asyncio
async def test_run_selected_task_does_not_mark_done_on_failure(tmp_path: Path) -> None:
    """Verify that the task is NOT marked done if execution fails."""

    class FailingExecutor(StubExecutor):
        async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
            raise RuntimeError("Simulated failure")
            yield  # Make this a generator

    executor = FailingExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    task = {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False}

    await app._run_selected_task(task)

    # set_tasklist should NOT be called since the job failed
    assert len(executor.set_tasklist_calls) == 0


def test_action_task_run_dispatches_worker_when_task_selected() -> None:
    """Verify that action_task_run dispatches the worker when a task is selected."""
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = True
    app.job_in_progress = False

    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = {
        "id": "1",
        "title": "Test",
        "text": "Test task",
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

    app.action_task_run()

    assert app.run_worker.call_count == 1
    worker_coro = app.run_worker.call_args.args[0]
    assert worker_coro.__name__ == "_run_selected_task"


def test_action_task_run_shows_message_when_no_task_selected() -> None:
    """Verify that action_task_run shows a message when no task is selected."""
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = True
    app.job_in_progress = False

    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = None

    def query_one(target, *args, **kwargs):
        if target is ChatPanel:
            return mock_chat
        if target == "#side-tasklist":
            return mock_panel
        raise AssertionError(f"Unexpected query target: {target}")

    app.query_one = MagicMock(side_effect=query_one)
    app.run_worker = MagicMock(side_effect=_close_coro)

    app.action_task_run()

    mock_chat.add_system_message.assert_called_once_with("No task selected.")
    app.run_worker.assert_not_called()


def test_action_task_run_blocked_when_job_in_progress() -> None:
    """Verify that action_task_run is blocked when a job is already running."""
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = True
    app.job_in_progress = True

    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = {
        "id": "1",
        "title": "Test",
        "text": "Test task",
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

    app.action_task_run()

    mock_chat.add_system_message.assert_called_once()
    call_args = mock_chat.add_system_message.call_args
    assert "already in progress" in call_args.args[0]
    app.run_worker.assert_not_called()


def test_action_task_run_blocked_when_executor_not_ready() -> None:
    """Verify that action_task_run is blocked when executor is not ready."""
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = False
    app.job_in_progress = False

    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = {
        "id": "1",
        "title": "Test",
        "text": "Test task",
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

    app.action_task_run()

    mock_chat.add_system_message.assert_called_once()
    call_args = mock_chat.add_system_message.call_args
    assert "not ready" in call_args.args[0]
    app.run_worker.assert_not_called()
