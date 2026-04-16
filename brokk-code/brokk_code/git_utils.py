import random
import re
import shutil
import subprocess
import sys
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Optional, Tuple

_GITHUB_HTTPS_REGEX = r"^https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?$"
_GITHUB_SSH_REGEX = r"^git@github\.com:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?$"


def infer_github_repo_from_remote(workspace_dir: Path) -> Tuple[Optional[str], Optional[str]]:
    """Infers GitHub owner and repo from the git remote 'origin' URL.

    Returns (owner, repo) if successfully parsed, otherwise (None, None).
    Supports HTTPS and SSH GitHub remote URL formats.
    """
    try:
        result = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            capture_output=True,
            text=True,
            timeout=10.0,
            cwd=str(workspace_dir),
        )
        if result.returncode != 0:
            return (None, None)

        remote_url = result.stdout.strip()
        if not remote_url:
            return (None, None)

        # Try HTTPS format: https://github.com/owner/repo.git
        https_match = re.match(_GITHUB_HTTPS_REGEX, remote_url)
        if https_match:
            return (https_match.group(1), https_match.group(2))

        # Try SSH format: git@github.com:owner/repo.git
        ssh_match = re.match(_GITHUB_SSH_REGEX, remote_url)
        if ssh_match:
            return (ssh_match.group(1), ssh_match.group(2))

        return (None, None)
    except Exception:
        return (None, None)


# --- Worktree support ---

_ADJECTIVES = [
    "pure",
    "bold",
    "calm",
    "cool",
    "dark",
    "deep",
    "fast",
    "fine",
    "firm",
    "glad",
    "gold",
    "keen",
    "kind",
    "lush",
    "mild",
    "neat",
    "pale",
    "pink",
    "rare",
    "rich",
    "ripe",
    "safe",
    "slim",
    "soft",
    "tall",
    "tidy",
    "trim",
    "true",
    "vast",
    "warm",
    "wide",
    "wild",
    "wise",
    "airy",
    "blue",
    "cozy",
    "deft",
    "fair",
    "gray",
    "hazy",
    "jade",
    "lazy",
    "navy",
    "opal",
    "plum",
    "rosy",
    "sage",
    "teal",
    "wavy",
    "zany",
]

_NOUNS = [
    "arch",
    "bell",
    "bird",
    "bolt",
    "cave",
    "claw",
    "dawn",
    "dove",
    "dust",
    "edge",
    "fern",
    "gate",
    "hare",
    "iris",
    "jade",
    "kite",
    "lake",
    "mint",
    "nest",
    "opal",
    "pear",
    "reed",
    "sage",
    "tide",
    "vale",
    "vine",
    "wave",
    "yarn",
    "reef",
    "leaf",
]


def _generate_worktree_name() -> str:
    """Generate an adjective-adjective-noun name for a worktree."""
    return f"{random.choice(_ADJECTIVES)}-{random.choice(_ADJECTIVES)}-{random.choice(_NOUNS)}"


def _get_current_branch_or_head(repo_dir: Path) -> str:
    """Get the current branch name, or HEAD commit hash if detached."""
    result = subprocess.run(
        ["git", "symbolic-ref", "--short", "HEAD"],
        capture_output=True,
        text=True,
        cwd=str(repo_dir),
        timeout=10.0,
    )
    if result.returncode == 0:
        return result.stdout.strip()
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        cwd=str(repo_dir),
        timeout=10.0,
    )
    return result.stdout.strip()


def _worktree_has_changes(worktree_dir: Path) -> bool:
    """Check if a worktree has uncommitted changes (staged, unstaged, or untracked)."""
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        capture_output=True,
        text=True,
        cwd=str(worktree_dir),
        timeout=10.0,
    )
    return bool(result.stdout.strip())


def _remove_worktree(repo_dir: Path, worktree_dir: Path) -> None:
    """Remove a git worktree and its directory."""
    subprocess.run(
        ["git", "worktree", "remove", "--force", str(worktree_dir)],
        capture_output=True,
        text=True,
        cwd=str(repo_dir),
        timeout=30.0,
    )
    if worktree_dir.exists():
        shutil.rmtree(worktree_dir, ignore_errors=True)


@contextmanager
def worktree_context(repo_dir: Path) -> Iterator[Path]:
    """Create a git worktree, yield its path, and clean up on exit if clean.

    The worktree is created at <repo_dir>/.brokk/worktrees/<name> from the
    current branch/HEAD in detached HEAD mode (avoids branch lock conflicts).

    On exit, the worktree is removed if it has no uncommitted changes;
    otherwise it is left in place and the path is printed to stderr.
    """
    name = _generate_worktree_name()
    worktrees_dir = repo_dir / ".brokk" / "worktrees"
    worktrees_dir.mkdir(parents=True, exist_ok=True)

    worktree_path = worktrees_dir / name
    while worktree_path.exists():
        name = _generate_worktree_name()
        worktree_path = worktrees_dir / name

    branch_or_commit = _get_current_branch_or_head(repo_dir)

    subprocess.run(
        ["git", "worktree", "add", "--detach", str(worktree_path), branch_or_commit],
        check=True,
        capture_output=True,
        text=True,
        cwd=str(repo_dir),
        timeout=30.0,
    )
    print(f"Created worktree: {worktree_path}", file=sys.stderr)

    try:
        yield worktree_path
    finally:
        try:
            if _worktree_has_changes(worktree_path):
                print(
                    f"Worktree has uncommitted changes, leaving in place: {worktree_path}",
                    file=sys.stderr,
                )
            else:
                _remove_worktree(repo_dir, worktree_path)
                print(f"Removed clean worktree: {worktree_path}", file=sys.stderr)
        except Exception:
            print(
                f"Warning: failed to clean up worktree at {worktree_path}",
                file=sys.stderr,
            )
