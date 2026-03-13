"""Tests for the 'Run selected task' execution flow."""

from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional
from unittest.mock import MagicMock

import pytest

from brokk_code.app import BrokkApp
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


@pytest.mark.parametrize(
    "executor_ready, job_in_progress, selected_task, expected_message_fragment",
    [
        pytest.param(True, False, None, "No task selected", id="no_task_selected"),
        pytest.param(
            True,
            True,
            {"id": "1", "title": "T", "text": "T", "done": False},
            "already in progress",
            id="job_in_progress",
        ),
        pytest.param(
            False,
            False,
            {"id": "1", "title": "T", "text": "T", "done": False},
            "not ready",
            id="executor_not_ready",
        ),
        pytest.param(
            True,
            False,
            {"id": "1", "title": "T", "text": "T", "done": True},
            "already done",
            id="task_already_done",
        ),
    ],
)
def test_action_task_run_guard_conditions(
    executor_ready: bool,
    job_in_progress: bool,
    selected_task,
    expected_message_fragment: str,
) -> None:
    """Verify that action_task_run rejects invalid states with appropriate messages."""
    app = BrokkApp(executor=MagicMock())
    app._executor_ready = executor_ready
    app.job_in_progress = job_in_progress

    mock_chat = MagicMock(spec=ChatPanel)
    mock_panel = MagicMock(spec=TaskListPanel)
    mock_panel.selected_task.return_value = selected_task

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
    assert expected_message_fragment in mock_chat.add_system_message.call_args.args[0]
    app.run_worker.assert_not_called()


@pytest.mark.asyncio
async def test_run_all_incomplete_tasks_executes_in_order(tmp_path: Path) -> None:
    """Verify that run_all executes tasks in order and marks each done."""
    executor = StubExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First", "text": "Do first", "done": False},
            {"id": "task-2", "title": "Second", "text": "Do second", "done": False},
            {"id": "task-3", "title": "Third", "text": "Do third", "done": True},  # Already done
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    await app._run_all_incomplete_tasks()

    # Should have submitted 2 jobs (skipping the done task)
    assert len(executor.submit_calls) == 2
    assert executor.submit_calls[0]["task_input"] == "Do first"
    assert executor.submit_calls[1]["task_input"] == "Do second"

    # Both incomplete tasks should now be marked done
    final_data = executor._tasklist_data
    tasks = final_data.get("tasks", [])
    task_1 = next((t for t in tasks if t.get("id") == "task-1"), None)
    task_2 = next((t for t in tasks if t.get("id") == "task-2"), None)
    task_3 = next((t for t in tasks if t.get("id") == "task-3"), None)

    assert task_1 is not None and task_1["done"] is True
    assert task_2 is not None and task_2["done"] is True
    assert task_3 is not None and task_3["done"] is True  # Was already done


@pytest.mark.asyncio
async def test_run_all_incomplete_tasks_stops_on_failure(tmp_path: Path) -> None:
    """Verify that run_all stops on first failure and leaves remaining tasks unchanged."""

    class FailingOnSecondExecutor(StubExecutor):
        def __init__(self, workspace_dir: Path):
            super().__init__(workspace_dir)
            self._job_count = 0

        async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
            self._job_count += 1
            if self._job_count == 2:
                raise RuntimeError("Simulated failure on second task")
            yield {"type": "LLM_TOKEN", "data": {"token": "done", "isTerminal": True}}

    executor = FailingOnSecondExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First", "text": "Do first", "done": False},
            {"id": "task-2", "title": "Second", "text": "Do second", "done": False},
            {"id": "task-3", "title": "Third", "text": "Do third", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    await app._run_all_incomplete_tasks()

    # Should have submitted 2 jobs (first succeeds, second fails, third never runs)
    assert len(executor.submit_calls) == 2

    # Only the first task should be marked done
    final_data = executor._tasklist_data
    tasks = final_data.get("tasks", [])
    task_1 = next((t for t in tasks if t.get("id") == "task-1"), None)
    task_2 = next((t for t in tasks if t.get("id") == "task-2"), None)
    task_3 = next((t for t in tasks if t.get("id") == "task-3"), None)

    assert task_1 is not None and task_1["done"] is True  # Succeeded
    assert task_2 is not None and task_2["done"] is False  # Failed
    assert task_3 is not None and task_3["done"] is False  # Never ran


@pytest.mark.asyncio
async def test_run_all_incomplete_tasks_persists_after_each_success(tmp_path: Path) -> None:
    """Verify that set_tasklist is called after each successful task."""
    executor = StubExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First", "text": "Do first", "done": False},
            {"id": "task-2", "title": "Second", "text": "Do second", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    await app._run_all_incomplete_tasks()

    # set_tasklist should be called twice (once after each task)
    assert len(executor.set_tasklist_calls) == 2

    # First call should have task-1 done, task-2 not done
    first_save = executor.set_tasklist_calls[0]
    first_tasks = first_save.get("tasks", [])
    first_task_1 = next((t for t in first_tasks if t.get("id") == "task-1"), None)
    first_task_2 = next((t for t in first_tasks if t.get("id") == "task-2"), None)
    assert first_task_1 is not None and first_task_1["done"] is True
    assert first_task_2 is not None and first_task_2["done"] is False

    # Second call should have both done
    second_save = executor.set_tasklist_calls[1]
    second_tasks = second_save.get("tasks", [])
    second_task_1 = next((t for t in second_tasks if t.get("id") == "task-1"), None)
    second_task_2 = next((t for t in second_tasks if t.get("id") == "task-2"), None)
    assert second_task_1 is not None and second_task_1["done"] is True
    assert second_task_2 is not None and second_task_2["done"] is True


@pytest.mark.asyncio
async def test_run_all_incomplete_tasks_no_incomplete(tmp_path: Path) -> None:
    """Verify that run_all handles no incomplete tasks gracefully."""
    executor = StubExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First", "text": "Do first", "done": True},
        ],
    }

    app = BrokkApp(executor=executor)
    app._executor_ready = True
    app.current_model = "test-model"

    await app._run_all_incomplete_tasks()

    # No jobs should be submitted
    assert len(executor.submit_calls) == 0


