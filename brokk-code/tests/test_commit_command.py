"""Tests for the /commit slash command."""

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


def test_commit_in_slash_command_catalog():
    """Verify /commit is listed in the slash commands catalog."""
    commands = BrokkApp.get_slash_commands()
    command_names = [c["command"] for c in commands]
    assert "/commit" in command_names

    commit_cmd = next(c for c in commands if c["command"] == "/commit")
    assert "description" in commit_cmd
    assert commit_cmd["description"]


def test_handle_command_commit_requires_executor_ready(tmp_path: Path):
    """Verify /commit shows error when executor is not ready."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = False

    # Mock the chat panel
    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    app._handle_command("/commit")

    mock_chat.add_system_message.assert_called_once()
    call_args = mock_chat.add_system_message.call_args
    assert "not ready" in call_args[0][0].lower()
    assert call_args[1].get("level") == "ERROR"


def test_handle_command_commit_no_message(tmp_path: Path):
    """Verify /commit without message calls commit_context(None)."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    with patch.object(app, "run_worker") as mock_run_worker:
        app._handle_command("/commit")
        mock_run_worker.assert_called_once()
        # The coroutine should be _commit_changes(None)
        coro = mock_run_worker.call_args[0][0]
        assert coro is not None
        coro.close()


def test_handle_command_commit_with_message(tmp_path: Path):
    """Verify /commit with message parses message correctly."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)

    with patch.object(app, "run_worker") as mock_run_worker:
        app._handle_command("/commit Fix the bug in parser")
        mock_run_worker.assert_called_once()
        coro = mock_run_worker.call_args[0][0]
        coro.close()


@pytest.mark.asyncio
async def test_commit_changes_success(tmp_path: Path):
    """Verify _commit_changes handles successful commit."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.commit_context = AsyncMock(
        return_value={"commitId": "abc1234567890", "firstLine": "Fix parser bug"}
    )

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    mock_chat.add_system_message_markup = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)
    app._refresh_context_panel = AsyncMock()

    await app._commit_changes("Fix parser bug")

    mock_executor.commit_context.assert_called_once_with("Fix parser bug")
    mock_chat.add_system_message_markup.assert_called_once()
    call_arg = mock_chat.add_system_message_markup.call_args[0][0]
    assert "abc1234" in call_arg
    assert "Fix parser bug" in call_arg


@pytest.mark.asyncio
async def test_commit_changes_no_changes(tmp_path: Path):
    """Verify _commit_changes handles no changes case."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.commit_context = AsyncMock(return_value={"status": "no_changes"})

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    await app._commit_changes(None)

    mock_executor.commit_context.assert_called_once_with(None)
    mock_chat.add_system_message.assert_called_once()
    assert "no uncommitted changes" in mock_chat.add_system_message.call_args[0][0].lower()


@pytest.mark.asyncio
async def test_commit_changes_error(tmp_path: Path):
    """Verify _commit_changes handles errors gracefully."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.commit_context = AsyncMock(side_effect=Exception("Git error"))

    app = BrokkApp(workspace_dir=tmp_path, executor=mock_executor)
    app._executor_ready = True

    mock_chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    await app._commit_changes("test message")

    mock_chat.add_system_message.assert_called_once()
    call_args = mock_chat.add_system_message.call_args
    assert "failed" in call_args[0][0].lower()
    assert call_args[1].get("level") == "ERROR"
