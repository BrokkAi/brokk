import asyncio
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_runtime_isolation_per_path(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)

    # Verify primary workspace is used initially
    assert app.current_worktree == path_a
    runtime_a = app.current_runtime
    assert runtime_a.executor.workspace_dir == path_a

    # Get another runtime
    runtime_b = app.get_runtime(path_b)
    assert runtime_b.executor.workspace_dir == path_b
    assert runtime_a is not runtime_b

    # Verify state isolation
    runtime_a.session_id = "session-a"
    runtime_b.session_id = "session-b"
    runtime_a.job_in_progress = True
    runtime_a.current_job_id = "job-a"
    runtime_a.pending_prompt = "prompt-a"
    runtime_a.pending_switch_prompt = ("sid-a", "ps-a")

    assert app.get_runtime(path_a).session_id == "session-a"
    assert app.get_runtime(path_b).session_id == "session-b"
    assert app.get_runtime(path_b).job_in_progress is False
    assert app.get_runtime(path_a).job_in_progress is True
    assert app.get_runtime(path_a).current_job_id == "job-a"
    assert app.get_runtime(path_b).current_job_id is None
    assert app.get_runtime(path_a).pending_prompt == "prompt-a"
    assert app.get_runtime(path_b).pending_prompt is None
    assert app.get_runtime(path_a).pending_switch_prompt == ("sid-a", "ps-a")
    assert app.get_runtime(path_b).pending_switch_prompt is None

    # Verify executor handle identity
    assert runtime_a.executor is not runtime_b.executor
    assert runtime_a.executor.workspace_dir == path_a
    assert runtime_b.executor.workspace_dir == path_b


def test_default_workspace_used_when_none_provided():
    # Use current working directory if no workspace is provided
    cwd = Path.cwd().resolve()
    # resolve_workspace_dir might climb up to a git root, so we just check it exists
    app = BrokkApp()
    assert app.current_worktree.exists()
    assert app.current_runtime.executor.workspace_dir == app.current_worktree


@pytest.mark.asyncio
async def test_shutdown_stops_all_runtimes(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    rt_a = app.current_runtime
    rt_b = app.get_runtime(path_b)

    # Mock executors
    f_a = asyncio.Future()
    f_a.set_result(None)
    f_b = asyncio.Future()
    f_b.set_result(None)
    rt_a.executor.stop = MagicMock(return_value=f_a)
    rt_b.executor.stop = MagicMock(return_value=f_b)

    await app._shutdown_once(show_message=False)

    assert rt_a.executor.stop.called
    assert rt_b.executor.stop.called