@pytest.mark.asyncio
async def test_run_all_incomplete_tasks_stops_on_cancellation(tmp_path: Path) -> None:
    """Verify that run_all stops when a task is cancelled and does not mark it done."""

    class CancellingExecutor(StubExecutor):
        """Executor that simulates cancellation during the first task."""

        def __init__(self, workspace_dir: Path, app_ref: Optional[Any] = None):
            super().__init__(workspace_dir)
            self._app_ref = app_ref

        def set_app(self, app: Any) -> None:
            self._app_ref = app

        async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
            # Simulate some progress, then cancellation is triggered
            yield {"type": "LLM_TOKEN", "data": {"token": "working", "isTerminal": False}}
            # Simulate the cancellation flag being set (as if Ctrl+C was pressed)
            if self._app_ref is not None:
                self._app_ref._task_run_cancelled = True
            # Yield terminal event
            yield {"type": "LLM_TOKEN", "data": {"token": "", "isTerminal": True}}

    executor = CancellingExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First", "text": "Do first", "done": False},
            {"id": "task-2", "title": "Second", "text": "Do second", "done": False},
            {"id": "task-3", "title": "Third", "text": "Do third", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    executor.set_app(app)
    app._executor_ready = True
    app.current_model = "test-model"

    await app._run_all_incomplete_tasks()

    # Only the first task should have been submitted (cancelled during execution)
    assert len(executor.submit_calls) == 1
    assert executor.submit_calls[0]["task_input"] == "Do first"

    # No tasks should be marked done since the first was cancelled
    final_data = executor._tasklist_data
    tasks = final_data.get("tasks", [])
    task_1 = next((t for t in tasks if t.get("id") == "task-1"), None)
    task_2 = next((t for t in tasks if t.get("id") == "task-2"), None)
    task_3 = next((t for t in tasks if t.get("id") == "task-3"), None)

    assert task_1 is not None and task_1["done"] is False  # Cancelled, not marked done
    assert task_2 is not None and task_2["done"] is False  # Never ran
    assert task_3 is not None and task_3["done"] is False  # Never ran

    # set_tasklist should NOT have been called since no task completed successfully
    assert len(executor.set_tasklist_calls) == 0


@pytest.mark.asyncio
async def test_run_selected_task_does_not_mark_done_on_cancellation(tmp_path: Path) -> None:
    """Verify that a single task is NOT marked done if cancelled."""

    class CancellingExecutor(StubExecutor):
        """Executor that simulates cancellation during task execution."""

        def __init__(self, workspace_dir: Path, app_ref: Optional[Any] = None):
            super().__init__(workspace_dir)
            self._app_ref = app_ref

        def set_app(self, app: Any) -> None:
            self._app_ref = app

        async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
            yield {"type": "LLM_TOKEN", "data": {"token": "working", "isTerminal": False}}
            # Simulate cancellation
            if self._app_ref is not None:
                self._app_ref._task_run_cancelled = True
            yield {"type": "LLM_TOKEN", "data": {"token": "", "isTerminal": True}}

    executor = CancellingExecutor(tmp_path)
    executor._tasklist_data = {
        "bigPicture": "Test goal",
        "tasks": [
            {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False},
        ],
    }

    app = BrokkApp(executor=executor)
    executor.set_app(app)
    app._executor_ready = True
    app.current_model = "test-model"

    task = {"id": "task-1", "title": "First Task", "text": "Do the first thing", "done": False}

    await app._run_selected_task(task)

    # Job was submitted
    assert len(executor.submit_calls) == 1

    # Task should NOT be marked done since it was cancelled
    assert len(executor.set_tasklist_calls) == 0
