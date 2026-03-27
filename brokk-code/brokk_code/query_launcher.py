"""Launcher for one-shot Brokk tool queries.

Runs a single Brokk SearchTools method and prints the result to stdout.
Designed to be called from hook scripts for real code intelligence.
"""

import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

from brokk_code.executor import BUNDLED_EXECUTOR_VERSION, ExecutorError, ensure_jbang_ready
from brokk_code.mcp_pure_launcher import git_toplevel_for, resolve_mcp_workspace_dir
from brokk_code.runtime_utils import find_dev_jar

_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_QUERY_CLI_MAIN_CLASS = "ai.brokk.mcpserver.BrokkQueryCli"


def build_direct_query_command(jar_path: Path, tool_name: str, json_args: str) -> list[str]:
    return [
        "java",
        "-Djava.awt.headless=true",
        "-Dapple.awt.UIElement=true",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        str(jar_path),
        _QUERY_CLI_MAIN_CLASS,
        tool_name,
        json_args,
    ]


def build_jbang_query_command(
    *, jbang_binary: str, executor_version: str | None, tool_name: str, json_args: str
) -> list[str]:
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
        _QUERY_CLI_MAIN_CLASS,
        jar_url,
        tool_name,
        json_args,
    ]


def run_query(
    *,
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: str | None,
    tool_name: str,
    json_args: str,
) -> None:
    """Run a single Brokk tool query and print the result."""
    resolved_workspace_dir = resolve_mcp_workspace_dir(workspace_dir)

    try:
        if jar_path:
            command = build_direct_query_command(jar_path, tool_name, json_args)
        else:
            dev_jar = find_dev_jar(resolved_workspace_dir)
            if dev_jar:
                command = build_direct_query_command(dev_jar, tool_name, json_args)
            else:
                jbang_binary = ensure_jbang_ready()
                command = build_jbang_query_command(
                    jbang_binary=jbang_binary,
                    executor_version=executor_version,
                    tool_name=tool_name,
                    json_args=json_args,
                )

        result = subprocess.run(
            command,
            cwd=str(resolved_workspace_dir),
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.stdout:
            print(result.stdout, end="")
        if result.returncode != 0 and result.stderr:
            print(result.stderr, end="", file=sys.stderr)
        sys.exit(result.returncode)
    except subprocess.TimeoutExpired:
        print("Error: Query timed out after 30 seconds", file=sys.stderr)
        sys.exit(1)
    except ExecutorError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print(
            "Error: Unable to launch query runtime. "
            "Ensure Java 21 is installed or pass --jar.",
            file=sys.stderr,
        )
        sys.exit(1)
