import pytest
from pathlib import Path
from brokk_code.app import BrokkApp, WorktreeSelectModal
from brokk_code.git_worktrees import WorktreeInfo


def test_worktrees_command_in_catalog():
    commands = BrokkApp.get_slash_commands()
    worktree_cmd = next((c for c in commands if c["command"] == "/worktrees"), None)
    assert worktree_cmd is not None
    assert "worktrees" in worktree_cmd["description"].lower()


def test_worktree_select_modal_instantiation():
    sample_worktrees = [
        WorktreeInfo(
            path=Path("/repo/main"),
            branch="main",
            commit_id="123456",
            is_current=True,
            is_bare=False,
            is_locked=False,
        ),
        WorktreeInfo(
            path=Path("/repo/feat1"),
            branch="feature-1",
            commit_id="abcdef",
            is_current=False,
            is_bare=False,
            is_locked=False,
        ),
    ]
    modal = WorktreeSelectModal(sample_worktrees)
    assert modal.worktrees == sample_worktrees
    assert len(modal._item_id_to_path) == 2
    assert modal._item_id_to_path["wt-0"] == Path("/repo/main")
    assert modal._item_id_to_path["wt-1"] == Path("/repo/feat1")


@pytest.mark.asyncio
async def test_worktree_select_modal_compose():
    sample_worktrees = [
        WorktreeInfo(
            path=Path("/repo/main"),
            branch="main",
            commit_id="123456",
            is_current=True,
            is_bare=False,
            is_locked=False,
        )
    ]
    app = BrokkApp()
    async with app.run_test() as pilot:
        modal = WorktreeSelectModal(sample_worktrees)
        # Just ensure it can be pushed and composed without crashing
        await app.push_screen(modal)
        assert modal.is_current
