import pytest
from pathlib import Path
from unittest.mock import AsyncMock, patch
from brokk_code.app import BrokkApp
from brokk_code.session_persistence import save_last_session_id, get_session_zip_path
from tests.test_tui_resubmit import StubExecutor


class SessionStubExecutor(StubExecutor):
    def __init__(self):
        super().__init__()
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
async def test_startup_creates_new_session_when_no_resume(tmp_path):
    workspace = tmp_path
    stub = SessionStubExecutor()
    # --no-resume
    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=False)

    with patch("brokk_code.app.ChatPanel"):
        await app._start_executor()

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
        await app._start_executor()

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
        await app._start_executor()

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
        await app.action_quit()

    assert "active-789" in stub.download_calls
    zip_path = get_session_zip_path(workspace, "active-789")
    assert zip_path.exists()
    assert zip_path.read_bytes() == b"fake-zip-data"
