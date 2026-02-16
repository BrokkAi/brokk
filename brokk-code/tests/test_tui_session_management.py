"""Tests for TUI multi-session management commands."""

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


@pytest.fixture
def mock_executor():
    """Creates a mock ExecutorManager with session methods."""
    executor = MagicMock(spec=ExecutorManager)
    executor.workspace_dir = Path("/tmp/test_workspace")
    executor.session_id = "test-session-id"
    executor.resolved_jar_path = Path("/tmp/brokk.jar")

    executor.list_sessions = AsyncMock(
        return_value={
            "sessions": [
                {"id": "session-1", "name": "Session One", "active": True},
                {"id": "session-2", "name": "Session Two", "active": False},
            ]
        }
    )
    executor.create_session = AsyncMock(return_value="new-session-id")
    executor.import_session_zip = AsyncMock(return_value="imported-session-id")
    executor.delete_session = AsyncMock()
    executor.get_context = AsyncMock(return_value={"fragments": [], "usedTokens": 0})
    executor.get_tasklist = AsyncMock(return_value={"bigPicture": None, "tasks": []})

    return executor


@pytest.fixture
def app_with_mock_executor(mock_executor, tmp_path):
    """Creates a BrokkApp with a mocked executor."""
    mock_executor.workspace_dir = tmp_path
    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True
    return app


class TestSessionCommandParsing:
    """Tests for /session command parsing logic."""

    def test_handle_session_command_no_args_shows_current(self, app_with_mock_executor):
        """Test /session with no args shows current session."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session"]

        app._handle_session_command(parts, mock_chat)

        assert mock_chat.add_system_message.call_count >= 1
        first_call = mock_chat.add_system_message.call_args_list[0]
        assert "test-session-id" in first_call[0][0]

    def test_handle_session_command_list_triggers_worker(self, app_with_mock_executor):
        """Test /session list triggers the list worker."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "list"]

        with patch.object(app, "run_worker") as mock_run_worker:
            app._handle_session_command(parts, mock_chat)
            mock_run_worker.assert_called_once()

    def test_handle_session_command_new_requires_name(self, app_with_mock_executor):
        """Test /session new without name shows usage."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "new"]

        app._handle_session_command(parts, mock_chat)

        mock_chat.add_system_message.assert_called_with("Usage: /session new <name>")

    def test_handle_session_command_new_with_name_triggers_worker(
        self, app_with_mock_executor
    ):
        """Test /session new <name> triggers worker."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "new", "My", "New", "Session"]

        with patch.object(app, "run_worker") as mock_run_worker:
            app._handle_session_command(parts, mock_chat)
            mock_run_worker.assert_called_once()

    def test_handle_session_command_switch_requires_id(self, app_with_mock_executor):
        """Test /session switch without id shows usage."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "switch"]

        app._handle_session_command(parts, mock_chat)

        mock_chat.add_system_message.assert_called_with("Usage: /session switch <id>")

    def test_handle_session_command_delete_requires_id(self, app_with_mock_executor):
        """Test /session delete without id shows usage."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "delete"]

        app._handle_session_command(parts, mock_chat)

        mock_chat.add_system_message.assert_called_with("Usage: /session delete <id>")

    def test_handle_session_command_unknown_subcommand(self, app_with_mock_executor):
        """Test /session unknown shows help."""
        app = app_with_mock_executor
        mock_chat = MagicMock()
        parts = ["/session", "foobar"]

        app._handle_session_command(parts, mock_chat)

        mock_chat.add_system_message.assert_called_with(
            "Unknown /session subcommand. Use: list | new <name> | switch <id> | delete <id>"
        )


@pytest.mark.asyncio
class TestSessionAsyncMethods:
    """Tests for async session management methods."""

    async def test_session_list_shows_remote_sessions(self, app_with_mock_executor):
        """Test _session_list displays remote sessions."""
        app = app_with_mock_executor

        mock_chat = MagicMock()
        with patch.object(app, "query_one", return_value=mock_chat):
            await app._session_list()

        app.executor.list_sessions.assert_called_once()
        assert mock_chat.add_system_message.call_count >= 2

    async def test_session_list_fallback_to_local(self, app_with_mock_executor, tmp_path):
        """Test _session_list falls back to local zips when remote fails."""
        app = app_with_mock_executor
        app.executor.list_sessions = AsyncMock(side_effect=Exception("Network error"))
        app.executor.workspace_dir = tmp_path

        sessions_dir = tmp_path / ".brokk" / "sessions"
        sessions_dir.mkdir(parents=True)
        (sessions_dir / "11111111-1111-1111-1111-111111111111.zip").write_bytes(b"test")

        mock_chat = MagicMock()
        with patch.object(app, "query_one", return_value=mock_chat):
            await app._session_list()

        calls = [str(c) for c in mock_chat.add_system_message.call_args_list]
        assert any("local cache" in c for c in calls)

    async def test_session_new_creates_and_persists(self, app_with_mock_executor, tmp_path):
        """Test _session_new creates session and saves last session id."""
        app = app_with_mock_executor
        app.executor.workspace_dir = tmp_path

        mock_chat = MagicMock()
        with patch.object(app, "query_one", return_value=mock_chat):
            with patch.object(app, "_refresh_context_panel", new_callable=AsyncMock):
                await app._session_new("Test Session")

        app.executor.create_session.assert_called_once_with("Test Session")
        assert app.executor.session_id == "new-session-id"

        from brokk_code.session_persistence import load_last_session_id

        saved = load_last_session_id(tmp_path)
        assert saved == "new-session-id"

    async def test_session_switch_updates_state(self, app_with_mock_executor, tmp_path):
        """Test _session_switch updates executor state and persists."""
        app = app_with_mock_executor
        app.executor.workspace_dir = tmp_path

        mock_chat = MagicMock()
        with patch.object(app, "query_one", return_value=mock_chat):
            with patch.object(app, "_refresh_context_panel", new_callable=AsyncMock):
                await app._session_switch("target-session-id")

        assert app.executor.session_id == "target-session-id"

        from brokk_code.session_persistence import load_last_session_id

        saved = load_last_session_id(tmp_path)
        assert saved == "target-session-id"

    async def test_session_delete_removes_and_creates_fallback(
        self, app_with_mock_executor, tmp_path
    ):
        """Test _session_delete removes session and creates fallback if active."""
        app = app_with_mock_executor
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = "to-delete-id"

        sessions_dir = tmp_path / ".brokk" / "sessions"
        sessions_dir.mkdir(parents=True)
        zip_path = sessions_dir / "to-delete-id.zip"
        zip_path.write_bytes(b"test")

        mock_chat = MagicMock()
        with patch.object(app, "query_one", return_value=mock_chat):
            with patch.object(app, "_refresh_context_panel", new_callable=AsyncMock):
                await app._session_delete("to-delete-id")

        app.executor.delete_session.assert_called_once_with("to-delete-id")
        assert not zip_path.exists()
        app.executor.create_session.assert_called_once()
        assert app.executor.session_id == "new-session-id"
