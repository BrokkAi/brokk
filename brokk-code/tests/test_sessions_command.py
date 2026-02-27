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
    callback("s2")

    # Verify switch worker was triggered
    app.run_worker.assert_called_once()


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

    # Check approximate alignment (title width 40 + 2 spaces)
    # The date should start at index 42
    assert label1.find("2025-01-01") == 42
    # The entries should start after the date (42 + 16 + 2 = 60)
    assert label1.find("3 entries") == 60

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

    # The title width is 40.
    # "Implement a new login system with oauth " is 39 chars.
    expected_substring = long_title[:40]
    assert expected_substring in label
    # Ensure date starts at index 42 (title 40 + 2 spaces)
    assert label.find("2025-01-01") == 42


@pytest.mark.asyncio
async def test_executor_delete_session(tmp_path):
    """Verify ExecutorManager.delete_session sends the correct request."""
    from brokk_code.executor import ExecutorManager
    import httpx
    import json

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
