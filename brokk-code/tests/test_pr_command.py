"""Tests for the /pr slash command."""

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp, PrCreateModalScreen


def test_pr_in_slash_command_catalog():
    """Verify /pr is listed in the slash commands catalog."""
    commands = BrokkApp.get_slash_commands()
    command_names = [c["command"] for c in commands]
    assert "/pr" in command_names

    pr_cmd = next(c for c in commands if c["command"] == "/pr")
    assert "description" in pr_cmd
    assert pr_cmd["description"]


def test_handle_command_pr_requires_executor_ready(tmp_path: Path):
    """Verify /pr shows error when executor is not ready."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = False

    # Mock the chat panel
    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    app._handle_command("/pr")

    mock_chat.add_system_message.assert_called_once()
    call_args = mock_chat.add_system_message.call_args
    assert "not ready" in call_args[0][0].lower()
    assert call_args[1].get("level") == "ERROR"


def test_handle_command_pr_no_base_branch(tmp_path: Path):
    """Verify /pr without base branch calls _create_pull_request(None)."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    with patch.object(app, "run_worker") as mock_run_worker:
        app._handle_command("/pr")
        mock_run_worker.assert_called_once()
        # The coroutine should be _create_pull_request(None)
        coro = mock_run_worker.call_args[0][0]
        assert coro is not None


def test_handle_command_pr_with_base_branch(tmp_path: Path):
    """Verify /pr with base branch parses argument correctly."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    with patch.object(app, "run_worker") as mock_run_worker:
        app._handle_command("/pr main")
        mock_run_worker.assert_called_once()


@pytest.mark.asyncio
async def test_create_pull_request_fetches_sessions_and_suggestion(tmp_path: Path):
    """Verify _create_pull_request fetches overlapping sessions and PR suggestion from executor."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_sessions = AsyncMock(
        return_value={
            "sessions": [
                {"id": "session-1", "name": "Session One", "taskCount": 3},
                {"id": "session-2", "name": "Session Two", "taskCount": 5},
            ],
            "sourceBranch": "feature-branch",
            "targetBranch": "main",
        }
    )
    mock_executor.pr_suggest = AsyncMock(
        return_value={
            "title": "Add new feature",
            "description": "This PR adds a cool feature",
            "sourceBranch": "feature-branch",
            "targetBranch": "main",
        }
    )

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True
    app.current_branch = "feature-branch"

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    # Mock push_screen to capture the modal
    pushed_screen = {}

    def capture_push_screen(screen, callback=None):
        pushed_screen["screen"] = screen
        pushed_screen["callback"] = callback

    app.push_screen = MagicMock(side_effect=capture_push_screen)

    await app._create_pull_request(base_branch="main")

    # Verify overlapping sessions were fetched with correct branches
    mock_executor.pr_sessions.assert_called_once_with(
        source_branch="feature-branch",
        target_branch="main",
    )

    # Verify pr_suggest was called with ALL overlapping session IDs (Swing behavior)
    mock_executor.pr_suggest.assert_called_once_with(
        source_branch="feature-branch",
        target_branch="main",
        session_ids=["session-1", "session-2"],
    )

    # Verify modal was pushed with correct data including sessions
    assert "screen" in pushed_screen
    screen = pushed_screen["screen"]
    assert isinstance(screen, PrCreateModalScreen)
    assert screen._suggested_title == "Add new feature"
    assert screen._suggested_body == "This PR adds a cool feature"
    assert screen._source_branch == "feature-branch"
    assert screen._target_branch == "main"
    assert len(screen._sessions) == 2
    # All overlapping sessions should be pre-selected
    assert "session-1" in screen._selected_session_ids
    assert "session-2" in screen._selected_session_ids


@pytest.mark.asyncio
async def test_do_create_pr_success(tmp_path: Path):
    """Verify _do_create_pr handles successful PR creation."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_create = AsyncMock(
        return_value={"url": "https://github.com/owner/repo/pull/42"}
    )

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.add_system_message_markup = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)
    app._refresh_context_panel = AsyncMock()

    await app._do_create_pr(
        title="My PR",
        body="PR description",
        source_branch="feature",
        target_branch="main",
    )

    mock_executor.pr_create.assert_called_once_with(
        title="My PR",
        body="PR description",
        source_branch="feature",
        target_branch="main",
        session_ids=None,
    )

    # Check that the PR URL was shown
    mock_chat.add_system_message_markup.assert_called_once()
    call_arg = mock_chat.add_system_message_markup.call_args[0][0]
    assert "https://github.com/owner/repo/pull/42" in call_arg


@pytest.mark.asyncio
async def test_do_create_pr_with_session_ids(tmp_path: Path):
    """Verify _do_create_pr forwards session_ids to executor."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_create = AsyncMock(
        return_value={"url": "https://github.com/owner/repo/pull/42"}
    )

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.add_system_message_markup = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)
    app._refresh_context_panel = AsyncMock()

    await app._do_create_pr(
        title="My PR",
        body="PR description",
        source_branch="feature",
        target_branch="main",
        session_ids=["session-1", "session-2"],
    )

    mock_executor.pr_create.assert_called_once_with(
        title="My PR",
        body="PR description",
        source_branch="feature",
        target_branch="main",
        session_ids=["session-1", "session-2"],
    )


