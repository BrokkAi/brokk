import argparse
import asyncio
import base64
import contextlib
import getpass
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Iterator

from rich.console import Console

from brokk_code.avante_config import configure_nvim_avante_acp_settings
from brokk_code.event_utils import is_failure_state, safe_data
from brokk_code.executor import (
    BUNDLED_EXECUTOR_VERSION,
    ExecutorError,
    ensure_jbang_ready,
    resolve_jbang_binary,
)
from brokk_code.git_utils import infer_github_repo_from_remote
from brokk_code.intellij_config import configure_intellij_acp_settings
from brokk_code.mcp_config import (
    configure_claude_code_mcp_settings,
    configure_codex_mcp_settings,
    install_codex_mcp_workspace_skill,
)
from brokk_code.mcp_launcher import run_mcp_server
from brokk_code.nvim_config import configure_nvim_codecompanion_acp_settings
from brokk_code.nvim_init_patch import wire_nvim_plugin_setup
from brokk_code.settings import (
    Settings,
    get_brokk_properties_path,
    read_brokk_properties,
    write_brokk_api_key,
    write_brokk_properties,
)
from brokk_code.uv_utils import UvSetupError, ensure_uv_ready
from brokk_code.workspace import resolve_workspace_dir
from brokk_code.zed_config import ExistingBrokkCodeEntryError, configure_zed_acp_settings

# Default model names used across CLI subcommands
DEFAULT_PLANNER_MODEL = "gpt-5.4"
DEFAULT_CODE_MODEL = "gemini-3-flash-preview"

REPO_COMPONENT_ALLOWLIST_REGEX = r"^[A-Za-z0-9_.-]+$"
_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_HEADLESS_EXECUTOR_MAIN_CLASS = "ai.brokk.executor.HeadlessExecutorMain"
_MCP_SERVER_MAIN_CLASS = "ai.brokk.mcpserver.BrokkExternalMcpServer"
_JBANG_PREFETCH_TIMEOUT_SECONDS = 120.0


def _resolve_neovim_plugin(*, plugin: str | None) -> str:
    if plugin:
        return plugin
    if not sys.stdin.isatty():
        return "codecompanion"

    console = Console()
    console.print("Choose a Neovim plugin integration:")
    console.print("1) CodeCompanion (ACP adapter)")
    console.print("2) Avante (ACP provider)")
    choice = console.input("Selection [1/2] (default: 1): ").strip().lower()
    if choice in {"", "1", "codecompanion"}:
        return "codecompanion"
    if choice in {"2", "avante"}:
        return "avante"
    raise ValueError(f"Invalid plugin selection: '{choice}'")


def _add_github_issue_args(
    parser: argparse.ArgumentParser,
    planner_model_default: str = DEFAULT_CODE_MODEL,
) -> None:
    """Add common GitHub issue arguments to a parser."""
    parser.add_argument(
        "--github-token",
        type=str,
        default=Settings().get_github_token(),
        help="GitHub API token (from brokk.properties, GITHUB_TOKEN env var, or --github-token)",
    )
    parser.add_argument(
        "--repo-owner",
        type=str,
        help="GitHub repository owner",
    )
    parser.add_argument(
        "--repo-name",
        type=str,
        help="GitHub repository name",
    )
    parser.add_argument(
        "--planner-model",
        type=str,
        default=planner_model_default,
        help=f"LLM model for planning/analysis (default: {planner_model_default})",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Show full headless executor output (events/tokens) for debugging",
    )


def _validate_github_params(
    github_token: str | None,
    repo_owner: str | None,
    repo_name: str | None,
    command_name: str,
) -> None:
    if not github_token:
        print(f"Error: --github-token is required for {command_name}", file=sys.stderr)
        sys.exit(1)
    if not repo_owner:
        print(f"Error: --repo-owner is required for {command_name}", file=sys.stderr)
        sys.exit(1)
    if not repo_name:
        print(f"Error: --repo-name is required for {command_name}", file=sys.stderr)
        sys.exit(1)

    if not re.match(REPO_COMPONENT_ALLOWLIST_REGEX, repo_owner):
        print(
            f"Error: Invalid --repo-owner '{repo_owner}'. "
            f"Repo owner must match {REPO_COMPONENT_ALLOWLIST_REGEX}",
            file=sys.stderr,
        )
        sys.exit(1)
    if not re.match(REPO_COMPONENT_ALLOWLIST_REGEX, repo_name):
        print(
            f"Error: Invalid --repo-name '{repo_name}'. "
            f"Repo name must match {REPO_COMPONENT_ALLOWLIST_REGEX}",
            file=sys.stderr,
        )
        sys.exit(1)


def _print_brokk_login_instructions() -> None:
    print("Get your Brokk API key:")
    print("1. Open https://brokk.ai/")
    print('2. Click "Try Brokk Now"')
    print("3. Log in with GitHub or Google")
    print("4. Copy your API key")
    print()
    print("Paste shortcuts:")
    print("  macOS Terminal/iTerm: Cmd+V")
    print("  Windows Terminal/PowerShell: Ctrl+V (or right-click)")
    print("  Linux terminals: Ctrl+Shift+V or Shift+Insert")
    print()


def _read_api_key_from_stdin() -> str:
    key = sys.stdin.read().strip()
    if not key:
        raise ValueError("No API key data was read from standard input.")
    return key


def _read_masked_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise ValueError("Interactive API key input requires a TTY terminal.")

    if sys.platform == "win32":
        import msvcrt

        chars: list[str] = []
        sys.stdout.write(prompt)
        sys.stdout.flush()
        while True:
            ch = msvcrt.getwch()
            if ch in {"\r", "\n"}:
                sys.stdout.write("\n")
                sys.stdout.flush()
                return "".join(chars)
            if ch == "\003":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise KeyboardInterrupt
            if ch in {"\b", "\x7f"}:
                if chars:
                    chars.pop()
                    sys.stdout.write("\b \b")
                    sys.stdout.flush()
                continue
            if ch in {"\x00", "\xe0"}:
                _ = msvcrt.getwch()
                continue
            chars.append(ch)
            sys.stdout.write("*")
            sys.stdout.flush()

    import termios
    import tty

    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    chars = []
    sys.stdout.write(prompt)
    sys.stdout.flush()
    try:
        tty.setraw(fd)
        while True:
            ch = sys.stdin.read(1)
            if ch in {"\r", "\n"}:
                sys.stdout.write("\n")
                sys.stdout.flush()
                return "".join(chars)
            if ch == "\x03":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise KeyboardInterrupt
            if ch == "\x04" and not chars:
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise EOFError("No API key entered.")
            if ch in {"\x7f", "\b"}:
                if chars:
                    chars.pop()
                    sys.stdout.write("\b \b")
                    sys.stdout.flush()
                continue
            chars.append(ch)
            sys.stdout.write("*")
            sys.stdout.flush()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)


def _read_api_key_interactive() -> str:
    _print_brokk_login_instructions()
    print("Paste your Brokk API key below, then press Enter.")
    print("Your input will be masked as '*' (one star per character).")
    return _read_masked_input("Brokk API key: ").strip()


def _ensure_install_api_key() -> None:
    key = (Settings().get_brokk_api_key() or "").strip()
    if key:
        return

    if sys.stdin.isatty():
        key = _read_api_key_interactive()
    else:
        key = _read_api_key_from_stdin()

    key = key.strip()
    if not key:
        raise ValueError("API key cannot be empty.")

    write_brokk_api_key(key)
    print(f"Saved Brokk API key to {get_brokk_properties_path()}")


def _ensure_install_github_token(
    workspace_dir: Path,
    jar_path: Path | None,
    executor_version: str | None,
    executor_snapshot: bool,
) -> None:
    if Settings().get_github_token():
        return

    if not sys.stdin.isatty():
        print(
            "Note: GitHub token is not configured. "
            "Run 'brokk github login' to enable GitHub features."
        )
        return

    print("\nGitHub integration is not configured.")
    print("This allows Brokk to create PRs and manage issues directly.")
    print("1) Login via web browser (Device Flow)")
    print("2) Enter Personal Access Token (PAT)")
    print("3) Skip for now")

    choice = input("Selection [1/2/3] (default: 1): ").strip()
    if choice in {"", "1"}:
        asyncio.run(
            run_github_login(
                workspace_dir=workspace_dir,
                jar_path=jar_path,
                executor_version=executor_version,
                executor_snapshot=executor_snapshot,
                method="device",
            )
        )
    elif choice == "2":
        asyncio.run(
            run_github_login(
                workspace_dir=workspace_dir,
                jar_path=jar_path,
                executor_version=executor_version,
                executor_snapshot=executor_snapshot,
                method="pat",
            )
        )
    else:
        print("Skipped GitHub setup.")


