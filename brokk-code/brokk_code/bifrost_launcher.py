"""Launch the bifrost (Rust) MCP server as a subcommand of `brokk`.

bifrost is a native binary; this module mirrors the workspace-resolution
behavior of `mcp_launcher` and execs the binary in place so stdio passes
through cleanly to the MCP client.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from brokk_code.mcp_launcher import resolve_mcp_workspace_dir
from brokk_code.rust_acp_install import (
    BifrostInstallError,
    RustAcpInstallError,
    resolve_bifrost_binary,
)


def run_bifrost_server(
    *,
    workspace_dir: Path,
    binary_override: Path | None,
    passthrough_args: list[str] | None = None,
) -> None:
    """Resolve the bifrost binary and exec it as `bifrost --root <ws> --server searchtools`.

    Replaces the Python process via os.execvpe on Unix; on Windows runs as a
    child since execvpe loses stdout. Extra positional args from the CLI are
    forwarded after the fixed `--server searchtools` flag.
    """
    resolved_workspace_dir = resolve_mcp_workspace_dir(workspace_dir)

    try:
        bifrost_bin = resolve_bifrost_binary(override=binary_override)
    except (RustAcpInstallError, BifrostInstallError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)

    command = [
        str(bifrost_bin),
        "--root",
        str(resolved_workspace_dir),
        "--server",
        "searchtools",
    ]
    if passthrough_args:
        command.extend(passthrough_args)

    os.chdir(resolved_workspace_dir)
    try:
        if sys.platform == "win32":
            result = subprocess.run(command, env=os.environ.copy())
            sys.exit(result.returncode)
        else:
            os.execvpe(str(bifrost_bin), command, os.environ.copy())
    except FileNotFoundError:
        print(
            f"Error: bifrost binary not found at {bifrost_bin}.",
            file=sys.stderr,
        )
        sys.exit(1)
    except OSError as exc:
        print(f"Error: Failed to launch bifrost: {exc}", file=sys.stderr)
        sys.exit(1)
