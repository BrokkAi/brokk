import asyncio
import time
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


@pytest.mark.asyncio
async def test_switch_to_session_concurrency_blocking(tmp_path):
    """Verify that concurrent switch attempts are blocked by session_switch_in_progress."""
    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app.executor.workspace_dir = tmp_path
    app._executor_ready = True

    chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=chat)

    # Mock switch_session to simulate delay
    async def slow_switch(*args, **kwargs):
        await asyncio.sleep(0.1)
        return {}

    app.executor.switch_session = AsyncMock(side_effect=slow_switch)
    app.executor.get_conversation = AsyncMock(return_value={"entries": []})

    # Trigger first switch
    task = asyncio.create_task(app._switch_to_session("s1"))
    # Wait a tiny bit for the first task to set the flag
    await asyncio.sleep(0.01)
    assert app.session_switch_in_progress is True

    # Trigger second switch while first is in progress
    await app._switch_to_session("s2")

    # Second switch should have bailed and logged a warning
    any_warning = any(
        "already in progress" in str(call.args[0])
        for call in chat.add_system_message.call_args_list
        if call.kwargs.get("level") == "WARNING"
    )
    assert any_warning is True

    # Ensure first switch finishes
    await task
    assert app.session_switch_in_progress is False
    # Only s1 should have been called on executor
    app.executor.switch_session.assert_called_once_with("s1")


@pytest.mark.asyncio
async def test_prompt_submission_gates_and_queues_during_switch(tmp_path):
    """Verify that submitting a prompt during a session switch is queued and run after."""
    from brokk_code.widgets.chat_panel import ChatPanel

    app = BrokkApp(workspace_dir=tmp_path)
    app.executor = MagicMock()
    app.executor.workspace_dir = tmp_path
    app._executor_ready = True

    chat = MagicMock(spec=ChatPanel)
    chat._message_history = []

    # Mock the #chat-log query path to avoid TypeError: 'MagicMock' object can't be awaited
    # when app calls log.query("*").remove()
    log_mock = MagicMock()
    log_mock.query.return_value.remove.return_value = None  # Non-awaitable for test
    chat.query_one.side_effect = lambda q: log_mock if q == "#chat-log" else MagicMock()

    app._maybe_chat = MagicMock(return_value=chat)

    # Simulate a slow switch
    switch_event = asyncio.Event()

    async def slow_switch(*args, **kwargs):
        await switch_event.wait()
        return {}

    app.executor.switch_session = AsyncMock(side_effect=slow_switch)
    app.executor.get_conversation = AsyncMock(return_value={"entries": []})
    app.executor.submit_job = AsyncMock(return_value="job-queued")
    app.executor.stream_events = MagicMock()

    async def empty_gen(*args, **kwargs):
        if False:
            yield {}

    app.executor.stream_events.return_value = empty_gen()

    # Start the switch
    switch_task = asyncio.create_task(app._switch_to_session("s-target"))
    await asyncio.sleep(0.01)
    assert app.session_switch_in_progress is True

    # Submit a prompt while switching
    msg = MagicMock()
    msg.text = "Queued prompt during switch"
    app.on_chat_panel_submitted(msg)

    assert app._pending_switch_prompt == "Queued prompt during switch"
    # Verify no immediate job submission
    app.executor.submit_job.assert_not_called()

    # Verify feedback was given
    any_queued_msg = any(
        "Queuing prompt" in str(call.args[0]) for call in chat.add_system_message.call_args_list
    )
    assert any_queued_msg is True

    # complete the switch
    switch_event.set()
    await switch_task

    # Verify switch finished
    app.executor.switch_session.assert_called_once_with("s-target")

    # The switch_task completion triggers run_worker(self._run_job(queued)).
    # In tests, we need to ensure the event loop processes this.
    start_wait = time.time()
    while app.executor.submit_job.call_count == 0 and time.time() - start_wait < 2.0:
        await asyncio.sleep(0.01)

    app.executor.submit_job.assert_called_once()
    assert app.executor.submit_job.call_args[0][0] == "Queued prompt during switch"
    assert app._pending_switch_prompt is None


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
    callback("rename:s1")

    # Verify rename workflow helper was called via run_worker
    # It should be the first call (or only call) to run_worker after the callback
    found_rename = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "rename_session_workflow" in str(coro):
            found_rename = True
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
    callback("delete:s1")

    # Verify delete workflow helper was called via run_worker
    found_delete = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "delete_session_workflow" in str(coro):
            found_delete = True
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
    callback("new")

    # Verify create-session workflow helper was called via run_worker
    found_new = False
    for call in app.run_worker.call_args_list:
        coro = call[0][0]
        if "create_session_from_menu" in str(coro):
            found_new = True
            break
    assert found_new
