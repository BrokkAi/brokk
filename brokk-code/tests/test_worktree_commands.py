import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.worktrees import WorktreeInfo


@pytest.mark.asyncio
async def test_worktree_commands_in_catalog():
    """Verify worktree commands are consolidated under /worktree."""
    commands = BrokkApp.get_slash_commands()
    cmds_only = {c["command"] for c in commands}
    assert "/worktree" in cmds_only
    assert "/worktree-add" not in cmds_only
    assert "/worktree-remove" not in cmds_only


@pytest.mark.asyncio
async def test_worktree_add_flow(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    app = BrokkApp(workspace_dir=repo)
    app.worktree_service = MagicMock()
    app.worktree_service.repo_root = repo
    app.worktree_service.add_worktree = MagicMock(return_value=True)

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)
    app.push_screen = MagicMock()

    # Trigger add workflow directly (selected from /worktree menu)
    app._worktree_add_workflow()
    assert app.push_screen.call_count == 1
    callback = app.push_screen.call_args[0][1]

    # 2. Simulate user entering 'feature-x'
    with patch.object(app, "_switch_to_worktree", new_callable=AsyncMock) as mock_switch:
        callback("feature-x")
        # Give worker time to run
        await asyncio.sleep(0.1)

        app.worktree_service.add_worktree.assert_called_once()
        args = app.worktree_service.add_worktree.call_args[0]
        assert "feature-x" in str(args[0])
        mock_switch.assert_not_called()


@pytest.mark.asyncio
async def test_worktree_add_absolute_path_does_not_set_branch(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    target = (tmp_path / "custom-wt").resolve()

    app = BrokkApp(workspace_dir=repo)
    app.worktree_service = MagicMock()
    app.worktree_service.repo_root = repo
    app.worktree_service.add_worktree = MagicMock(return_value=True)
    app._maybe_chat = MagicMock(return_value=MagicMock())

    with patch.object(app, "_switch_to_worktree", new_callable=AsyncMock) as mock_switch:
        await app._perform_worktree_add(str(target))

        args, kwargs = app.worktree_service.add_worktree.call_args
        assert args[0] == target
        assert "branch" not in kwargs
        mock_switch.assert_not_called()


@pytest.mark.asyncio
async def test_worktree_remove_flow(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    wt_path = tmp_path / "wt-other"
    wt_path.mkdir()

    app = BrokkApp(workspace_dir=repo)
    app.worktree_service = MagicMock()
    app.worktree_service.list_worktrees = MagicMock(
        return_value=[WorktreeInfo(repo, "main", "123"), WorktreeInfo(wt_path, "other", "456")]
    )
    app.worktree_service.remove_worktree = MagicMock(return_value=True)

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)
    app.push_screen = MagicMock()

    await app._perform_worktree_remove(wt_path)

    app.worktree_service.remove_worktree.assert_called_once_with(wt_path)
    assert any("Removed worktree" in str(c) for c in chat.add_system_message.call_args_list)


@pytest.mark.asyncio
async def test_worktree_command_rejects_subcommands(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    app = BrokkApp(workspace_dir=repo)
    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    app._handle_command("/worktree add")

    chat.add_system_message.assert_called_once()
    assert "Usage: /worktree" in str(chat.add_system_message.call_args)


@pytest.mark.asyncio
async def test_worktree_switch_not_ready_fallback(tmp_path):
    """Verify switching to a worktree starts a new executor if not present."""
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    app._start_executor = AsyncMock()

    # Switch to B
    await app._switch_to_worktree(path_b)

    assert app.current_worktree == path_b
    app._start_executor.assert_called_once_with(path_b)