def _looks_like_auth_failure(message: str) -> bool:
    text = message.lower()
    return (
        "401" in text
        or "unauthorized" in text
        or "forbidden" in text
        or ("invalid" in text and "key" in text)
        or ("api key" in text and "failed" in text)
    )


def _validation_not_possible(message: str) -> bool:
    text = message.lower()
    return (
        "jbang executable not found" in text
        or "java executable not found" in text
        or "failed to extract port from executor output" in text
        or "executor failed to become live" in text
        or "executor failed to become ready" in text
    )


def _is_missing_validate_endpoint(message: str) -> bool:
    text = message.lower()
    return "/v1/auth/validate" in text and "not found (404)" in text


async def _validate_brokk_api_key(
    *,
    api_key: str,
    workspace_dir: Path,
    jar_path: Path | None,
    executor_version: str | None,
    executor_snapshot: bool,
    vendor: str | None,
) -> dict[str, Any]:
    from brokk_code.executor import ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
        brokk_api_key=api_key,
    )
    try:
        await manager.start()
        if not await manager.wait_live(timeout=20.0):
            raise ExecutorError("Executor failed to become live for API key validation.")
        try:
            return await manager.validate_brokk_auth()
        except ExecutorError as exc:
            if _is_missing_validate_endpoint(str(exc)):
                await manager.get_models()
                return {
                    "state": "LEGACY_VALID",
                    "valid": True,
                    "subscribed": False,
                    "hasBalance": False,
                    "message": "Validated with legacy model discovery path.",
                }
            raise
    finally:
        await manager.stop()


async def run_github_login(
    *,
    workspace_dir: Path,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    method: str = "device",
    read_from_stdin: bool = False,
    no_browser: bool = False,
) -> None:
    if method == "pat":
        try:
            if read_from_stdin or not sys.stdin.isatty():
                if read_from_stdin and sys.stdin.isatty():
                    print("Error: --stdin requires piped input on standard input.", file=sys.stderr)
                    sys.exit(1)
                token = sys.stdin.read().strip()
            else:
                token = getpass.getpass("GitHub Personal Access Token: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGitHub login cancelled.", file=sys.stderr)
            sys.exit(1)

        if not token:
            print("Error: GitHub token cannot be empty.", file=sys.stderr)
            sys.exit(1)

        write_brokk_properties({"githubToken": token})
        print(f"Saved GitHub token to {get_brokk_properties_path()}")
        return

    # Device flow
    if read_from_stdin:
        print("Error: --stdin is only supported with --method=pat", file=sys.stderr)
        sys.exit(1)

    from brokk_code.executor import ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        exit_on_stdin_eof=True,
    )

    try:
        await manager.start()
        if not await manager.wait_live():
            print("Error: executor failed to become live.", file=sys.stderr)
            sys.exit(1)

        resp = await manager.start_github_oauth()
        uri = resp.get("verificationUri")
        code = resp.get("userCode")
        interval = resp.get("interval", 5)
        expires_in = resp.get("expiresIn", 900)

        print(f"\nTo authorize Brokk with GitHub, open this URL in your browser:\n\n  {uri}\n")
        console = Console()
        console.print(f"Enter the code: [bold]{code}[/]\n")

        if not no_browser:
            import webbrowser

            webbrowser.open(uri)

        deadline = asyncio.get_event_loop().time() + expires_in
        while asyncio.get_event_loop().time() < deadline:
            await asyncio.sleep(interval)
            status = await manager.get_github_oauth_status()
            state = status.get("state", "IDLE")

            if state == "SUCCESS":
                user = status.get("username", "unknown user")
                print(f"Successfully connected to GitHub as {user}.")
                return
            if state in {"DENIED", "EXPIRED", "ERROR", "CANCELLED"}:
                msg = status.get("message", "Authentication failed")
                print(f"GitHub authentication failed: {msg}", file=sys.stderr)
                sys.exit(1)

        print("GitHub authentication timed out or expired before completion.", file=sys.stderr)
        sys.exit(1)
    finally:
        await manager.stop()


