import subprocess
import logging
from pathlib import Path
from typing import List, Optional, NamedTuple

logger = logging.getLogger(__name__)


class WorktreeInfo(NamedTuple):
    path: Path
    branch: Optional[str]
    commit_id: str


class WorktreeService:
    """Service for managing Git worktrees."""

    def __init__(self, repo_root: Path):
        self.repo_root = repo_root

    def list_worktrees(self) -> List[WorktreeInfo]:
        """Lists all worktrees in the repository."""
        try:
            result = subprocess.run(
                ["git", "worktree", "list", "--porcelain"],
                cwd=str(self.repo_root),
                capture_output=True,
                text=True,
                check=True,
            )

            worktrees = []
            current_worktree = {}

            for line in result.stdout.splitlines():
                if not line.strip():
                    if current_worktree:
                        worktrees.append(self._parse_worktree_dict(current_worktree))
                        current_worktree = {}
                    continue

                parts = line.split(" ", 1)
                if len(parts) == 2:
                    current_worktree[parts[0]] = parts[1]

            if current_worktree:
                worktrees.append(self._parse_worktree_dict(current_worktree))

            return worktrees
        except (subprocess.CalledProcessError, FileNotFoundError) as e:
            logger.debug("Failed to list worktrees: %s", e)
            return []

    def _parse_worktree_dict(self, d: dict) -> WorktreeInfo:
        path = Path(d.get("worktree", ""))
        branch = d.get("branch", "")
        if branch.startswith("refs/heads/"):
            branch = branch[len("refs/heads/") :]
        commit_id = d.get("HEAD", "")
        return WorktreeInfo(path=path, branch=branch or None, commit_id=commit_id)

    def add_worktree(
        self, path: Path, branch: Optional[str] = None, commitish: Optional[str] = None
    ) -> bool:
        """Adds a new worktree."""
        cmd = ["git", "worktree", "add", str(path)]
        if branch:
            cmd.extend(["-b", branch])
        if commitish:
            cmd.append(commitish)

        try:
            subprocess.run(cmd, cwd=str(self.repo_root), check=True, capture_output=True)
            return True
        except subprocess.CalledProcessError as e:
            logger.error("Failed to add worktree at %s: %s", path, e.stderr)
            return False

    def remove_worktree(self, path: Path, force: bool = False) -> bool:
        """Removes a worktree."""
        cmd = ["git", "worktree", "remove", str(path)]
        if force:
            cmd.append("--force")

        try:
            subprocess.run(cmd, cwd=str(self.repo_root), check=True, capture_output=True)
            return True
        except subprocess.CalledProcessError as e:
            logger.error("Failed to remove worktree at %s: %s", path, e.stderr)
            return False

    @staticmethod
    def find_repo_root(start_path: Path | str) -> Optional[Path]:
        """Finds the git repository root for a given path."""
        curr = Path(start_path).resolve()
        for candidate in (curr, *curr.parents):
            git_dir = candidate / ".git"
            if git_dir.exists():
                return candidate
        return None
