import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_multi_worktree_isolation_end_to_end(tmp_path):
    """
    End-to-end style test verifying that two worktrees maintain isolated
    executors, session IDs, and job status.
    """
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)

    # 1. Setup Runtime A
    rt_a = app.get_runtime(path_a)
    rt_a.executor = MagicMock()
    rt_a.executor.workspace_dir = path_a
    rt_a.executor_ready = True
    rt_a.session_id = "sid-a"

    # 2. Setup Runtime B
    rt_b = app.get_runtime(path_b)
    rt_b.executor = MagicMock()
    rt_b.executor.workspace_dir = path_b
    rt_b.executor_ready = True
    rt_b.session_id = "sid-b"

    # 3. Simulate job in A
    rt_a.job_in_progress = True
    rt_a.current_job_id = "job-a"

    # Verify B is still idle
    assert rt_b.job_in_progress is False
    assert rt_b.current_job_id is None

    # 4. Verify model settings are isolated
    rt_a.planner_model = "model-a"
    rt_b.planner_model = "model-b"

    app.current_worktree = path_a
    assert app.current_model == "model-a"

    app.current_worktree = path_b
    assert app.current_model == "model-b"


@pytest.mark.asyncio
async def test_worktree_switch_failure_path(tmp_path):
    """Verify negative path: switching to an invalid worktree."""
    repo = tmp_path / "repo"
    repo.mkdir()
    app = BrokkApp(workspace_dir=repo)

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    # Attempt to switch to a non-existent path
    invalid_path = tmp_path / "does-not-exist"

    # The app should start an executor for it anyway (it's a path)
    # but the executor start will fail later.
    with patch.object(app, "_start_executor", side_effect=Exception("Boot fail")):
        await app._switch_to_worktree(invalid_path)
        await asyncio.sleep(0.05)

        assert app.current_worktree == invalid_path


@pytest.mark.asyncio
async def test_worktree_cleanup_on_app_exit(tmp_path):
    """Verify all executors are stopped when the app quits."""
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    rt_a = app.get_runtime(path_a)
    rt_b = app.get_runtime(path_b)

    rt_a.executor = MagicMock()
    rt_b.executor = MagicMock()

    # Stop returns a future
    rt_a.executor.stop = AsyncMock()
    rt_b.executor.stop = AsyncMock()

    await app._shutdown_once(show_message=False)

    rt_a.executor.stop.assert_called_once()
    rt_b.executor.stop.assert_called_once()
