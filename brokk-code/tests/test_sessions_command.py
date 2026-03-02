from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_sessions_command_in_catalog():
    """Verify /sessions appears in the slash command list."""
    commands = BrokkApp.get_slash_commands()
    assert any(c["command"] == "/sessions" for c in commands)


@pytest.mark.asyncio
async def test_show_sessions_flow(tmp_path):
    """Verify _show_sessions logic with a stub executor."""
    # Setup stub app and executor
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app.executor.workspace_dir = tmp_path
    app._executor_ready = True

    # Mock list_sessions response
    sessions_data = {
        "sessions": [
            {"id": "s1", "name": "Session 1", "aiResponses": 3},
            {"id": "s2", "name": "Session 2", "aiResponses": 0},
        ],
        "currentSessionId": "s1",
    }
    app.executor.list_sessions = AsyncMock(return_value=sessions_data)

    # Mock chat and screen pushing
    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)
    app.push_screen = MagicMock()

    await app._show_sessions()

    # Verify interaction
    app.executor.list_sessions.assert_called_once()
    app.push_screen.assert_called_once()

    # Extract the callback passed to push_screen
    args, kwargs = app.push_screen.call_args
    callback = args[1]

    # Simulate selecting session s2
    app.run_worker = MagicMock()
    app._switch_to_session = AsyncMock()
    await callback("s2")

    # Verify switch was triggered via worker
    app.run_worker.assert_called_once()
    # Await the coroutine to prevent RuntimeWarning
    await app.run_worker.call_args[0][0]
    app._switch_to_session.assert_called_once_with("s2")


def test_session_select_modal_labels_use_table_layout():
    """Verify SessionSelectModal formatting logic for table-like layout."""
    from brokk_code.app import SessionSelectModal

    # 1735732800000 is 2025-01-01 12:00:00 UTC (approx)
    sessions = [
        {
            "id": "s1",
            "name": "My Project Session",
            "aiResponses": 3,
            "modified": 1735732800000,
        },
        {
            "id": "s2",
            "name": "Empty Session",
            "aiResponses": 0,
            "modified": 0,
        },
    ]

    # Test the internal formatter directly
    label1 = SessionSelectModal._format_session_row(sessions[0])

    # Should not have [x] or [ ]
    assert not label1.startswith("[")

    # Should contain title, date, and "entries"
    assert "My Project Session" in label1
    assert "2025-01-01" in label1
    assert "3 entries" in label1

    # Check approximate alignment (title width 60 + 2 spaces)
    # The date should start at index 62
    assert label1.find("2025-01-01") == 62
    # The entries should start after the date (62 + 16 + 2 = 80)
    assert label1.find("3 entries") == 80

    label2 = SessionSelectModal._format_session_row(sessions[1])
    assert "Empty Session" in label2
    assert "2025" not in label2  # No date for 0
    assert "entry" not in label2  # No label for 0


def test_session_select_modal_long_autoname_truncation():
    """Verify that auto-generated long titles are truncated gracefully in the modal."""
    from brokk_code.app import SessionSelectModal

    long_title = "Implement a new login system with oauth support and more..."
    session = {
        "id": "s3",
        "name": long_title,
        "aiResponses": 5,
        "modified": 1735732800000,
    }

    label = SessionSelectModal._format_session_row(session)

    # The title width is 60.
    expected_substring = long_title[:60]
    assert expected_substring in label
    # Ensure date starts at index 62 (title 60 + 2 spaces)
    assert label.find("2025-01-01") == 62


@pytest.mark.asyncio
async def test_executor_delete_session(tmp_path):
    """Verify ExecutorManager.delete_session sends the correct request."""

    import httpx

    from brokk_code.executor import ExecutorManager

    executor = ExecutorManager(workspace_dir=tmp_path)
    executor._http_client = MagicMock(spec=httpx.AsyncClient)

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "ok", "sessionId": "test-id"}
    executor._http_client.post = AsyncMock(return_value=mock_response)

    result = await executor.delete_session("test-id")

    assert result["status"] == "ok"
    executor._http_client.post.assert_called_once_with(
        "/v1/sessions/delete", json={"sessionId": "test-id"}
    )


@pytest.mark.asyncio
async def test_show_sessions_rename_flow(tmp_path):
    """Verify _show_sessions routes rename signal to rename workflow."""
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app._executor_ready = True

    sessions_data = {
        "sessions": [{"id": "s1", "name": "S1"}],
        "currentSessionId": "s1",
    }
    app.executor.list_sessions = AsyncMock(return_value=sessions_data)
    app.push_screen = MagicMock()
    app.run_worker = MagicMock()

    await app._show_sessions()
    callback = app.push_screen.call_args[0][1]

    # Simulate rename signal
    await callback("rename:s1")

    # Verify rename workflow helper was called via run_worker
    # It should be the first call (or only call) to run_worker after the callback
    found_rename = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "rename_session_workflow" in str(coro):
            found_rename = True
            # Coroutine created by AsyncMock or similar must be closed/awaited
            if hasattr(coro, "close"):
                coro.close()
            break
    assert found_rename


@pytest.mark.asyncio
async def test_show_sessions_delete_flow(tmp_path):
    """Verify _show_sessions routes delete signal to delete workflow."""
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app._executor_ready = True

    sessions_data = {
        "sessions": [{"id": "s1", "name": "S1"}],
        "currentSessionId": "s1",
    }
    app.executor.list_sessions = AsyncMock(return_value=sessions_data)
    app.push_screen = MagicMock()
    app.run_worker = MagicMock()

    await app._show_sessions()
    callback = app.push_screen.call_args[0][1]

    # Simulate delete signal
    await callback("delete:s1")

    # Verify delete workflow helper was called via run_worker
    found_delete = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "delete_session_workflow" in str(coro):
            found_delete = True
            if hasattr(coro, "close"):
                coro.close()
            break
    assert found_delete


@pytest.mark.asyncio
async def test_show_sessions_new_flow(tmp_path):
    """Verify _show_sessions routes new signal to create-session workflow."""
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app._executor_ready = True

    sessions_data = {
        "sessions": [{"id": "s1", "name": "S1"}],
        "currentSessionId": "s1",
    }
    app.executor.list_sessions = AsyncMock(return_value=sessions_data)
    app.push_screen = MagicMock()
    app.run_worker = MagicMock()

    await app._show_sessions()
    callback = app.push_screen.call_args[0][1]

    # Simulate new-session signal
    await callback("new")

    # Verify create-session workflow helper was called via run_worker
    found_new = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "create_session_from_menu" in str(coro):
            found_new = True
            if hasattr(coro, "close"):
                coro.close()
            break
    assert found_new


# Verified fix
