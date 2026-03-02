from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


@pytest.mark.asyncio
async def test_switch_to_worktree_already_current():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value

    target = Path("/repo/main")
    # Resolve uses real filesystem usually, so we mock it to ensure equality in test
    with patch.object(Path, "resolve", return_value=Path("/repo/main")):
        await app._switch_to_worktree(target)

    mock_chat.add_system_message.assert_called_with(f"Already in worktree: {target}")


@pytest.mark.asyncio
async def test_switch_to_worktree_lazy_start():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    app._refresh_context_panel = AsyncMock()
    app._replay_conversation_entries = MagicMock()

    new_path = Path("/repo/feat1")
    resolved_new = Path("/repo/feat1")
    resolved_old = Path("/repo/main")

    mock_new_exec = MagicMock(spec=ExecutorManager)
    mock_new_exec.workspace_dir = new_path
    mock_new_exec.start = AsyncMock()
    mock_new_exec.create_session = AsyncMock()
    mock_new_exec.wait_ready = AsyncMock(return_value=True)
    mock_new_exec.get_conversation = AsyncMock(return_value={"entries": []})

    with patch.object(Path, "resolve", side_effect=[resolved_new, resolved_old, resolved_new]):
        with patch.object(app, "_make_executor", return_value=mock_new_exec):
            await app._switch_to_worktree(new_path)

            assert app.executor == mock_new_exec
            mock_new_exec.start.assert_called_once()
            assert app._worktree_executors[resolved_new] == mock_new_exec


@pytest.mark.asyncio
async def test_remove_worktree_refuses_current():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value

    path = Path("/repo/main")
    with patch.object(Path, "resolve", return_value=path):
        await app._remove_worktree(path)

    mock_chat.add_system_message.assert_called_with(
        "Cannot remove the active worktree.", level="ERROR"
    )


@pytest.mark.asyncio
async def test_shutdown_stops_all_executors():
    app = BrokkApp(workspace_dir=Path("/repo/main"))

    mock_exec1 = app.executor
    mock_exec1.stop = AsyncMock()

    mock_exec2 = MagicMock(spec=ExecutorManager)
    mock_exec2.workspace_dir = Path("/repo/feat1")
    mock_exec2.stop = AsyncMock()

    app._worktree_executors[Path("/repo/feat1").resolve()] = mock_exec2

    await app._shutdown_once(show_message=False)

    mock_exec1.stop.assert_called()
    mock_exec2.stop.assert_called()