async def run_login(
    *,
    workspace_dir: Path,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    vendor: str | None = None,
    read_from_stdin: bool = False,
    skip_validate: bool = False,
) -> None:
    try:
        if read_from_stdin:
            if sys.stdin.isatty():
                raise ValueError("--stdin requires piped input on standard input.")
            key = _read_api_key_from_stdin()
        elif not sys.stdin.isatty():
            key = _read_api_key_from_stdin()
        else:
            key = _read_api_key_interactive()
    except (ValueError, EOFError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nLogin cancelled.", file=sys.stderr)
        sys.exit(1)

    if not key:
        print("Error: API key cannot be empty.", file=sys.stderr)
        sys.exit(1)

    await asyncio.to_thread(write_brokk_api_key, key)
    print(f"Saved Brokk API key to {get_brokk_properties_path()}")

    if skip_validate:
        print("Skipped validation (--skip-validate).")
        return

    print("Validating API key with the Brokk executor...")
    try:
        validation = await _validate_brokk_api_key(
            api_key=key,
            workspace_dir=workspace_dir,
            jar_path=jar_path,
            executor_version=executor_version,
            executor_snapshot=executor_snapshot,
            vendor=vendor,
        )
        state = str(validation.get("state", "")).strip().upper()
        message = str(validation.get("message", "")).strip()
        valid = bool(validation.get("valid"))
        subscribed = bool(validation.get("subscribed"))
        has_balance = bool(validation.get("hasBalance"))
        balance_raw = validation.get("balance")
        balance = balance_raw if isinstance(balance_raw, (int, float)) else None

        if valid:
            if state == "LEGACY_VALID":
                print("API key validation succeeded.")
                return

            tier = "paid" if subscribed else "free"
            if has_balance and balance is not None:
                print(f"API key validation succeeded: {tier} user, balance ${balance:.2f}.")
            else:
                print(f"API key validation succeeded: {tier} user.")
            return

        if state in {"INVALID_KEY", "INVALID_KEY_FORMAT", "UNKNOWN_USER", "MISSING_KEY"}:
            detail = message if message else state
            print(f"Error: API key validation failed [{state}]: {detail}", file=sys.stderr)
            print(
                "The key was saved, but it appears invalid. Run `brokk login` to update it.",
                file=sys.stderr,
            )
            sys.exit(1)

        if _validation_not_possible(message):
            print(f"Could not validate API key automatically: {message}")
            print("The key is saved and will be validated on next Brokk run.")
            return

        detail = message if message else state
        print(f"Warning: API key validation was inconclusive [{state}]: {detail}")
        print("The key is saved. If Brokk reports auth errors, run `brokk login` again.")
    except Exception as exc:
        message = str(exc)
        if _looks_like_auth_failure(message):
            print(f"Error: API key validation failed: {message}", file=sys.stderr)
            print(
                "The key was saved, but it appears invalid. Run `brokk login` to update it.",
                file=sys.stderr,
            )
            sys.exit(1)

        if _validation_not_possible(message):
            print(f"Could not validate API key automatically: {message}")
            print("The key is saved and will be validated on next Brokk run.")
            return

        print(f"Warning: API key validation was inconclusive: {message}")
        print("The key is saved. If Brokk reports auth errors, run `brokk login` again.")


def run_logout() -> None:
    props = read_brokk_properties()
    had_saved_key = bool(props.get("brokkApiKey", "").strip())
    write_brokk_properties({"brokkApiKey": None})
    props_path = get_brokk_properties_path()
    if had_saved_key:
        print(f"Removed saved Brokk API key from {props_path}")
    else:
        print(f"No saved Brokk API key found in {props_path}")
    if os.getenv("BROKK_API_KEY"):
        print("Note: BROKK_API_KEY is still set in your environment and can still be used.")


def _build_executor_prefetch_command(
    *, jbang_binary: str, executor_version: str | None
) -> list[str]:
    version = executor_version or BUNDLED_EXECUTOR_VERSION
    jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-{version}.jar"
    return [
        jbang_binary,
        "--java",
        "21",
        "-R",
        "-Djava.awt.headless=true "
        + "-Dapple.awt.UIElement=true "
        + "--enable-native-access=ALL-UNNAMED",
        "--main",
        _HEADLESS_EXECUTOR_MAIN_CLASS,
        jar_url,
        "--help",
    ]


def _build_mcp_prefetch_command(*, jbang_binary: str) -> list[str]:
    version = BUNDLED_EXECUTOR_VERSION
    jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-{version}.jar"
    return [
        jbang_binary,
        "--java",
        "21",
        "-R",
        "-Djava.awt.headless=true -Dapple.awt.UIElement=true",
        "-R",
        "--enable-native-access=ALL-UNNAMED",
        "--main",
        _MCP_SERVER_MAIN_CLASS,
        jar_url,
        "--help",
    ]


def _build_install_prefetch_commands(
    *, target: str, jbang_binary: str, executor_version: str | None
) -> list[tuple[str, list[str]]]:
    if target == "mcp":
        return [("MCP runtime", _build_mcp_prefetch_command(jbang_binary=jbang_binary))]
    return [
        (
            "Executor runtime",
            _build_executor_prefetch_command(
                jbang_binary=jbang_binary, executor_version=executor_version
            ),
        )
    ]


def _run_jbang_prefetch_command(label: str, command: list[str]) -> None:
    proc = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=_JBANG_PREFETCH_TIMEOUT_SECONDS,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        if detail:
            detail = f": {detail}"
        raise ExecutorError(f"{label} prefetch failed with code {proc.returncode}{detail}")


async def _spin_for_prefetch(commands: list[asyncio.Task[None]], *, label: str) -> None:
    if not commands or not sys.stdout.isatty():
        return

    spinner_frames = "|/-\\"
    frame_index = 0
    total = len(commands)
    while True:
        done = sum(1 for command in commands if command.done())
        if done >= total:
            break
        frame = spinner_frames[frame_index % len(spinner_frames)]
        frame_index += 1
        sys.stdout.write(f"\r{frame} {label} ({done}/{total})")
        sys.stdout.flush()
        await asyncio.sleep(0.12)

    clear_len = len(f"{label} ({total}/{total})") + 10
    sys.stdout.write(f"\r{' ' * clear_len}\r")
    print(f"{label} ({total}/{total})")


async def _run_install_prefetch_async(commands: list[tuple[str, list[str]]]) -> None:
    tasks: list[asyncio.Task[None]] = [
        asyncio.create_task(asyncio.to_thread(_run_jbang_prefetch_command, label, cmd))
        for label, cmd in commands
    ]
    spinner = asyncio.create_task(_spin_for_prefetch(tasks, label="Prefetching Brokk dependencies"))
    try:
        await asyncio.gather(*tasks)
    finally:
        await spinner


def _run_install_prefetch(commands: list[tuple[str, list[str]]]) -> None:
    if not commands:
        return
    asyncio.run(_run_install_prefetch_async(commands))


def _print_install_prefetch_commands(commands: list[tuple[str, list[str]]]) -> None:
    for _, command in commands:
        print(shlex.join(command))


def _add_common_runtime_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--workspace",
        type=str,
        default=".",
        help="Path to the workspace directory (default: current directory)",
    )
    parser.add_argument(
        "--vendor",
        type=str,
        choices=["Default", "Anthropic", "Gemini", "OpenAI", "OpenAI - Codex"],
        default=None,
        help=(
            "Set 'Other Models' vendor preference (affects internal roles like "
            "summarize/scan/commit). Use 'Default' to clear overrides."
        ),
    )
    parser.add_argument(
        "--jar",
        type=str,
        default=None,
        help="Path to brokk.jar (bypasses jbang; default: use jbang to launch)",
    )
    parser.add_argument(
        "--executor-version",
        type=str,
        default=None,
        help="Executor version to use (default: bundled version)",
    )
    parser.add_argument(
        "--executor-snapshot",
        action="store_true",
        default=True,
        help="[Ignored] Use jbang to manage versions",
    )
    parser.add_argument(
        "--executor-stable",
        action="store_false",
        dest="executor_snapshot",
        help="[Ignored] Use jbang to manage versions",
    )


def _run_issue_command(
    args: argparse.Namespace,
    jar_path: Path | None,
    command_name: str,
    mode: str,
    task_input: str,
    action_label: str,
    *,
    include_issue_number: bool = False,
    planner_reasoning_level: str = "disable",
    code_model: str | None = None,
    code_reasoning_level: str | None = None,
    skip_verification: bool | None = None,
    max_issue_fix_attempts: int | None = None,
    build_settings: str | None = None,
) -> None:
    """Run a GitHub issue command with shared validation, checkout, and job execution."""
    _validate_github_params(args.github_token, args.repo_owner, args.repo_name, command_name)
    tags: dict[str, str] = {
        "github_token": args.github_token,
        "repo_owner": args.repo_owner,
        "repo_name": args.repo_name,
    }
    if include_issue_number:
        tags["issue_number"] = str(args.issue_number)
    if build_settings:
        tags["build_settings"] = build_settings

    with _temporary_issue_repo_checkout(
        repo_owner=args.repo_owner,
        repo_name=args.repo_name,
        github_token=args.github_token,
        action_label=action_label,
    ) as issue_workspace_path:
        asyncio.run(
            run_headless_job(
                workspace_dir=issue_workspace_path,
                task_input=task_input,
                planner_model=args.planner_model,
                planner_reasoning_level=planner_reasoning_level,
                code_model=code_model,
                code_reasoning_level=code_reasoning_level,
                skip_verification=skip_verification,
                max_issue_fix_attempts=max_issue_fix_attempts,
                verbose=args.verbose,
                mode=mode,
                tags=tags,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
            )
        )


