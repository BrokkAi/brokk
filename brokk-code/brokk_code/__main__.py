import argparse
import asyncio
import os
import re
import sys
from pathlib import Path
from typing import Any

from brokk_code.intellij_config import configure_intellij_acp_settings
from brokk_code.workspace import resolve_workspace_dir
from brokk_code.zed_config import ExistingBrokkCodeEntryError, configure_zed_acp_settings


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
            "Set 'Other Models' vendor preference (affects "
            + "internal roles like summarize/scan/commit). "
            "Use 'Default' to clear overrides."
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

    acp_parser = subparsers.add_parser("acp", help="Run in ACP server mode")
    _add_common_runtime_args(acp_parser)
    acp_parser.add_argument(
        "--ide",
        type=str,
        choices=["intellij", "zed"],
        default="intellij",
        help="ACP client profile to target (default: intellij)",
    )

    install_parser = subparsers.add_parser("install", help="Install integration settings")
    install_parser.add_argument(
        "target",
        choices=["zed", "intellij"],
        help="Install target for integration settings",
    )
    install_parser.add_argument(
        "--force",
        action="store_true",
        default=False,
        help="Overwrite existing install configuration when supported",
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
    issue_create_parser.add_argument(
        "--github-token",
        type=str,
        default=os.environ.get("GITHUB_TOKEN"),
        help="GitHub API token (defaults to GITHUB_TOKEN env var)",
    )
    issue_create_parser.add_argument(
        "--repo-owner",
        type=str,
        help="GitHub repository owner",
    )
    issue_create_parser.add_argument(
        "--repo-name",
        type=str,
        help="GitHub repository name",
    )
    # Default to a fast planner model for issue creation. Reasoning is disabled
    # explicitly in the headless submit path for this command.
    issue_create_parser.add_argument(
        "--planner-model",
        type=str,
        default="gemini-3-flash-preview",
        help="LLM model for planning (default: gemini-3-flash-preview)",
    )
    issue_create_parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Show full headless executor output (events/tokens) for debugging",
    )

    return parser


async def run_headless_job(
    workspace_dir: Path,
    task_input: str,
    planner_model: str,
    mode: str,
    tags: dict[str, str],
    planner_reasoning_level: str | None = None,
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

    def _event_data(event: dict[str, Any]) -> dict[str, Any]:
        raw = event.get("data")
        return raw if isinstance(raw, dict) else {}

    def _extract_message(event: dict[str, Any]) -> str:
        data = _event_data(event)
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
        data = _event_data(event)
        issue_url = data.get("issueUrl")
        if isinstance(issue_url, str) and issue_url.strip():
            _record_issue_url(issue_url.strip())

    def _render_spinner() -> None:
        nonlocal spinner_index, spinner_active
        if mode != "ISSUE_WRITER" or not spinner_enabled:
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
        sys.stdout.write("\r" + (" " * (len(spinner_label) + 8)) + "\r")
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

        # Create session before wait_ready to satisfy Java-side readiness requirements
        stage = "creating executor session"
        _update_shutdown_context()
        await manager.create_session(name=f"Headless {mode}")

        stage = "waiting for executor readiness"
        _update_shutdown_context()
        if not await manager.wait_ready():
            print(
                f"Error during {mode} job ({stage}): executor failed to become ready.",
                file=sys.stderr,
            )
            sys.exit(1)

        stage = "submitting job"
        _update_shutdown_context()
        _render_spinner()
        job_id = await manager.submit_job(
            task_input=task_input,
            planner_model=planner_model,
            reasoning_level=planner_reasoning_level,
            mode=mode,
            tags=tags,
        )
        _update_shutdown_context()

        stage = "streaming job events"
        _update_shutdown_context()
        async for event in manager.stream_events(job_id):
            _render_spinner()
            event_type = event.get("type")
            data = _event_data(event)
            if event_type == "NOTIFICATION":
                message = _extract_message(event)
                if not message:
                    continue
                _record_issue_url_from_issue_writer_notification(message)
                _record_issue_url(message)
                level = str(data.get("level", event.get("level", "INFO"))).strip().upper()
                # Keep headless issue mode quiet by default: warnings/errors matter,
                # routine INFO/COST/CONFIRM notifications do not.
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
                _record_issue_url(text)
                if verbose and text:
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

        if last_state in {"FAILED", "CANCELLED"}:
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
    args = parser.parse_args()

    if args.command == "install":
        try:
            if args.target == "zed":
                settings_path = configure_zed_acp_settings(force=args.force)
                target_name = "Zed"
            elif args.target == "intellij":
                settings_path = configure_intellij_acp_settings(force=args.force)
                target_name = "IntelliJ"
            else:
                # Should not happen due to argparse choices
                raise ValueError(f"Unknown target: {args.target}")
        except (ExistingBrokkCodeEntryError, ValueError) as exc:
            print(f"Error: {exc}", file=sys.stderr)
            sys.exit(1)

        print(f"Configured {target_name} ACP integration in {settings_path}")
        return

    workspace_path = Path(args.workspace).resolve()
    if not workspace_path.exists():
        print(f"Error: Workspace path does not exist: {workspace_path}")
        sys.exit(1)
    workspace_path = resolve_workspace_dir(workspace_path)
    jar_path = Path(args.jar).resolve() if args.jar else None

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
                ide=args.ide,
                vendor=args.vendor,
            )
        )
        return

    try:
        from brokk_code.app import BrokkApp
    except ImportError:
        print("Error: Could not import BrokkApp. Is app.py missing?")
        sys.exit(1)

    session_id = getattr(args, "session", None)
    resume_session = getattr(args, "resume_session", False)

    if args.command == "resume":
        session_id = args.session_id
        resume_session = False  # Explicitly using the provided ID, not "last session" logic

    if args.command == "issue" and args.issue_command == "create":
        # Handle issue create mode by launching a non-interactive job
        tags = {
            "github_token": args.github_token or "",
            "repo_owner": args.repo_owner or "",
            "repo_name": args.repo_name or "",
        }

        asyncio.run(
            run_headless_job(
                workspace_dir=workspace_path,
                task_input=args.prompt,
                planner_model=args.planner_model,
                planner_reasoning_level="disable",
                verbose=args.verbose,
                mode="ISSUE_WRITER",
                tags=tags,
                jar_path=jar_path,
                executor_version=args.executor_version,
                executor_snapshot=args.executor_snapshot,
                vendor=args.vendor,
            )
        )
        return

    app = BrokkApp(
        workspace_dir=workspace_path,
        jar_path=jar_path,
        executor_version=args.executor_version,
        executor_snapshot=args.executor_snapshot,
        session_id=session_id,
        resume_session=resume_session,
        vendor=args.vendor,
    )
    app.run()

    # Print resume hint on exit if the session has tasks
    from brokk_code.session_persistence import (
        get_session_zip_path,
        has_tasks,
        load_last_session_id,
    )

    last_id = load_last_session_id(workspace_path)
    if last_id:
        zip_path = get_session_zip_path(workspace_path, last_id)
        if has_tasks(zip_path):
            print(f"brokk resume {last_id}")


if __name__ == "__main__":
    main()
