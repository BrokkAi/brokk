import argparse
import asyncio
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Brokk Code - Interactive Terminal Interface")
    parser.add_argument(
        "command",
        nargs="?",
        choices=["acp"],
        help="Run in ACP server mode over stdio",
    )
    parser.add_argument(
        "--workspace",
        type=str,
        default=".",
        help="Path to the workspace directory (default: current directory)",
    )
    parser.add_argument(
        "--jar",
        type=str,
        default=None,
        help="Path to brokk.jar (default: auto-download to ~/.brokk/)",
    )
    parser.add_argument(
        "--executor-version",
        type=str,
        default=None,
        help="Specific version/tag of the executor to download (default: latest snapshot)",
    )
    parser.add_argument(
        "--executor-snapshot",
        action="store_true",
        default=True,
        help=(
            "Download the latest snapshot release if no specific version is provided "
            "(default: True)"
        ),
    )
    parser.add_argument(
        "--executor-stable",
        action="store_false",
        dest="executor_snapshot",
        help="Download the latest stable release instead of the snapshot",
    )
    parser.add_argument(
        "--session",
        type=str,
        default=None,
        help="Attempt to resume a specific session by ID",
    )
    parser.add_argument(
        "--no-resume",
        action="store_false",
        dest="resume_session",
        default=True,
        help="Always create a new session instead of resuming the last one",
    )
    parser.add_argument(
        "--new-session",
        action="store_false",
        dest="resume_session",
        help="Synonym for --no-resume",
    )
    args = parser.parse_args()

    workspace_path = Path(args.workspace).resolve()
    if not workspace_path.exists():
        print(f"Error: Workspace path does not exist: {workspace_path}")
        sys.exit(1)
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
            )
        )
        return

    try:
        from brokk_code.app import BrokkApp
    except ImportError:
        print("Error: Could not import BrokkApp. Is app.py missing?")
        sys.exit(1)

    app = BrokkApp(
        workspace_dir=workspace_path,
        jar_path=jar_path,
        executor_version=args.executor_version,
        executor_snapshot=args.executor_snapshot,
        session_id=args.session,
        resume_session=args.resume_session,
    )
    app.run()


if __name__ == "__main__":
    main()
