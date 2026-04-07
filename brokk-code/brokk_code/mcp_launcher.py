import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

from brokk_code.executor import BUNDLED_EXECUTOR_VERSION, ExecutorError, ensure_jbang_ready
from brokk_code.runtime_utils import find_dev_jar

_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_MCP_SERVER_MAIN_CLASS = "ai.brokk.mcpserver.BrokkExternalMcpServer"
_MCP_CORE_SERVER_MAIN_CLASS = "ai.brokk.mcpserver.BrokkCoreMcpServer"


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


def build_direct_mcp_command(jar_path: Path) -> list[str]:
    return [
        "java",
        "-Djava.awt.headless=true",
        "-Dapple.awt.UIElement=true",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        str(jar_path),
        _MCP_SERVER_MAIN_CLASS,
    ]


def build_jbang_mcp_command(*, jbang_binary: str, executor_version: str | None) -> list[str]:
    version = executor_version or BUNDLED_EXECUTOR_VERSION
    jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-{version}.jar"
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
        _MCP_SERVER_MAIN_CLASS,
        jar_url,
    ]


def resolve_mcp_command(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
) -> list[str]:
    if jar_path:
        return build_direct_mcp_command(jar_path)

    dev_jar = find_dev_jar(workspace_dir)
    if dev_jar:
        return build_direct_mcp_command(dev_jar)

    jbang_binary = ensure_jbang_ready()
    return build_jbang_mcp_command(jbang_binary=jbang_binary, executor_version=executor_version)


def run_mcp_server(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    resolved_workspace_dir = resolve_mcp_workspace_dir(workspace_dir)

    try:
        command = resolve_mcp_command(
            workspace_dir=resolved_workspace_dir,
            jar_path=jar_path,
            executor_version=executor_version,
        )
        if passthrough_args:
            # JBang requires '--' before arguments intended for the Java main class
            # to distinguish them from JBang's own options.
            # build_direct_mcp_command always starts with 'java'.
            if command[0] != "java":
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
            os.execvpe(command[0], command, os.environ.copy())
    except ExecutorError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print(
            f"Error: Unable to launch MCP runtime via '{command[0]}'. "
            "Ensure the required runtime is installed or pass --jar.",
            file=sys.stderr,
        )
        sys.exit(1)
    except OSError as exc:
        print(f"Error: Failed to launch MCP runtime: {exc}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# brokk-core MCP server
# ---------------------------------------------------------------------------


def find_dev_jar_core(workspace_dir: Path) -> Optional[Path]:
    """Search for a local brokk-core development JAR.

    Walks upward from *workspace_dir* until ``gradlew`` is found, then looks
    in ``<repo>/brokk-core/build/libs/brokk-core-*.jar``.
    """
    excluded_suffixes = ("-sources.jar", "-javadoc.jar", "-plain.jar")

    def _find_in_repo(base: Path) -> Optional[Path]:
        libs_dir = base / "brokk-core" / "build" / "libs"
        if not libs_dir.exists():
            return None
        candidates = [
            jar
            for jar in libs_dir.glob("brokk-core-*.jar")
            if not jar.name.endswith(excluded_suffixes)
        ]
        if not candidates:
            return None
        return max(candidates, key=lambda jar: jar.stat().st_mtime)

    curr = workspace_dir.resolve()
    while True:
        if (curr / "gradlew").exists() or (curr / "gradlew.bat").exists():
            jar = _find_in_repo(curr)
            if jar:
                return jar
        if curr == curr.parent:
            break
        curr = curr.parent
    return None


def build_direct_mcp_core_command(jar_path: Path) -> list[str]:
    return [
        "java",
        "-Djava.awt.headless=true",
        "-Dapple.awt.UIElement=true",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        str(jar_path),
        _MCP_CORE_SERVER_MAIN_CLASS,
    ]


def build_jbang_mcp_core_command(*, jbang_binary: str, executor_version: str | None) -> list[str]:
    version = executor_version or BUNDLED_EXECUTOR_VERSION
    jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-core-{version}.jar"
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
        _MCP_CORE_SERVER_MAIN_CLASS,
        jar_url,
    ]


def resolve_mcp_core_command(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
) -> list[str]:
    if jar_path:
        return build_direct_mcp_core_command(jar_path)

    dev_jar = find_dev_jar_core(workspace_dir)
    if dev_jar:
        return build_direct_mcp_core_command(dev_jar)

    jbang_binary = ensure_jbang_ready()
    return build_jbang_mcp_core_command(
        jbang_binary=jbang_binary, executor_version=executor_version
    )


def run_mcp_core_server(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    passthrough_args: list[str] | None = None,
) -> None:
    resolved_workspace_dir = resolve_mcp_workspace_dir(workspace_dir)

    try:
        command = resolve_mcp_core_command(
            workspace_dir=resolved_workspace_dir,
            jar_path=jar_path,
            executor_version=executor_version,
        )
        if passthrough_args:
            if command[0] != "java":
                command.append("--")
            command.extend(passthrough_args)

        os.chdir(resolved_workspace_dir)
        if sys.platform == "win32":
            result = subprocess.run(command, env=os.environ.copy())
            sys.exit(result.returncode)
        else:
            os.execvpe(command[0], command, os.environ.copy())
    except ExecutorError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print(
            f"Error: Unable to launch MCP core runtime via '{command[0]}'. "
            "Ensure the required runtime is installed or pass --jar.",
            file=sys.stderr,
        )
        sys.exit(1)
    except OSError as exc:
        print(f"Error: Failed to launch MCP core runtime: {exc}", file=sys.stderr)
        sys.exit(1)