@contextlib.contextmanager
def _temporary_issue_repo_checkout(
    *,
    repo_owner: str,
    repo_name: str,
    github_token: str,
    action_label: str,
) -> Iterator[Path]:
    temp_parent = Path(tempfile.mkdtemp(prefix="brokk-issue-repo-"))
    temp_workspace_dir = temp_parent / repo_name
    clone_url = f"https://github.com/{repo_owner}/{repo_name}.git"
    basic_auth = base64.b64encode(f"x-access-token:{github_token}".encode("utf-8")).decode("ascii")
    try:
        print(
            f"{action_label}: shallow cloning {repo_owner}/{repo_name} into {temp_workspace_dir}",
            flush=True,
        )
        subprocess.run(
            [
                "git",
                "-c",
                "credential.helper=",
                "-c",
                "credential.interactive=never",
                "-c",
                "core.askPass=",
                "-c",
                f"http.extraHeader=Authorization: Basic {basic_auth}",
                "clone",
                "--depth",
                "1",
                "--single-branch",
                clone_url,
                str(temp_workspace_dir),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env={
                **os.environ,
                "GIT_TERMINAL_PROMPT": "0",
                "GIT_ASKPASS": "true",
                "SSH_ASKPASS": "true",
                "GCM_INTERACTIVE": "Never",
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
            },
        )
        print(f"{action_label}: cloned repository at {temp_workspace_dir}", flush=True)
        yield temp_workspace_dir
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or "").strip() or (exc.stdout or "").strip() or str(exc)
        print(f"Error: {action_label.lower()} clone failed: {detail}", file=sys.stderr)
        sys.exit(1)
    except OSError as exc:
        print(f"Error: {action_label.lower()} clone failed: {exc}", file=sys.stderr)
        sys.exit(1)
    finally:
        print(f"{action_label}: removing temporary checkout at {temp_workspace_dir}", flush=True)
        shutil.rmtree(temp_parent, ignore_errors=True)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Brokk Code - Interactive Terminal Interface")
    _add_common_runtime_args(parser)
    parser.add_argument(
        "--session",
        type=str,
        default=None,
        help="Attempt to resume a specific session by ID",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        dest="resume_session",
        default=False,
        help="Resume the last used session instead of creating a new one",
    )

    subparsers = parser.add_subparsers(dest="command")

    resume_parser = subparsers.add_parser("resume", help="Resume a specific session")
    _add_common_runtime_args(resume_parser)
    resume_parser.add_argument(
        "session_id",
        type=str,
        help="The ID of the session to resume",
    )

    sessions_parser = subparsers.add_parser("sessions", help="List and switch between sessions")
    _add_common_runtime_args(sessions_parser)

    acp_parser = subparsers.add_parser("acp", help="Run in ACP server mode")
    _add_common_runtime_args(acp_parser)
    acp_parser.add_argument(
        "--ide",
        choices=["intellij", "zed"],
        default=None,
        help=(
            "[Deprecated] Legacy IDE hint (no-op). "
            "ACP behavior is now derived from client capabilities and client_info."
        ),
    )

    mcp_parser = subparsers.add_parser("mcp", help="Run in MCP server mode", add_help=False)
    _add_common_runtime_args(mcp_parser)

    install_parser = subparsers.add_parser("install", help="Install integration settings")
    install_parser.add_argument(
        "target",
        choices=["zed", "intellij", "nvim", "neovim", "mcp"],
        help="Install target for integration settings",
    )
    install_parser.add_argument(
        "--plugin",
        choices=["codecompanion", "avante"],
        default=None,
        help=(
            "Neovim plugin integration to install (codecompanion or avante). "
            "Only used for install targets nvim/neovim; when omitted, an interactive "
            "selection menu is shown in TTY sessions."
        ),
    )
    install_parser.add_argument(
        "--force",
        action="store_true",
        default=False,
        help="Overwrite existing install configuration when supported",
    )
    install_parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Print the JBang prefetch command(s) instead of executing them",
    )

    commit_parser = subparsers.add_parser("commit", help="Commit current changes")
    _add_common_runtime_args(commit_parser)
    commit_parser.add_argument(
        "message",
        type=str,
        nargs="?",
        default=None,
        help="Commit message (optional; if omitted, a message will be generated)",
    )

    exec_parser = subparsers.add_parser(
        "exec", help="Run a prompt with LITE_AGENT (scan + architect, no build)"
    )
    _add_common_runtime_args(exec_parser)
    exec_parser.add_argument(
        "prompt",
        type=str,
        help="The task to execute",
    )
    exec_parser.add_argument(
        "--planner-model",
        type=str,
        default=DEFAULT_PLANNER_MODEL,
        help=f"LLM model for planning (default: {DEFAULT_PLANNER_MODEL})",
    )
    exec_parser.add_argument(
        "--code-model",
        type=str,
        default=DEFAULT_CODE_MODEL,
        help=f"LLM model for code generation (default: {DEFAULT_CODE_MODEL})",
    )
    exec_parser.add_argument(
        "--planner-reasoning-level",
        type=str,
        default="high",
        help="Reasoning level for planner model (default: high)",
    )
    exec_parser.add_argument(
        "--code-reasoning-level",
        type=str,
        default=None,
        help="Reasoning level for code model (default: none)",
    )
    exec_parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Show full output (events/tokens) for debugging",
    )

    issue_parser = subparsers.add_parser("issue", help="Manage GitHub issues")
    issue_subparsers = issue_parser.add_subparsers(dest="issue_command", required=True)

    # Note: 'issue create' maps to ISSUE_WRITER mode in the Java HeadlessExecCli.
    # It follows the same required parameters: repo owner/name, token, and prompt.
    issue_create_parser = issue_subparsers.add_parser("create", help="Create a new GitHub issue")
    _add_common_runtime_args(issue_create_parser)
    issue_create_parser.add_argument(
        "prompt",
        type=str,
        help="Description of the issue to create",
    )
    _add_github_issue_args(issue_create_parser)

    issue_diagnose_parser = issue_subparsers.add_parser(
        "diagnose", help="Analyze a GitHub issue and post a diagnosis comment"
    )
    _add_common_runtime_args(issue_diagnose_parser)
    issue_diagnose_parser.add_argument(
        "--issue-number",
        type=int,
        required=True,
        help="The GitHub issue number to diagnose",
    )
    _add_github_issue_args(issue_diagnose_parser)

    issue_solve_parser = issue_subparsers.add_parser("solve", help="Fix an existing GitHub issue")
    _add_common_runtime_args(issue_solve_parser)
    issue_solve_parser.add_argument(
        "--issue-number",
        type=int,
        required=True,
        help="The GitHub issue number to solve",
    )
    _add_github_issue_args(issue_solve_parser, planner_model_default=DEFAULT_PLANNER_MODEL)
    # Additional solve-specific arguments
    issue_solve_parser.add_argument(
        "--code-model",
        type=str,
        default=DEFAULT_CODE_MODEL,
        help=f"LLM model for code generation (default: {DEFAULT_CODE_MODEL})",
    )
    issue_solve_parser.add_argument(
        "--planner-reasoning-level",
        type=str,
        default="high",
        help="Reasoning level for planner model (default: high)",
    )
    issue_solve_parser.add_argument(
        "--code-reasoning-level",
        type=str,
        default="disable",
        help="Reasoning level for code model (default: disable)",
    )
    issue_solve_parser.add_argument(
        "--skip-verification",
        action="store_true",
        default=False,
        help="Skip per-task and final verification steps",
    )
    issue_solve_parser.add_argument(
        "--max-issue-fix-attempts",
        type=int,
        default=None,
        help="Maximum iterations for final verification fix loop",
    )
    issue_solve_parser.add_argument(
        "--build-settings",
        type=str,
        default=None,
        help="JSON string of build settings overrides (matching executor expectations)",
    )

    # PR commands
    pr_parser = subparsers.add_parser("pr", help="Manage pull requests")
    pr_subparsers = pr_parser.add_subparsers(dest="pr_command", required=True)

    pr_create_parser = pr_subparsers.add_parser("create", help="Create a pull request")
    _add_common_runtime_args(pr_create_parser)
    pr_create_parser.add_argument(
        "--title",
        type=str,
        default=None,
        help="PR title (if omitted, will be suggested by LLM)",
    )
    pr_create_parser.add_argument(
        "--body",
        type=str,
        default=None,
        help="PR body/description (if omitted, will be suggested by LLM)",
    )
    pr_create_parser.add_argument(
        "--base",
        type=str,
        default=None,
        help="Target/base branch (defaults to repository default branch)",
    )
    pr_create_parser.add_argument(
        "--head",
        type=str,
        default=None,
        help="Source/head branch (defaults to current branch)",
    )
    pr_create_parser.add_argument(
        "--github-token",
        type=str,
        default=Settings().get_github_token(),
        help="GitHub API token (from brokk.properties, GITHUB_TOKEN env var, or --github-token)",
    )

    pr_review_parser = pr_subparsers.add_parser("review", help="Review a pull request")
    _add_common_runtime_args(pr_review_parser)
    pr_review_parser.add_argument(
        "--pr-number",
        type=int,
        required=True,
        help="The pull request number to review",
    )
    pr_review_parser.add_argument(
        "--github-token",
        type=str,
        default=Settings().get_github_token(),
        help="GitHub API token (from brokk.properties, GITHUB_TOKEN env var, or --github-token)",
    )
    pr_review_parser.add_argument(
        "--repo-owner",
        type=str,
        default=None,
        help="GitHub repository owner (inferred from git remote if omitted)",
    )
    pr_review_parser.add_argument(
        "--repo-name",
        type=str,
        default=None,
        help="GitHub repository name (inferred from git remote if omitted)",
    )
    pr_review_parser.add_argument(
        "--planner-model",
        type=str,
        default=DEFAULT_PLANNER_MODEL,
        help=f"LLM model for the review (default: {DEFAULT_PLANNER_MODEL})",
    )
    pr_review_parser.add_argument(
        "--severity",
        type=str,
        default=None,
        choices=["CRITICAL", "HIGH", "MEDIUM", "LOW"],
        help="Minimum severity threshold for inline comments (default: HIGH)",
    )
    pr_review_parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Show full headless executor output (events/tokens) for debugging",
    )

    login_parser = subparsers.add_parser("login", help="Save your Brokk API key")
    _add_common_runtime_args(login_parser)
    login_parser.add_argument(
        "--stdin",
        action="store_true",
        default=False,
        help="Read the Brokk API key from stdin",
    )
    login_parser.add_argument(
        "--skip-validate",
        action="store_true",
        default=False,
        help="Save the key without immediate executor validation",
    )

    logout_parser = subparsers.add_parser("logout", help="Remove your saved Brokk API key")
    _add_common_runtime_args(logout_parser)

    github_parser = subparsers.add_parser("github", help="Manage GitHub authentication")
    github_subparsers = github_parser.add_subparsers(dest="github_command", required=True)

    gh_login_parser = github_subparsers.add_parser("login", help="Log in to GitHub")
    _add_common_runtime_args(gh_login_parser)
    gh_login_parser.add_argument(
        "--method",
        choices=["pat", "device"],
        default="device",
        help="Authentication method: pat (Personal Access Token) or device (OAuth Flow)",
    )
    gh_login_parser.add_argument(
        "--stdin",
        action="store_true",
        help="Read PAT from stdin (only for --method pat)",
    )
    gh_login_parser.add_argument(
        "--no-browser",
        action="store_true",
        help="Do not attempt to open the browser (only for --method device)",
    )

    gh_status_parser = github_subparsers.add_parser("status", help="Show GitHub login status")
    _add_common_runtime_args(gh_status_parser)

    gh_logout_parser = github_subparsers.add_parser("logout", help="Log out of GitHub")
    _add_common_runtime_args(gh_logout_parser)

    version_parser = subparsers.add_parser("version", help="Print version information")
    _add_common_runtime_args(version_parser)

    return parser


