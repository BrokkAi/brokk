import subprocess

import pytest

from brokk_code.worktrees import WorktreeService


@pytest.fixture
def git_repo(tmp_path):
    """Creates a temporary git repository."""
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init"], cwd=str(repo), check=True)
    (repo / "file.txt").write_text("hello")
    subprocess.run(["git", "add", "file.txt"], cwd=str(repo), check=True)
    subprocess.run(["git", "commit", "-m", "initial"], cwd=str(repo), check=True)
    return repo


def test_find_repo_root(git_repo):
    service = WorktreeService(git_repo)
    assert WorktreeService.find_repo_root(git_repo) == git_repo

    sub = git_repo / "subdir"
    sub.mkdir()
    assert WorktreeService.find_repo_root(sub) == git_repo


def test_list_worktrees(git_repo):
    service = WorktreeService(git_repo)
    worktrees = service.list_worktrees()
    assert len(worktrees) == 1
    assert worktrees[0].path == git_repo


def test_add_remove_worktree(git_repo, tmp_path):
    service = WorktreeService(git_repo)
    wt_path = tmp_path / "wt1"

    # Add
    success = service.add_worktree(wt_path, branch="new-branch")
    assert success is True
    assert wt_path.exists()

    worktrees = service.list_worktrees()
    assert any(w.path == wt_path for w in worktrees)

    # Remove
    success = service.remove_worktree(wt_path)
    assert success is True
    assert not wt_path.exists()
