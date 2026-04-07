import re
import subprocess
from pathlib import Path
from typing import Optional, Tuple

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
