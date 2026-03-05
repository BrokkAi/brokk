import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_prompt_routing_isolation(tmp_path):
    """Verify that prompts only trigger jobs on the active worktree's executor."""
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    rt_a = app.current_runtime
    rt_b = app.get_runtime(path_b)

    # Setup mocks
    rt_a.executor = MagicMock()
    rt_a.executor.workspace_dir = path_a
    rt_a.executor_ready = True
    rt_a.executor.submit_job = AsyncMock(return_value="job-a")
    rt_a.executor.stream_events = MagicMock(return_value=AsyncMock())

    rt_b.executor = MagicMock()
    rt_b.executor.workspace_dir = path_b
    rt_b.executor_ready = True
    rt_b.executor.submit_job = AsyncMock(return_value="job-b")
    rt_b.executor.stream_events = MagicMock(return_value=AsyncMock())

    app._maybe_chat = MagicMock(return_value=MagicMock())

    # 1. Submit prompt to current worktree (A)
    await app._run_job("Prompt for A")
    rt_a.executor.submit_job.assert_called_once()
    rt_b.executor.submit_job.assert_not_called()

    rt_a.executor.submit_job.reset_mock()

    # 2. Switch "current" and submit prompt
    app.current_worktree = path_b
    await app._run_job("Prompt for B")
    rt_b.executor.submit_job.assert_called_once()
    rt_a.executor.submit_job.assert_not_called()


@pytest.mark.asyncio
async def test_context_refresh_isolation(tmp_path):
    """Verify context refresh only targets the selected worktree's executor."""
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    rt_a = app.current_runtime
    rt_b = app.get_runtime(path_b)

    rt_a.executor = MagicMock()
    rt_a.executor_ready = True
    rt_a.executor.get_context = AsyncMock(return_value={"usedTokens": 100})

    rt_b.executor = MagicMock()
    rt_b.executor_ready = True
    rt_b.executor.get_context = AsyncMock(return_value={"usedTokens": 200})

    app._maybe_chat = MagicMock(return_value=MagicMock())

    # Refresh while A is current
    await app._refresh_context_panel()
    rt_a.executor.get_context.assert_called_once()
    rt_b.executor.get_context.assert_not_called()

    rt_a.executor.get_context.reset_mock()

    # Refresh specifically for B
    await app._refresh_context_panel(path_b)
    rt_b.executor.get_context.assert_called_once()
    rt_a.executor.get_context.assert_not_called()
