import argparse
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Brokk Code - Interactive Terminal Interface")
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
    args = parser.parse_args()

    try:
        from brokk_code.app import BrokkApp

        workspace_path = Path(args.workspace).resolve()
        if not workspace_path.exists():
            print(f"Error: Workspace path does not exist: {workspace_path}")
            sys.exit(1)

        jar_path = Path(args.jar).resolve() if args.jar else None
        app = BrokkApp(workspace_dir=workspace_path, jar_path=jar_path)
        app.run()
    except ImportError:
        print("Error: Could not import BrokkApp. Is app.py missing?")
        sys.exit(1)


if __name__ == "__main__":
    main()