async def run_commit(
    workspace_dir: Path,
    message: str | None = None,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    vendor: str | None = None,
) -> None:
    """Commits current changes via ExecutorManager."""
    from brokk_code.executor import ExecutorError, ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
    )

    try:
        await manager.start()
        if not await manager.wait_live():
            print("Error: executor failed to become live.", file=sys.stderr)
            sys.exit(1)

        result = await manager.commit_context(message)

        if result.get("status") == "no_changes":
            print("No uncommitted changes.")
        else:
            commit_id = result.get("commitId", "")
            first_line = result.get("firstLine", "")
            short_id = commit_id[:7] if commit_id else ""
            print(f"Committed {short_id}: {first_line}")

    except ExecutorError as e:
        print(f"Executor error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        await manager.stop()


async def run_pr_create(
    workspace_dir: Path,
    title: str | None = None,
    body: str | None = None,
    base_branch: str | None = None,
    head_branch: str | None = None,
    github_token: str | None = None,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    vendor: str | None = None,
) -> None:
    """Creates a pull request via ExecutorManager.

    If title or body is not provided, the executor will suggest them via LLM.
    """
    from brokk_code.executor import ExecutorError, ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
    )

    try:
        await manager.start()
        if not await manager.wait_live():
            print("Error: executor failed to become live.", file=sys.stderr)
            sys.exit(1)

        # If title or body is missing, suggest them first
        effective_title = title
        effective_body = body
        if not effective_title or not effective_body:
            print("Suggesting PR title and description...", flush=True)
            suggestion = await manager.pr_suggest(
                source_branch=head_branch,
                target_branch=base_branch,
                github_token=github_token,
            )
            if not effective_title:
                effective_title = suggestion.get("title", "")
            if not effective_body:
                effective_body = suggestion.get("description", "")

            if not effective_title:
                print("Error: could not determine PR title.", file=sys.stderr)
                sys.exit(1)
            if not effective_body:
                effective_body = ""

        # Create the PR
        result = await manager.pr_create(
            title=effective_title,
            body=effective_body,
            source_branch=head_branch,
            target_branch=base_branch,
            github_token=github_token,
        )

        pr_url = result.get("url", "")
        if pr_url:
            print(f"Pull request created: {pr_url}")
        else:
            print("Pull request created.")

    except ExecutorError as e:
        print(f"Executor error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        await manager.stop()


async def run_pr_review_job(
    workspace_dir: Path,
    pr_number: int,
    github_token: str,
    repo_owner: str,
    repo_name: str,
    planner_model: str,
    severity_threshold: str | None = None,
    verbose: bool = False,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    vendor: str | None = None,
) -> None:
    """Runs a PR review job via ExecutorManager and streams events to stdout."""
    from brokk_code.executor import ExecutorError, ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
    )

    stage = "initializing"
    job_id: str | None = None
    last_state: str | None = None
    error_messages: list[str] = []
    spinner_index = 0
    spinner_active = False
    spinner_label = f"Reviewing PR #{pr_number}"
    spinner_frames = "|/-\\"
    spinner_enabled = sys.stdout.isatty() and not verbose

    def _extract_message(event: dict[str, Any]) -> str:
        raw = event.get("data")
        if isinstance(raw, str):
            return raw.strip()
        data = safe_data(event)
        for key in ("message", "text", "detail", "error"):
            value = data.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        for key in ("message", "text", "detail", "error"):
            value = event.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return ""

    def _render_spinner() -> None:
        nonlocal spinner_index, spinner_active
        if not spinner_enabled:
            return
        frame = spinner_frames[spinner_index % len(spinner_frames)]
        spinner_index += 1
        sys.stdout.write(f"\r{spinner_label}... {frame}")
        sys.stdout.flush()
        spinner_active = True

    def _clear_spinner() -> None:
        nonlocal spinner_active
        if not spinner_enabled or not spinner_active:
            return
        width = len(spinner_label) + 20
        sys.stdout.write("\r" + (" " * width) + "\r")
        sys.stdout.flush()
        spinner_active = False

    def _update_shutdown_context() -> None:
        context_parts = ["mode=REVIEW", f"stage={stage}"]
        if job_id:
            context_parts.append(f"job_id={job_id}")
        if last_state:
            context_parts.append(f"last_state={last_state}")
        if error_messages:
            context_parts.append(f"last_error={error_messages[-1]}")
        manager.shutdown_context = ", ".join(context_parts)

    try:
        stage = "starting executor"
        _update_shutdown_context()
        await manager.start()

        stage = "waiting for executor liveness"
        _update_shutdown_context()
        if not await manager.wait_live():
            print(
                f"Error during PR review job ({stage}): executor failed to become live.",
                file=sys.stderr,
            )
            sys.exit(1)

        stage = "submitting job"
        _update_shutdown_context()
        _render_spinner()
        job_id = await manager.submit_pr_review_job(
            planner_model=planner_model,
            github_token=github_token,
            owner=repo_owner,
            repo=repo_name,
            pr_number=pr_number,
            severity_threshold=severity_threshold,
        )
        _update_shutdown_context()

        stage = "streaming job events"
        _update_shutdown_context()
        async for event in manager.stream_events(job_id):
            _render_spinner()
            event_type = event.get("type")
            data = safe_data(event)
            if event_type == "NOTIFICATION":
                message = _extract_message(event)
                if not message:
                    continue
                level = str(data.get("level", event.get("level", "INFO"))).strip().upper()
                if not verbose and level not in {"WARN", "WARNING", "ERROR"}:
                    continue
                _clear_spinner()
                print(f"[{level}] {message}")
            elif event_type == "STATE_CHANGE":
                last_state = str(data.get("state", event.get("state", "UNKNOWN")))
                _update_shutdown_context()
                if verbose:
                    _clear_spinner()
                    print(f"Job state: {last_state}")
            elif event_type in {"TOKEN", "LLM_TOKEN"}:
                text = str(data.get("token", event.get("text", "")))
                if verbose and text:
                    sys.stdout.write(text)
                    sys.stdout.flush()
                continue
            elif event_type == "ERROR":
                message = _extract_message(event) or "Unknown error event"
                error_messages.append(message)
                _update_shutdown_context()
                _clear_spinner()
                print(f"\nError event: {message}", file=sys.stderr)
            elif event_type == "COMMAND_RESULT":
                if verbose:
                    _clear_spinner()
                    print(f"[COMMAND_RESULT] {data}")
            elif event_type == "TOOL_OUTPUT":
                if verbose:
                    _clear_spinner()
                    print(f"[TOOL_OUTPUT] {data}")

        if is_failure_state(last_state or ""):
            _clear_spinner()
            detail = f" Last error: {error_messages[-1]}" if error_messages else ""
            print(
                f"\nPR review job ended with state {last_state}.{detail}",
                file=sys.stderr,
            )
            sys.exit(1)

        if error_messages and last_state != "COMPLETED":
            _clear_spinner()
            detail = f" Last error: {error_messages[-1]}"
            observed_state = last_state or "UNKNOWN"
            msg = f"\nPR review job ended with errors (last observed state: {observed_state})."
            print(f"{msg}{detail}", file=sys.stderr)
            sys.exit(1)

        _clear_spinner()
        print(f"PR #{pr_number} review complete.")

    except ExecutorError as e:
        _clear_spinner()
        _update_shutdown_context()
        print(f"Executor error during PR review job ({stage}): {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        _clear_spinner()
        _update_shutdown_context()
        print(f"Unexpected error during PR review job ({stage}): {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        _clear_spinner()
        _update_shutdown_context()
        await manager.stop()


async def run_github_status(
    workspace_dir: Path,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
) -> None:
    from brokk_code.executor import ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
    )
    try:
        await manager.start()
        if not await manager.wait_live():
            print("Error: Executor failed to start.")
            return

        status = await manager.get_github_oauth_status()
        if status.get("connected"):
            print(f"Connected to GitHub as: {status.get('username')}")
        else:
            print("Not connected to GitHub.")
    finally:
        await manager.stop()


async def run_github_logout(
    workspace_dir: Path,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
) -> None:
    # Remove from properties
    write_brokk_properties({"githubToken": None})

    # Also notify executor to clear its instance/BackgroundAuth
    from brokk_code.executor import ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
    )
    try:
        await manager.start()
        if await manager.wait_live(timeout=5.0):
            await manager.disconnect_github_oauth()
    except Exception:
        pass
    finally:
        await manager.stop()

    print("Logged out of GitHub.")