@pytest.mark.asyncio
async def test_do_create_pr_error(tmp_path: Path):
    """Verify _do_create_pr handles errors gracefully."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_create = AsyncMock(side_effect=Exception("GitHub API error"))

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    await app._do_create_pr(
        title="My PR",
        body="PR description",
        source_branch="feature",
        target_branch="main",
    )

    # Find the error message call
    error_calls = [
        call
        for call in mock_chat.add_system_message.call_args_list
        if call[1].get("level") == "ERROR"
    ]
    assert len(error_calls) == 1
    assert "failed" in error_calls[0][0][0].lower()


@pytest.mark.asyncio
async def test_create_pull_request_suggestion_error(tmp_path: Path):
    """Verify _create_pull_request handles suggestion errors gracefully."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_sessions = AsyncMock(
        return_value={"sessions": [], "sourceBranch": "feature-branch", "targetBranch": "main"}
    )
    mock_executor.pr_suggest = AsyncMock(side_effect=Exception("Network error"))

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True
    app.current_branch = "feature-branch"

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    await app._create_pull_request()

    # Find the error message call
    error_calls = [
        call
        for call in mock_chat.add_system_message.call_args_list
        if call[1].get("level") == "ERROR"
    ]
    assert len(error_calls) == 1
    assert "suggestion" in error_calls[0][0][0].lower()
    # Ensure "sessions" is NOT in the error message (distinguishing from sessions failure)
    assert "sessions" not in error_calls[0][0][0].lower()

    # Verify set_job_running(False) was called after the error
    mock_chat.set_job_running.assert_called()
    assert mock_chat.set_job_running.call_args_list[-1] == (((False,), {}))


@pytest.mark.asyncio
async def test_create_pull_request_sessions_error(tmp_path: Path):
    """Verify _create_pull_request handles pr_sessions errors gracefully with specific message."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.pr_sessions = AsyncMock(side_effect=Exception("Network error"))
    mock_executor.pr_suggest = AsyncMock()

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True
    app.current_branch = "feature-branch"

    mock_chat = MagicMock()
    mock_chat.add_system_message = MagicMock()
    mock_chat.set_job_running = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    await app._create_pull_request()

    # Find the error message call
    error_calls = [
        call
        for call in mock_chat.add_system_message.call_args_list
        if call[1].get("level") == "ERROR"
    ]
    assert len(error_calls) == 1
    # Error message should mention "sessions", not "suggestion"
    assert "sessions" in error_calls[0][0][0].lower()
    assert "suggestion" not in error_calls[0][0][0].lower()

    # pr_suggest should NOT have been called since pr_sessions failed
    mock_executor.pr_suggest.assert_not_called()

    # Verify set_job_running(False) was called after the error
    mock_chat.set_job_running.assert_called()
    assert mock_chat.set_job_running.call_args_list[-1] == (((False,), {}))


def test_pr_create_modal_screen_with_sessions():
    """Verify PrCreateModalScreen can be instantiated with session parameters."""
    sessions = [
        {"id": "sess-1", "name": "Session One"},
        {"id": "sess-2", "name": "Session Two"},
    ]
    screen = PrCreateModalScreen(
        suggested_title="Test Title",
        suggested_body="Test Body",
        source_branch="feature",
        target_branch="main",
        sessions=sessions,
        selected_session_ids=["sess-1"],
    )
    assert screen._suggested_title == "Test Title"
    assert screen._suggested_body == "Test Body"
    assert screen._source_branch == "feature"
    assert screen._target_branch == "main"
    assert len(screen._sessions) == 2
    assert "sess-1" in screen._selected_session_ids
    assert "sess-2" not in screen._selected_session_ids
    # Verify session ID order is preserved
    assert screen._session_id_order == ["sess-1", "sess-2"]


def test_pr_create_modal_screen_without_sessions():
    """Verify PrCreateModalScreen works without sessions (backwards compatible)."""
    screen = PrCreateModalScreen(
        suggested_title="Test Title",
        suggested_body="Test Body",
        source_branch="feature",
        target_branch="main",
    )
    assert screen._suggested_title == "Test Title"
    assert screen._suggested_body == "Test Body"
    assert screen._source_branch == "feature"
    assert screen._target_branch == "main"
    assert len(screen._sessions) == 0
    assert len(screen._selected_session_ids) == 0
    assert screen._session_id_order == []


def test_pr_create_modal_screen_deterministic_session_order():
    """Verify selected session IDs are returned in display order, not set iteration order."""
    sessions = [
        {"id": "sess-a", "name": "Session A"},
        {"id": "sess-b", "name": "Session B"},
        {"id": "sess-c", "name": "Session C"},
    ]
    screen = PrCreateModalScreen(
        suggested_title="Test",
        suggested_body="Body",
        source_branch="feature",
        target_branch="main",
        sessions=sessions,
        # Select in reverse order to verify ordering is by display, not selection
        selected_session_ids=["sess-c", "sess-a"],
    )
    # The internal set has both
    assert "sess-a" in screen._selected_session_ids
    assert "sess-c" in screen._selected_session_ids
    # But they should come out in display order (a, c) not selection order (c, a)
    ordered = [sid for sid in screen._session_id_order if sid in screen._selected_session_ids]
    assert ordered == ["sess-a", "sess-c"]
