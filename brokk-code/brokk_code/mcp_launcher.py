import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

from brokk_code.executor import BUNDLED_EXECUTOR_VERSION, ExecutorError, ensure_jbang_ready

_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_MCP_SERVER_MAIN_CLASS = "ai.brokk.mcpserver.BrokkExternalMcpServer"
_MCP_CORE_SERVER_MAIN_CLASS = "ai.brokk.mcpserver.BrokkCoreMcpServer"
_ACP_SERVER_MAIN_CLASS = "ai.brokk.acp.AcpServerMain"


def git_toplevel_for(path: Path) -> Optional[Path]:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            timeout=5.0,
            cwd=str(path),
        )
    except Exception:
        return None

    if result.returncode != 0:
        return None

    stdout = result.stdout.strip()
    if not stdout:
        return None
    return Path(stdout).resolve()


def resolve_mcp_workspace_dir(path: Path) -> Path:
    resolved = path.resolve()
    current = resolved if resolved.is_dir() else resolved.parent
    return git_toplevel_for(current) or current


# ---------------------------------------------------------------------------
# Shared parameterized helpers
# ---------------------------------------------------------------------------


def _build_direct_mcp_command(main_class: str, jar_path: Path) -> list[str]:
    return [
        "java",
        "-Djava.awt.headless=true",
        "-Dapple.awt.UIElement=true",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        str(jar_path),
        main_class,
    ]


def _build_jbang_mcp_command(
    main_class: str,
    jar_name_prefix: str,
    *,
    jbang_binary: str,
    executor_version: str | None,
) -> list[str]:
    version = executor_version or BUNDLED_EXECUTOR_VERSION
    jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/{jar_name_prefix}-{version}.jar"
    return [
        jbang_binary,
        "--java",
        "21",
        "-R",
        "-Djava.awt.headless=true",
        "-R",
        "-Dapple.awt.UIElement=true",
        "-R",
        "--enable-native-access=ALL-UNNAMED",
        "--main",
        main_class,
        jar_url,
    ]


def _resolve_mcp_command(
    main_class: str,
    jar_name_prefix: str,
    subproject: str,
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
) -> list[str]:
    if jar_path:
        return _build_direct_mcp_command(main_class, jar_path)

    jbang_binary = ensure_jbang_ready()
    return _build_jbang_mcp_command(
        main_class,
        jar_name_prefix,
        jbang_binary=jbang_binary,
        executor_version=executor_version,
    )


def _run_mcp(
    main_class: str,
    jar_name_prefix: str,
    subproject: str,
    label: str,
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    resolved_workspace_dir = resolve_mcp_workspace_dir(workspace_dir)
    launcher = "runtime"

    try:
        command = _resolve_mcp_command(
            main_class,
            jar_name_prefix,
            subproject,
            workspace_dir=resolved_workspace_dir,
            jar_path=jar_path,
            executor_version=executor_version,
        )
        launcher = command[0]
        if passthrough_args:
            # JBang requires '--' before arguments intended for the Java main class
            # to distinguish them from JBang's own options.
            # _build_direct_mcp_command always starts with 'java'.
            if launcher != "java":
                command.append("--")
            command.extend(passthrough_args)

        os.chdir(resolved_workspace_dir)
        if sys.platform == "win32":
            # os.execvpe on Windows doesn't truly replace the process and
            # stdout/stderr from the child may be silently lost.  Use
            # subprocess.run so output is properly inherited.
            result = subprocess.run(command, env=os.environ.copy())
            sys.exit(result.returncode)
        else:
            os.execvpe(launcher, command, os.environ.copy())
    except ExecutorError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        kind = f"{label} runtime" if label else "runtime"
        print(
            f"Error: Unable to launch MCP {kind} via '{launcher}'. "
            "Ensure the required runtime is installed or pass --jar.",
            file=sys.stderr,
        )
        sys.exit(1)
    except OSError as exc:
        kind = f"{label} runtime" if label else "runtime"
        print(f"Error: Failed to launch MCP {kind}: {exc}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# Public API — preserved signatures
# ---------------------------------------------------------------------------


def build_direct_mcp_command(jar_path: Path) -> list[str]:
    return _build_direct_mcp_command(_MCP_SERVER_MAIN_CLASS, jar_path)


def build_jbang_mcp_command(*, jbang_binary: str, executor_version: str | None) -> list[str]:
    return _build_jbang_mcp_command(
        _MCP_SERVER_MAIN_CLASS,
        "brokk",
        jbang_binary=jbang_binary,
        executor_version=executor_version,
    )


def resolve_mcp_command(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
) -> list[str]:
    return _resolve_mcp_command(
        _MCP_SERVER_MAIN_CLASS,
        "brokk",
        "app",
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
    )


def run_mcp_server(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    _run_mcp(
        _MCP_SERVER_MAIN_CLASS,
        "brokk",
        "app",
        "",
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        passthrough_args=passthrough_args,
    )


def run_acp_server(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    """Launch the native Java ACP server (stdio JSON-RPC).

    This replaces the Python ACP bridge with a direct Java process.
    stdin/stdout are passed through to the Java ACP server via os.execvpe.
    """
    _run_mcp(
        _ACP_SERVER_MAIN_CLASS,
        "brokk",
        "app",
        "ACP",
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        passthrough_args=passthrough_args,
    )


def run_mcp_core_server(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    _run_mcp(
        _MCP_CORE_SERVER_MAIN_CLASS,
        "brokk-core",
        "brokk-core",
        "core",
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        passthrough_args=passthrough_args,
    )
