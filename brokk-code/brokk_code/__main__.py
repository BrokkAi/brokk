import argparse
import asyncio
import sys
from pathlib import Path

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

    subparsers = parser.add_subparsers(dest="command")

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
        choices=["zed"],
        help="Install target for integration settings",
    )
    install_parser.add_argument(
        "--force",
        action="store_true",
        default=False,
        help="Overwrite existing install configuration when supported",
    )

    return parser


def main():
    parser = _build_parser()
    args = parser.parse_args()

    if args.command == "install":
        try:
            settings_path = configure_zed_acp_settings(force=args.force)
        except ExistingBrokkCodeEntryError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            sys.exit(1)
        except ValueError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            sys.exit(1)

        print(f"Configured Zed ACP integration in {settings_path}")
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

    app = BrokkApp(
        workspace_dir=workspace_path,
        jar_path=jar_path,
        executor_version=args.executor_version,
        executor_snapshot=args.executor_snapshot,
        session_id=args.session,
        resume_session=args.resume_session,
        vendor=args.vendor,
    )
    app.run()


if __name__ == "__main__":
    main()