async def run_headless_job(
    workspace_dir: Path,
    task_input: str,
    planner_model: str,
    mode: str,
    tags: dict[str, str],
    planner_reasoning_level: str | None = None,
    code_reasoning_level: str | None = None,
    code_model: str | None = None,
    skip_verification: bool | None = None,
    max_issue_fix_attempts: int | None = None,
    verbose: bool = False,
    jar_path: Path | None = None,
    executor_version: str | None = None,
    executor_snapshot: bool = True,
    vendor: str | None = None,
) -> None:
    """Runs a non-interactive job via ExecutorManager and streams events to stdout."""
    from brokk_code.executor import ExecutorError, ExecutorManager

    manager = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
    )

    stage = "initializing"
    job_id: str | None = None
    last_state: str | None = None
    error_messages: list[str] = []
    created_issue_url: str | None = None
    token_url_scan_buffer = ""
    spinner_index = 0
    spinner_active = False
    spinner_label = "Creating issue"
    spinner_frames = "|/-\\"
    spinner_enabled = sys.stdout.isatty() and not verbose

    def _extract_message(event: dict[str, Any]) -> str:
        raw = event.get("data")
        if isinstance(raw, str):
            return raw.strip()
        data = safe_data(event)
        for key in ("message", "text", "detail", "error"):
            value = data.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        for key in ("message", "text", "detail", "error"):
            value = event.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return ""

    def _record_issue_url(text: str) -> None:
        nonlocal created_issue_url, token_url_scan_buffer
        if created_issue_url or not text:
            return
        token_url_scan_buffer = (token_url_scan_buffer + text)[-8192:]
        match = re.search(r"https://github\.com/[^\s)\]>\"]+/issues/\d+", token_url_scan_buffer)
        if match:
            created_issue_url = match.group(0)

    def _record_issue_url_from_issue_writer_notification(message: str) -> None:
        # Java executor success path emits:
        # "ISSUE_WRITER: issue created <id> <htmlUrl>"
        if created_issue_url or not message:
            return
        normalized = message.strip()
        if not normalized.startswith("ISSUE_WRITER: issue created"):
            return
        _record_issue_url(normalized)

    def _record_issue_url_from_structured_issue_created(event: dict[str, Any]) -> None:
        if created_issue_url:
            return
        data = safe_data(event)
        issue_url = data.get("issueUrl")
        if isinstance(issue_url, str) and issue_url.strip():
            _record_issue_url(issue_url.strip())

    def _render_spinner() -> None:
        nonlocal spinner_index, spinner_active
        if mode not in {"ISSUE_WRITER", "ISSUE"} or not spinner_enabled:
            return
        frame = spinner_frames[spinner_index % len(spinner_frames)]
        spinner_index += 1
        label = "Solving issue" if mode == "ISSUE" else spinner_label
        sys.stdout.write(f"\r{label}... {frame}")
        sys.stdout.flush()
        spinner_active = True

    def _clear_spinner() -> None:
        nonlocal spinner_active
        if not spinner_enabled or not spinner_active:
            return
        # Calculate max possible width to clear (Issue # + long labels)
        width = len(spinner_label) + 20
        sys.stdout.write("\r" + (" " * width) + "\r")
        sys.stdout.flush()
        spinner_active = False

    def _update_shutdown_context() -> None:
        context_parts = [f"mode={mode}", f"stage={stage}"]
        if job_id:
            context_parts.append(f"job_id={job_id}")
        if last_state:
            context_parts.append(f"last_state={last_state}")
        if error_messages:
            context_parts.append(f"last_error={error_messages[-1]}")
        manager.shutdown_context = ", ".join(context_parts)

    try:
        stage = "starting executor"
        _update_shutdown_context()
        await manager.start()

        stage = "waiting for executor liveness"
        _update_shutdown_context()
        if not await manager.wait_live():
            print(
                f"Error during {mode} job ({stage}): executor failed to become live.",
                file=sys.stderr,
            )
            sys.exit(1)

        stage = "submitting job"
        _update_shutdown_context()
        _render_spinner()
        job_id = await manager.submit_job(
            task_input=task_input,
            planner_model=planner_model,
            code_model=code_model,
            reasoning_level=planner_reasoning_level,
            reasoning_level_code=code_reasoning_level,
            mode=mode,
            tags=tags,
            skip_verification=skip_verification,
            max_issue_fix_attempts=max_issue_fix_attempts,
        )
        _update_shutdown_context()

        stage = "streaming job events"
        _update_shutdown_context()
        async for event in manager.stream_events(job_id):
            _render_spinner()
            event_type = event.get("type")
            data = safe_data(event)
            if event_type == "NOTIFICATION":
                message = _extract_message(event)
                if not message:
                    continue
                _record_issue_url_from_issue_writer_notification(message)
                _record_issue_url(message)
                level = str(data.get("level", event.get("level", "INFO"))).strip().upper()
                # LITE_AGENT always shows notifications; others only show WARN/ERROR.
                if (
                    not verbose
                    and mode not in {"LITE_AGENT", "LITE_PLAN"}
                    and level not in {"WARN", "WARNING", "ERROR"}
                ):
                    continue
                _clear_spinner()
                print(f"[{level}] {message}")
            elif event_type == "STATE_CHANGE":
                last_state = str(data.get("state", event.get("state", "UNKNOWN")))
                _update_shutdown_context()
                if verbose:
                    _clear_spinner()
                    print(f"Job state: {last_state}")
            elif event_type in {"TOKEN", "LLM_TOKEN"}:
                text = str(data.get("token", event.get("text", "")))
                _record_issue_url(text)
                # LITE_AGENT always streams tokens; other modes only when verbose.
                if (verbose or mode in {"LITE_AGENT", "LITE_PLAN"}) and text:
                    sys.stdout.write(text)
                    sys.stdout.flush()
                continue
            elif event_type == "ERROR":
                message = _extract_message(event) or "Unknown error event"
                _record_issue_url(message)
                error_messages.append(message)
                _update_shutdown_context()
                _clear_spinner()
                print(f"\nError event: {message}", file=sys.stderr)
            elif event_type == "ISSUE_CREATED":
                _record_issue_url_from_structured_issue_created(event)
                if verbose:
                    _clear_spinner()
                    print(f"[ISSUE_CREATED] {data}")
            elif event_type == "COMMAND_RESULT":
                if verbose:
                    _clear_spinner()
                    print(f"[COMMAND_RESULT] {data}")
                _record_issue_url(str(data.get("output", "")))
                _record_issue_url(str(data.get("resultText", "")))
                _record_issue_url(str(data.get("command", "")))
                _record_issue_url(str(data.get("exception", "")))
            elif event_type == "TOOL_OUTPUT":
                if verbose:
                    _clear_spinner()
                    print(f"[TOOL_OUTPUT] {data}")
                _record_issue_url(str(data.get("output", "")))
                _record_issue_url(str(data.get("text", "")))
                _record_issue_url(str(data.get("resultText", "")))

        if is_failure_state(last_state or ""):
            _clear_spinner()
            detail = f" Last error: {error_messages[-1]}" if error_messages else ""
            print(
                f"\n{mode} job ended with state {last_state}.{detail}",
                file=sys.stderr,
            )
            sys.exit(1)

        if error_messages and last_state != "COMPLETED":
            _clear_spinner()
            detail = f" Last error: {error_messages[-1]}"
            observed_state = last_state or "UNKNOWN"
            print(
                f"\n{mode} job ended with errors (last observed state: {observed_state}).{detail}",
                file=sys.stderr,
            )
            sys.exit(1)

        _clear_spinner()
        if mode == "ISSUE_WRITER":
            if created_issue_url:
                print(f"Issue created: {created_issue_url}")
            else:
                print("Issue created.")
        else:
            print("Job finished.")

    except ExecutorError as e:
        _clear_spinner()
        _update_shutdown_context()
        print(f"Executor error during {mode} job ({stage}): {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        _clear_spinner()
        _update_shutdown_context()
        print(f"Unexpected error during {mode} job ({stage}): {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        _clear_spinner()
        _update_shutdown_context()
        await manager.stop()


def main():
    parser = _build_parser()
    args, unknown = parser.parse_known_args()

    if unknown and args.command != "mcp":
        parser.error(f"unrecognized arguments: {' '.join(unknown)}")

    # Resolve paths early so they are available to all commands
    workspace_path = Path(args.workspace).resolve()
    jar_path = Path(args.jar).resolve() if args.jar else None

    if args.command == "install":
        # Fast-fail validation before prompting for API keys
        if args.plugin and args.target not in {"nvim", "neovim"}:
            print("Error: --plugin is only valid for install targets nvim/neovim", file=sys.stderr)
            sys.exit(1)

        messages: list[str] = []
        prefetch_commands: list[tuple[str, list[str]]] = []
        try:
            uv_binary = ensure_uv_ready()
            uvx_command = str(Path(uv_binary).parent / "uvx")
            jbang_binary = resolve_jbang_binary() if args.verbose else ensure_jbang_ready()
            if args.verbose and not jbang_binary:
                jbang_binary = "jbang"
            if args.target == "zed":
                _ensure_install_api_key()
                _ensure_install_github_token(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
                settings_path = configure_zed_acp_settings(
                    force=args.force, uvx_command=uvx_command
                )
                prefetch_commands = _build_install_prefetch_commands(
                    target=args.target,
                    jbang_binary=jbang_binary,
                    executor_version=args.executor_version,
                )
                messages = [f"Configured Zed ACP integration in {settings_path}"]
            elif args.target == "intellij":
                _ensure_install_api_key()
                _ensure_install_github_token(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
                settings_path = configure_intellij_acp_settings(
                    force=args.force, uvx_command=uvx_command
                )
                prefetch_commands = _build_install_prefetch_commands(
                    target=args.target,
                    jbang_binary=jbang_binary,
                    executor_version=args.executor_version,
                )
                messages = [f"Configured IntelliJ ACP integration in {settings_path}"]
            elif args.target in {"nvim", "neovim"}:
                selected_plugin = _resolve_neovim_plugin(plugin=args.plugin)
                _ensure_install_api_key()
                _ensure_install_github_token(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
                if selected_plugin == "codecompanion":
                    settings_path = configure_nvim_codecompanion_acp_settings(force=args.force)
                    patch_result = wire_nvim_plugin_setup(
                        plugin_repo="olimorris/codecompanion.nvim",
                        module_name="brokk.brokk_codecompanion",
                    )
                    messages = [
                        f"Configured Neovim CodeCompanion ACP adapter in {settings_path}",
                        "",
                        "What this is:",
                        "- CodeCompanion is a Neovim AI/chat plugin.",
                        "- Brokk runs as an ACP agent server (`brokk acp`).",
                        "- The generated file wires CodeCompanion -> Brokk over ACP.",
                        "",
                        "Next steps:",
                        "1. Install codecompanion.nvim in Neovim (plugin manager):",
                        "   https://github.com/olimorris/codecompanion.nvim",
                        "2. Read setup docs if needed:",
                        "   https://codecompanion.olimorris.dev/",
                    ]
                    if patch_result.status == "patched":
                        messages.extend(
                            [
                                f"3. Updated {patch_result.path} to load Brokk automatically.",
                            ]
                        )
                    elif patch_result.status == "already_configured":
                        messages.extend(
                            [
                                f"3. {patch_result.path} already loads Brokk.",
                            ]
                        )
                    else:
                        messages.extend(
                            [
                                "3. Auto-wiring skipped to avoid risky edits.",
                                "   Add this in your lazy.nvim spec for codecompanion.nvim:",
                                "   opts = function()",
                                "     return require('brokk.brokk_codecompanion')",
                                "   end",
                            ]
                        )
                    messages.extend(
                        [
                            "4. Use the Brokk adapter in CodeCompanion chat:",
                            "   :CodeCompanionChat adapter=brokk",
                            "   (the generated module sets brokk as the default chat adapter)",
                            "",
                            "Troubleshooting:",
                            "- If you see 'Copilot Adapter: No token found', ",
                            "CodeCompanion is still",
                            "  using its default adapter and your Brokk module is not loaded",
                            "  in setup yet.",
                            "",
                            "Note: this command writes adapter config and may patch init.lua only",
                            "when it",
                            "can do a conservative, safe edit. It does not install Neovim plugins.",
                        ]
                    )
                else:
                    settings_path = configure_nvim_avante_acp_settings(force=args.force)
                    patch_result = wire_nvim_plugin_setup(
                        plugin_repo="yetone/avante.nvim",
                        module_name="brokk.brokk_avante",
                    )
                    messages = [
                        f"Configured Neovim Avante ACP provider in {settings_path}",
                        "",
                        "What this is:",
                        "- Avante is a Neovim AI coding assistant plugin.",
                        "- Brokk runs as an ACP agent server (`brokk acp`).",
                        "- The generated file wires Avante -> Brokk over ACP.",
                        "",
                        "Next steps:",
                        "1. Install avante.nvim in Neovim (plugin manager):",
                        "   https://github.com/yetone/avante.nvim",
                    ]
                    if patch_result.status == "patched":
                        messages.extend(
                            [
                                f"2. Updated {patch_result.path} to load Brokk automatically.",
                            ]
                        )
                    elif patch_result.status == "already_configured":
                        messages.extend(
                            [
                                f"2. {patch_result.path} already loads Brokk.",
                            ]
                        )
                    else:
                        messages.extend(
                            [
                                "2. Auto-wiring skipped to avoid risky edits.",
                                "   Load the generated Brokk provider in your config:",
                                "   local brokk = require('brokk.brokk_avante')",
                                "   require('avante').setup(",
                                "       vim.tbl_deep_extend('force', brokk, {}))",
                            ]
                        )
                    messages.extend(
                        [
                            "3. Use Brokk by setting provider='brokk' in Avante setup",
                            "   (the generated module already does this)",
                            "",
                            "Note: this command writes provider config and may patch init.lua only",
                            "when it",
                            "can do a conservative, safe edit. It does not install Neovim plugins.",
                        ]
                    )
                prefetch_commands = _build_install_prefetch_commands(
                    target=args.target,
                    jbang_binary=jbang_binary,
                    executor_version=args.executor_version,
                )
            elif args.target == "mcp":
                _ensure_install_api_key()
                _ensure_install_github_token(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
                claude_settings_path = configure_claude_code_mcp_settings(
                    force=args.force, uvx_command=uvx_command
                )
                codex_settings_path = configure_codex_mcp_settings(
                    force=args.force, uvx_command=uvx_command
                )
                codex_skill_path = install_codex_mcp_workspace_skill()
                prefetch_commands = _build_install_prefetch_commands(
                    target=args.target,
                    jbang_binary=jbang_binary,
                    executor_version=args.executor_version,
                )
                messages = [
                    f"Configured Claude Code MCP integration in {claude_settings_path}",
                    f"Configured Codex MCP integration in {codex_settings_path}",
                    f"Installed Codex MCP workspace skill in {codex_skill_path}",
                ]
            else:
                # Should not happen due to argparse choices
                raise ValueError(f"Unknown target: {args.target}")
            for message in messages:
                print(message)

            if args.verbose:
                _print_install_prefetch_commands(prefetch_commands)
                return

            _run_install_prefetch(prefetch_commands)
        except (ExistingBrokkCodeEntryError, ValueError) as exc:
            print(f"Error: {exc}", file=sys.stderr)
            sys.exit(1)
        except (ExecutorError, UvSetupError) as exc:
            print(f"Error: {exc}", file=sys.stderr)
            sys.exit(1)

        return

    if args.command == "version":
        from brokk_code import __version__

        print(f"brokk {__version__}")
        return

    if args.command == "logout":
        run_logout()
        return

    if args.command == "github":
        if args.github_command == "login":
            if not sys.stdin.isatty() and args.method == "device":
                print("Error: Device flow requires an interactive terminal.", file=sys.stderr)
                sys.exit(1)

            asyncio.run(
                run_github_login(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                    method=args.method,
                    read_from_stdin=args.stdin,
                    no_browser=args.no_browser,
                )
            )
        elif args.github_command == "status":
            asyncio.run(
                run_github_status(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
            )
        elif args.github_command == "logout":
            asyncio.run(
                run_github_logout(
                    workspace_dir=workspace_path,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                )
            )
        return

    if args.command == "login":
        validation_workspace = workspace_path
        if not validation_workspace.exists():
            validation_workspace = Path.cwd().resolve()
            print(
                f"Warning: Workspace path does not exist: {workspace_path}. "
                f"Using {validation_workspace} for validation."
            )
        asyncio.run(
            run_login(
                workspace_dir=validation_workspace,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
                read_from_stdin=args.stdin,
                skip_validate=args.skip_validate,
            )
        )
        return

    if args.command == "acp":
        try:
            from brokk_code.acp_server import run_acp_server
        except ImportError:
            print("Error: Could not import ACP server module.", file=sys.stderr)
            sys.exit(1)

        asyncio.run(
            run_acp_server(
                workspace_dir=workspace_path,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
            )
        )
        return

    if args.command == "mcp":
        run_mcp_server(
            workspace_dir=workspace_path,
            jar_path=jar_path,
            executor_version=args.executor_version,
            passthrough_args=unknown,
        )
        return

    try:
        from brokk_code.app import BrokkApp
    except ImportError:
        print("Error: Could not import BrokkApp. Is app.py missing?")
        sys.exit(1)

    session_id = getattr(args, "session", None)
    resume_session = getattr(args, "resume_session", False)
    pick_session = False

    if args.command == "resume":
        session_id = args.session_id
        resume_session = False  # Explicitly using the provided ID, not "last session" logic
    elif args.command == "sessions":
        # For explicit session picking, ignore any resume hints; the picker should run regardless.
        pick_session = True
        session_id = None
        resume_session = False

    if args.command == "exec":
        workspace_path = resolve_workspace_dir(workspace_path)
        asyncio.run(
            run_headless_job(
                workspace_dir=workspace_path,
                task_input=args.prompt,
                planner_model=args.planner_model,
                code_model=args.code_model,
                planner_reasoning_level=args.planner_reasoning_level,
                code_reasoning_level=args.code_reasoning_level,
                mode="LITE_AGENT",
                tags={"mode": "LITE_AGENT"},
                verbose=args.verbose,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
            )
        )
        return

    if args.command == "commit":
        asyncio.run(
            run_commit(
                workspace_dir=workspace_path,
                message=args.message,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
            )
        )
        return

    if args.command == "pr":
        if args.pr_command == "create":
            asyncio.run(
                run_pr_create(
                    workspace_dir=workspace_path,
                    title=args.title,
                    body=args.body,
                    base_branch=args.base,
                    head_branch=args.head,
                    github_token=args.github_token,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                    vendor=args.vendor,
                )
            )
        if args.pr_command == "review":
            repo_owner = args.repo_owner
            repo_name = args.repo_name
            if not repo_owner or not repo_name:
                inferred_owner, inferred_repo = infer_github_repo_from_remote(workspace_path)
                if not repo_owner:
                    repo_owner = inferred_owner
                if not repo_name:
                    repo_name = inferred_repo

            _validate_github_params(args.github_token, repo_owner, repo_name, "pr review")

            asyncio.run(
                run_pr_review_job(
                    workspace_dir=workspace_path,
                    pr_number=args.pr_number,
                    github_token=args.github_token,
                    repo_owner=repo_owner,
                    repo_name=repo_name,
                    planner_model=args.planner_model,
                    severity_threshold=args.severity,
                    verbose=args.verbose,
                    jar_path=jar_path,
                    executor_version=args.executor_version,
                    executor_snapshot=args.executor_snapshot,
                    vendor=args.vendor,
                )
            )
        return

    if args.command == "issue":
        if args.issue_command == "create":
            _run_issue_command(
                args,
                jar_path,
                command_name="issue create",
                mode="ISSUE_WRITER",
                task_input=args.prompt,
                action_label="Issue create",
            )
            return

        if args.issue_command == "diagnose":
            _run_issue_command(
                args,
                jar_path,
                command_name="issue diagnose",
                mode="ISSUE_DIAGNOSE",
                task_input=f"Diagnose GitHub Issue #{args.issue_number}",
                action_label="Issue diagnose",
                include_issue_number=True,
            )
            return

        if args.issue_command == "solve":
            _run_issue_command(
                args,
                jar_path,
                command_name="issue solve",
                mode="ISSUE",
                task_input=f"Resolve GitHub Issue #{args.issue_number}",
                action_label="Issue solve",
                include_issue_number=True,
                planner_reasoning_level=args.planner_reasoning_level,
                code_model=args.code_model,
                code_reasoning_level=args.code_reasoning_level,
                skip_verification=args.skip_verification,
                max_issue_fix_attempts=args.max_issue_fix_attempts,
                build_settings=args.build_settings,
            )
            return

    if not workspace_path.exists():
        print(f"Error: Workspace path does not exist: {workspace_path}")
        sys.exit(1)
    workspace_path = resolve_workspace_dir(workspace_path)

    app = BrokkApp(
        workspace_dir=workspace_path,
        jar_path=jar_path,
        executor_version=args.executor_version,
        executor_snapshot=args.executor_snapshot,
        session_id=session_id,
        resume_session=resume_session,
        pick_session=pick_session,
        vendor=args.vendor,
    )
    try:
        app.run()
    finally:
        # Best-effort cleanup for abnormal TUI exits (e.g., KeyboardInterrupt / runtime errors)
        # so the Java executor and Windows asyncio pipe transports do not linger.
        try:
            if app.executor.check_alive():
                asyncio.run(app.executor.stop())
        except Exception:
            pass

    get_renderables = getattr(app, "get_exit_transcript_renderables", None)
    transcript_renderables = get_renderables() if callable(get_renderables) else []
    get_transcript = getattr(app, "get_exit_transcript", None)
    transcript = get_transcript().strip() if callable(get_transcript) else ""
    if transcript_renderables:
        console = Console()
        for renderable in transcript_renderables:
            if renderable == "":
                console.print()
            else:
                console.print(renderable)
        console.print()
    elif transcript:
        print(transcript)
        print()

    # Print resume hint on exit if the session has tasks
    from brokk_code.session_persistence import (
        get_session_zip_resume_path,
        has_tasks,
        load_last_session_id,
    )

    last_id = load_last_session_id(workspace_path)
    if last_id:
        zip_path = get_session_zip_resume_path(workspace_path, last_id)
        if has_tasks(zip_path):
            print(f"brokk resume {last_id}")


if __name__ == "__main__":
    main()
