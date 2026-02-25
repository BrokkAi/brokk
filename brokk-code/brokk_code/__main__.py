import argparse
import asyncio
import os
import sys
from pathlib import Path

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
    issue_create_parser.add_argument(
        "--planner-model",
        type=str,
        default="claude-3-5-sonnet-latest",
        help="LLM model for planning (default: claude-3-5-sonnet-latest)",
    )

    return parser


async def run_headless_job(
    workspace_dir: Path,
    task_input: str,
    planner_model: str,
    mode: str,
    tags: dict[str, str],
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
    )

    try:
        await manager.start()
        if not await manager.wait_ready():
            print("Error: Executor failed to become ready.", file=sys.stderr)
            sys.exit(1)

        await manager.create_session(name=f"Headless {mode}")

        print(f"Submitting {mode} job...")
        job_id = await manager.submit_job(
            task_input=task_input,
            planner_model=planner_model,
            mode=mode,
            tags=tags,
        )
        print(f"Job submitted: {job_id}")

        async for event in manager.stream_events(job_id):
            event_type = event.get("type")
            if event_type == "NOTIFICATION":
                print(f"[{event.get('level', 'INFO')}] {event.get('message')}")
            elif event_type == "STATE_CHANGE":
                print(f"Job state: {event.get('state')}")
            elif event_type == "TOKEN":
                # Print tokens without newlines to stream text
                sys.stdout.write(event.get("text", ""))
                sys.stdout.flush()
            elif event_type == "ERROR":
                print(f"\nError event: {event.get('message')}", file=sys.stderr)

        print("\nJob finished.")

    except ExecutorError as e:
        print(f"Executor error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
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
