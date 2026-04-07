from pathlib import Path


def resolve_workspace_dir(path: Path) -> Path:
    """Resolve a workspace path to its git repository root when applicable."""
    resolved = path.resolve()
    current = resolved if resolved.is_dir() else resolved.parent

    for candidate in (current, *current.parents):
        git_path = candidate / ".git"
        if git_path.is_dir() or git_path.is_file():
            return candidate

    return resolved
