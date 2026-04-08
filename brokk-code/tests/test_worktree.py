import re
import subprocess
from pathlib import Path

from brokk_code.git_utils import (
    _generate_worktree_name,
    _worktree_has_changes,
    worktree_context,
)


def _init_git_repo(path):
    """Create a minimal git repo with one commit."""
    subprocess.run(["git", "init", str(path)], check=True, capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "test@test.com"],
        cwd=str(path),
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Test"],
        cwd=str(path),
        check=True,
        capture_output=True,
    )
    (path / "README").write_text("init")
    subprocess.run(["git", "add", "."], cwd=str(path), check=True, capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", "initial"],
        cwd=str(path),
        check=True,
        capture_output=True,
    )


def test_worktree_name_pattern():
    """Generated names match adjective-adjective-noun pattern."""
    for _ in range(20):
        name = _generate_worktree_name()
        assert re.fullmatch(r"[a-z]+-[a-z]+-[a-z]+", name), f"Bad name: {name}"


def test_worktree_creates_and_removes_clean(tmp_path):
    """A worktree with no changes is removed on exit."""
    _init_git_repo(tmp_path)

    worktree_path = None
    with worktree_context(tmp_path) as wt:
        worktree_path = wt
        assert worktree_path.exists()
        assert (worktree_path / ".git").exists()
        assert (worktree_path / "README").read_text() == "init"

    assert not worktree_path.exists()


def test_worktree_preserves_dirty(tmp_path):
    """A worktree with uncommitted changes is left in place."""
    _init_git_repo(tmp_path)

    worktree_path = None
    with worktree_context(tmp_path) as wt:
        worktree_path = wt
        (worktree_path / "new_file.txt").write_text("dirty")

    assert worktree_path.exists()
    assert (worktree_path / "new_file.txt").read_text() == "dirty"


def test_worktree_flag_in_parser():
    """The --worktree flag is accepted by the CLI parser."""
    from brokk_code.__main__ import _build_parser

    parser = _build_parser()
    args, _ = parser.parse_known_args(["--worktree"])
    assert args.worktree is True

    args_default, _ = parser.parse_known_args([])
    assert args_default.worktree is False


def test_worktree_preserves_relative_subdirectory_workspace():
    """Nested workspaces should map to the same relative path in the worktree."""
    from brokk_code.__main__ import _resolve_worktree_workspace_path

    repo_root = Path("/repo")
    workspace_path = repo_root / "nested" / "workspace"
    worktree_path = Path("/repo/.brokk/worktrees/pure-bold-arch")

    assert _resolve_worktree_workspace_path(workspace_path, repo_root, worktree_path) == (
        worktree_path / "nested" / "workspace"
    )


def test_worktree_has_changes_clean(tmp_path):
    """A fresh repo with no modifications reports no changes."""
    _init_git_repo(tmp_path)
    assert not _worktree_has_changes(tmp_path)


def test_worktree_has_changes_dirty(tmp_path):
    """An untracked file is detected as a change."""
    _init_git_repo(tmp_path)
    (tmp_path / "untracked.txt").write_text("hello")
    assert _worktree_has_changes(tmp_path)
