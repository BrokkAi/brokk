from pathlib import Path
from typing import Optional


def resolve_workspace_dir(path: Path, worktree_path: Optional[Path] = None) -> Path:
    """
    Resolve a workspace path.
    If worktree_path is provided, it is returned (normalized).
    Otherwise, resolves to the nearest git repository root.
    """
    if worktree_path:
        return worktree_path.resolve()

    resolved = path.resolve()
    current = resolved if resolved.is_dir() else resolved.parent

    for candidate in (current, *current.parents):
        git_path = candidate / ".git"
        if git_path.is_dir() or git_path.is_file():
            return candidate

    return resolved
