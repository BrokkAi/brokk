import asyncio
from unittest.mock import patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.session_persistence import get_session_zip_path, save_last_session_id
from tests.test_tui_resubmit import StubExecutor


class SessionStubExecutor(StubExecutor):
    def __init__(self):
        # We don't call super().__init__ with Path('.') because BrokkApp
        # will now correctly set the workspace_dir on the injected executor.
        self.workspace_dir = None
        self.import_calls = []
        self.create_calls = []
        self.download_calls = []

    async def create_session(self, name: str = "TUI Session") -> str:
        self.create_calls.append(name)
        self.session_id = "new-session"
        return self.session_id

    async def import_session_zip(self, zip_bytes: bytes, session_id: str = None) -> str:
        self.import_calls.append((zip_bytes, session_id))
        self.session_id = session_id or "imported-session"
        return self.session_id

    async def download_session_zip(self, session_id: str) -> bytes:
        self.download_calls.append(session_id)
        return b"fake-zip-data"


@pytest.mark.asyncio
async def test_startup_creates_new_session_by_default(tmp_path):
    workspace = tmp_path
    stub = SessionStubExecutor()
    # Default behavior is no resume
    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=False)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.create_calls) == 1
    assert len(stub.import_calls) == 0


@pytest.mark.asyncio
async def test_startup_imports_when_last_session_exists(tmp_path):
    workspace = tmp_path
    last_id = "last-123"
    save_last_session_id(workspace, last_id)

    zip_path = get_session_zip_path(workspace, last_id)
    zip_path.write_bytes(b"zip-data")

    stub = SessionStubExecutor()
    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=True)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == last_id
    assert stub.import_calls[0][0] == b"zip-data"
    assert len(stub.create_calls) == 0


@pytest.mark.asyncio
async def test_startup_prefers_cli_session(tmp_path):
    workspace = tmp_path
    cli_id = "cli-456"
    last_id = "last-123"
    save_last_session_id(workspace, last_id)

    # Zip exists for both
    get_session_zip_path(workspace, cli_id).write_bytes(b"cli-zip")
    get_session_zip_path(workspace, last_id).write_bytes(b"last-zip")

    stub = SessionStubExecutor()
    app = BrokkApp(executor=stub, workspace_dir=workspace, session_id=cli_id)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == cli_id
    assert stub.import_calls[0][0] == b"cli-zip"


@pytest.mark.asyncio
async def test_shutdown_exports_session(tmp_path):
    workspace = tmp_path
    stub = SessionStubExecutor()
    stub.session_id = "active-789"

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    app._executor_ready = True

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app.action_quit(), timeout=3.0)

    assert "active-789" in stub.download_calls
    zip_path = get_session_zip_path(workspace, "active-789")
    assert zip_path.exists()
    assert zip_path.read_bytes() == b"fake-zip-data"


@pytest.mark.asyncio
async def test_resume_command_launches_with_correct_id(tmp_path):
    """
    Verifies that the 'resume <id>' command correctly initializes BrokkApp
    with the specified session_id.
    """
    from brokk_code.__main__ import _build_parser

    workspace = tmp_path
    session_id = "manual-resume-id"

    # Simulate: brokk-code resume manual-resume-id --workspace <tmp_path>
    parser = _build_parser()
    args = parser.parse_args(["resume", session_id, "--workspace", str(workspace)])

    assert args.command == "resume"
    assert args.session_id == session_id

    stub = SessionStubExecutor()
    # We simulate what main() does
    app = BrokkApp(
        executor=stub,
        workspace_dir=workspace,
        session_id=args.session_id,
        resume_session=False,
    )

    assert app.requested_session_id == session_id
    assert app.resume_session is False

    with patch("brokk_code.app.ChatPanel"):
        # We need to set a valid zip for the stub to "resume" it
        get_session_zip_path(workspace, session_id).write_bytes(b"resumed-zip")
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == session_id


def test_main_prints_resume_hint_on_exit(tmp_path, capsys):
    """
    Verifies that main() prints the resume hint after the app finishes running,
    if a last session ID exists AND has tasks.
    """
    import zipfile
    import json
    from brokk_code.__main__ import main

    workspace = tmp_path
    session_id = "hint-session-789"
    save_last_session_id(workspace, session_id)

    # Create a zip with tasks so the hint prints
    zip_path = get_session_zip_path(workspace, session_id)
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("tasklist.json", json.dumps({"tasks": [{"id": "1", "title": "test"}]}))

    # Patch BrokkApp.run so it doesn't actually start the TUI
    # and sys.argv so main() uses our temp workspace.
    with (
        patch("brokk_code.app.BrokkApp.run", return_value=None),
        patch(
            "sys.argv",
            ["brokk-code", "--workspace", str(workspace)],
        ),
    ):
        main()

    captured = capsys.readouterr()
    expected_hint = f"brokk-code resume {session_id}"
    assert expected_hint in captured.out


def test_main_omits_resume_hint_when_no_tasks(tmp_path, capsys):
    """
    Verifies that main() does NOT print the resume hint if the session exists but has no tasks.
    """
    import zipfile
    import json
    from brokk_code.__main__ import main

    workspace = tmp_path
    session_id = "no-tasks-session"
    save_last_session_id(workspace, session_id)

    # Create a zip with NO tasks
    zip_path = get_session_zip_path(workspace, session_id)
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("tasklist.json", json.dumps({"tasks": []}))

    with (
        patch("brokk_code.app.BrokkApp.run", return_value=None),
        patch(
            "sys.argv",
            ["brokk-code", "--workspace", str(workspace)],
        ),
    ):
        main()

    captured = capsys.readouterr()
    assert "brokk-code resume" not in captured.out
