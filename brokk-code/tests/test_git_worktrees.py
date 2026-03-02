import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from brokk_code.git_worktrees import (
    WorktreeInfo,
    add_worktree,
    get_main_worktree_path,
    get_worktree_display_name,
    list_worktrees,
    next_worktree_path,
    remove_worktree,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_TWO_WORKTREE_PORCELAIN = (
    "worktree /repo/main\n"
    "HEAD 1234567890abcdef1234567890abcdef12345678\n"
    "branch refs/heads/main\n"
    "\n"
    "worktree /repo/wt1\n"
    "HEAD abcdef1234567890abcdef1234567890abcdef12\n"
    "detached\n"
)

_SINGLE_WORKTREE_PORCELAIN = (
    "worktree /repo/only\nHEAD deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\nbranch refs/heads/only\n"
)

_EMPTY_PORCELAIN = ""


def _make_run(stdout: str = "", returncode: int = 0):
    return MagicMock(stdout=stdout, returncode=returncode)


# ---------------------------------------------------------------------------
# list_worktrees
# ---------------------------------------------------------------------------


def test_list_worktrees_two_worktrees(tmp_path):
    """Two-worktree fixture: one regular on main, one detached HEAD."""
    workspace = tmp_path / "main"
    workspace.mkdir()

    # Build porcelain output using the real tmp_path so resolve() works naturally
    wt1_path = str(workspace)
    wt2_path = str(tmp_path / "wt1")
    porcelain = (
        f"worktree {wt1_path}\n"
        "HEAD 1234567890abcdef1234567890abcdef12345678\n"
        "branch refs/heads/main\n"
        "\n"
        f"worktree {wt2_path}\n"
        "HEAD abcdef1234567890abcdef1234567890abcdef12\n"
        "detached\n"
    )

    with patch("subprocess.run", return_value=_make_run(porcelain)):
        wts = list_worktrees(workspace)

    assert len(wts) == 2

    assert wts[0].path == Path(wt1_path).absolute()
    assert wts[0].branch == "main"
    assert wts[0].commit_id == "1234567890abcdef1234567890abcdef12345678"
    assert wts[0].is_current is True
    assert wts[0].is_bare is False
    assert wts[0].is_locked is False

    assert wts[1].path == Path(wt2_path).absolute()
    assert wts[1].branch is None
    assert wts[1].commit_id == "abcdef1234567890abcdef1234567890abcdef12"
    assert wts[1].is_current is False


def test_list_worktrees_is_current_on_second(tmp_path):
    """is_current should be True for the entry matching workspace_dir.resolve()."""
    wt1_path = tmp_path / "main"
    wt2_path = tmp_path / "wt1"
    wt1_path.mkdir()

    porcelain = (
        f"worktree {wt1_path}\n"
        "HEAD aaa\n"
        "branch refs/heads/main\n"
        "\n"
        f"worktree {wt2_path}\n"
        "HEAD bbb\n"
        "branch refs/heads/feature\n"
    )

    # Pass wt2_path as workspace_dir -> wt2 should be is_current
    with patch("subprocess.run", return_value=_make_run(porcelain)):
        wts = list_worktrees(wt2_path)

    assert wts[0].is_current is False
    assert wts[1].is_current is True


def test_list_worktrees_single(tmp_path):
    workspace = tmp_path / "only"
    workspace.mkdir()

    porcelain = f"worktree {workspace}\nHEAD deadbeef\nbranch refs/heads/only\n"

    with patch("subprocess.run", return_value=_make_run(porcelain)):
        wts = list_worktrees(workspace)

    assert len(wts) == 1
    assert wts[0].branch == "only"
    assert wts[0].is_current is True


def test_list_worktrees_empty():
    with patch("subprocess.run", return_value=_make_run("")):
        wts = list_worktrees(Path("/nonexistent"))
    assert wts == []


def test_list_worktrees_raises_on_git_failure():
    with patch(
        "subprocess.run",
        side_effect=subprocess.CalledProcessError(128, "git"),
    ):
        with pytest.raises(subprocess.CalledProcessError):
            list_worktrees(Path("/repo"))


# ---------------------------------------------------------------------------
# get_main_worktree_path
# ---------------------------------------------------------------------------


def test_get_main_worktree_path(tmp_path):
    workspace = tmp_path / "main"
    workspace.mkdir()
    wt2 = tmp_path / "wt1"

    porcelain = (
        f"worktree {workspace}\n"
        "HEAD aaa\n"
        "branch refs/heads/main\n"
        "\n"
        f"worktree {wt2}\n"
        "HEAD bbb\n"
        "detached\n"
    )
    with patch("subprocess.run", return_value=_make_run(porcelain)):
        result = get_main_worktree_path(workspace)

    assert result == workspace.absolute()


# ---------------------------------------------------------------------------
# get_worktree_display_name
# ---------------------------------------------------------------------------


def test_get_worktree_display_name_with_branch():
    info = WorktreeInfo(Path("/repo/wt1"), "feature-x", "sha", False, False, False)
    assert get_worktree_display_name(info) == "feature-x"


def test_get_worktree_display_name_detached():
    info = WorktreeInfo(Path("/repo/some-dir"), None, "sha", False, False, False)
    assert get_worktree_display_name(info) == "some-dir"


# ---------------------------------------------------------------------------
# next_worktree_path
# ---------------------------------------------------------------------------


def test_next_worktree_path_none_exist(tmp_path):
    main_path = tmp_path / "main"
    result = next_worktree_path(main_path)
    assert result.name == "wt1"
    assert result.parent == tmp_path / "brokk-worktrees"


def test_next_worktree_path_some_exist(tmp_path):
    main_path = tmp_path / "main"
    storage = tmp_path / "brokk-worktrees"
    storage.mkdir()
    (storage / "wt1").mkdir()
    (storage / "wt2").mkdir()

    result = next_worktree_path(main_path)
    assert result.name == "wt3"


def test_next_worktree_path_gaps_not_skipped(tmp_path):
    """Picks the first non-existent; wt1 missing means wt1 is returned."""
    main_path = tmp_path / "main"
    storage = tmp_path / "brokk-worktrees"
    storage.mkdir()
    (storage / "wt2").mkdir()

    result = next_worktree_path(main_path)
    assert result.name == "wt1"


# ---------------------------------------------------------------------------
# add_worktree
# ---------------------------------------------------------------------------


def test_add_worktree_existing_branch(tmp_path):
    workspace = tmp_path / "main"
    wt_path = tmp_path / "brokk-worktrees" / "wt1"
    branch = "existing-branch"

    with patch("subprocess.run") as mock_run:
        mock_run.side_effect = [
            MagicMock(returncode=0),  # rev-parse succeeds
            MagicMock(returncode=0),  # worktree add
        ]
        add_worktree(workspace, branch, wt_path)

    calls = mock_run.call_args_list
    assert len(calls) == 2

    add_call_args = calls[1][0][0]
    assert "-b" not in add_call_args
    assert str(wt_path) in add_call_args
    assert branch in add_call_args


def test_add_worktree_new_branch(tmp_path):
    workspace = tmp_path / "main"
    wt_path = tmp_path / "brokk-worktrees" / "wt1"
    branch = "new-branch"

    with patch("subprocess.run") as mock_run:
        mock_run.side_effect = [
            subprocess.CalledProcessError(128, "rev-parse"),  # rev-parse fails
            MagicMock(returncode=0),  # worktree add -b
        ]
        add_worktree(workspace, branch, wt_path)

    calls = mock_run.call_args_list
    assert len(calls) == 2

    add_call_args = calls[1][0][0]
    assert "-b" in add_call_args
    assert str(wt_path) in add_call_args
    assert branch in add_call_args


def test_add_worktree_creates_parent(tmp_path):
    workspace = tmp_path / "main"
    storage = tmp_path / "brokk-worktrees"
    wt_path = storage / "wt1"
    branch = "feat"

    assert not storage.exists()

    with patch("subprocess.run") as mock_run:
        mock_run.side_effect = [
            subprocess.CalledProcessError(128, "rev-parse"),
            MagicMock(returncode=0),
        ]
        add_worktree(workspace, branch, wt_path)

    assert storage.exists()


# ---------------------------------------------------------------------------
# remove_worktree
# ---------------------------------------------------------------------------


def test_remove_worktree_plain():
    workspace = Path("/repo/main")
    wt_path = Path("/repo/wt1")

    with patch("subprocess.run") as mock_run:
        remove_worktree(workspace, wt_path, force=False)
        mock_run.assert_called_once_with(
            ["git", "-C", str(workspace), "worktree", "remove", str(wt_path)],
            check=True,
            capture_output=True,
            text=True,
        )


def test_remove_worktree_force():
    workspace = Path("/repo/main")
    wt_path = Path("/repo/wt1")

    with patch("subprocess.run") as mock_run:
        remove_worktree(workspace, wt_path, force=True)
        mock_run.assert_called_once_with(
            [
                "git",
                "-C",
                str(workspace),
                "worktree",
                "remove",
                "--force",
                "--force",
                str(wt_path),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
