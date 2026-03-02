import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional


@dataclass
class WorktreeInfo:
    path: Path
    branch: Optional[str]
    commit_id: str
    is_current: bool
    is_bare: bool
    is_locked: bool


def list_worktrees(workspace_dir: Path) -> List[WorktreeInfo]:
    """Runs git worktree list --porcelain and parses the output."""
    res = subprocess.run(
        ["git", "-C", str(workspace_dir), "worktree", "list", "--porcelain"],
        check=True,
        capture_output=True,
        text=True,
    )

    worktrees = []
    current_block: dict = {}
    resolved_workspace = workspace_dir.resolve()

    for line in res.stdout.splitlines():
        line = line.strip()
        if not line:
            if current_block:
                worktrees.append(_parse_block(current_block, resolved_workspace))
                current_block = {}
            continue

        parts = line.split(" ", 1)
        key = parts[0]
        value = parts[1] if len(parts) > 1 else ""
        current_block[key] = value

    if current_block:
        worktrees.append(_parse_block(current_block, resolved_workspace))

    return worktrees


def _parse_block(block: dict, resolved_workspace: Path) -> WorktreeInfo:
    path = Path(block["worktree"]).absolute()
    branch: Optional[str] = None
    if "branch" in block:
        raw = block["branch"]
        branch = raw[len("refs/heads/") :] if raw.startswith("refs/heads/") else raw

    return WorktreeInfo(
        path=path,
        branch=branch,
        commit_id=block.get("HEAD", ""),
        is_current=path.resolve() == resolved_workspace,
        is_bare="bare" in block,
        is_locked="locked" in block,
    )


def get_main_worktree_path(workspace_dir: Path) -> Path:
    """Returns the path of the first worktree in the list."""
    worktrees = list_worktrees(workspace_dir)
    if not worktrees:
        return workspace_dir.absolute()
    return worktrees[0].path


def get_worktree_display_name(info: WorktreeInfo) -> str:
    """Returns info.branch if set; otherwise the basename of info.path."""
    return info.branch if info.branch else info.path.name


def next_worktree_path(main_path: Path) -> Path:
    """Returns the first non-existent path among storage_dir/wt1, wt2, ..."""
    storage_dir = main_path.parent / "brokk-worktrees"
    i = 1
    while True:
        candidate = storage_dir / f"wt{i}"
        if not candidate.exists():
            return candidate
        i += 1


def add_worktree(workspace_dir: Path, branch: str, worktree_path: Path) -> None:
    """Adds a new git worktree, creating the branch if it does not exist."""
    worktree_path.parent.mkdir(parents=True, exist_ok=True)

    branch_exists = True
    try:
        subprocess.run(
            ["git", "-C", str(workspace_dir), "rev-parse", "--verify", f"refs/heads/{branch}"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        branch_exists = False

    if branch_exists:
        cmd = ["git", "-C", str(workspace_dir), "worktree", "add", str(worktree_path), branch]
    else:
        cmd = ["git", "-C", str(workspace_dir), "worktree", "add", "-b", branch, str(worktree_path)]

    subprocess.run(cmd, check=True, capture_output=True, text=True)


def remove_worktree(workspace_dir: Path, worktree_path: Path, force: bool = False) -> None:
    """Removes a git worktree."""
    cmd = ["git", "-C", str(workspace_dir), "worktree", "remove"]
    if force:
        cmd.extend(["--force", "--force"])
    cmd.append(str(worktree_path))
    subprocess.run(cmd, check=True, capture_output=True, text=True)
