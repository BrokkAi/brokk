from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


class FakeStatusLine:
    def __init__(self):
        self.last_kwargs = {}

    def update_status(self, **kwargs):
        self.last_kwargs = kwargs


class FakeLog:
    def query(self, selector):
        return self

    def remove(self):
        return None


class FakeChat:
    def __init__(self):
        self._message_history = []
        self.status_line = FakeStatusLine()
        self.log = FakeLog()
        self.last_session_cost = None

    def add_system_message(self, msg, level="INFO"):
        pass

    def add_system_message_markup(self, msg, level="INFO"):
        pass

    def set_job_running(self, running: bool):
        pass

    def add_user_message(self, text):
        pass

    def add_markdown(self, text):
        pass

    def add_tool_result(self, text):
        pass

    def append_message(self, author, text):
        pass

    def set_token_bar_visible(self, visible: bool):
        pass

    def set_response_pending(self):
        pass

    def set_response_finished(self):
        pass

    def set_session_loading(self, loading: bool, message: str = None):
        pass

    def set_token_usage(self, used, max_tokens, fragments, session_cost=None):
        self.last_session_cost = session_cost

    def query_one(self, selector, *args, **kwargs):
        if selector in ("#chat-log", "log"):
            return self.log
        if selector in ("#status-line", "StatusLine"):
            return self.status_line
        raise Exception(f"Unexpected selector: {selector}")


@pytest.fixture
def mock_executor():
    executor = MagicMock()
    executor.workspace_dir = "/tmp/fake-workspace"
    executor.start = AsyncMock()
    executor.stop = AsyncMock()
    executor.check_alive.return_value = True
    return executor


def test_brokk_app_cost_tracking_basic(mock_executor):
    """Verify that COST notifications update job and session totals and are NOT logged to chat."""
    app = BrokkApp(executor=mock_executor)
    # Mock chat to verify suppression
    mock_chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    # Simulate a COST event
    event = {
        "type": "NOTIFICATION",
        "data": {"level": "COST", "message": "Cost: $0.10", "cost": 0.10},
    }

    app._handle_event(event)

    assert app.current_job_cost == 0.10
    assert app.session_total_cost == 0.10
    # Verify chat was NOT notified
    mock_chat.add_system_message.assert_not_called()

    # Simulate a CONFIRM event (should also be suppressed)
    confirm_event = {
        "type": "NOTIFICATION",
        "data": {"level": "CONFIRM", "message": "Confirm something..."},
    }
    app._handle_event(confirm_event)
    mock_chat.add_system_message.assert_not_called()

    # Simulate an INFO event
    info_event = {
        "type": "NOTIFICATION",
        "data": {"level": "INFO", "message": "Working..."},
    }
    app._handle_event(info_event)
    # Verify non-COST notifications still go to chat
    mock_chat.add_system_message.assert_called_with("Working...", level="INFO")

    # Simulate another COST event in the same job
    event2 = {
        "type": "NOTIFICATION",
        "data": {"level": "COST", "message": "Cost: $0.05", "cost": 0.05},
    }
    app._handle_event(event2)

    # These assertions would fail with 0.15000000000000002 if handled as raw floats
    assert app.current_job_cost == 0.15
    assert app.session_total_cost == 0.15


@pytest.mark.asyncio
async def test_brokk_app_cost_reset_between_jobs(mock_executor):
    """Verify that current_job_cost is reset at the start of _run_job."""
    app = BrokkApp(executor=mock_executor)
    app.current_job_cost = 1.0
    app.session_total_cost = 1.0

    # Mock submission and streaming to return immediately
    mock_executor.submit_job = AsyncMock(return_value="job-123")
    mock_executor.stream_events = MagicMock()

    async def empty_gen(*args, **kwargs):
        if False:
            yield {}

    mock_executor.stream_events.return_value = empty_gen()

    # Start a new job
    await app._run_job("hello")

    # current_job_cost should be reset to 0.0, but session cost remains
    assert app.current_job_cost == 0.0
    assert app.session_total_cost == 1.0


def test_brokk_app_cost_malformed_events(mock_executor):
    """Verify robustness against missing or non-numeric cost."""
    app = BrokkApp(executor=mock_executor)

    # Missing cost field
    app._handle_event(
        {"type": "NOTIFICATION", "data": {"level": "COST", "message": "no cost here"}}
    )
    assert app.current_job_cost == 0.0

    # Non-numeric cost
    app._handle_event(
        {"type": "NOTIFICATION", "data": {"level": "COST", "message": "bad cost", "cost": "free"}}
    )
    assert app.current_job_cost == 0.0


@pytest.mark.asyncio
async def test_brokk_app_session_cost_seeded_from_context(mock_executor):
    """Verify that session_total_cost is seeded from context and increments correctly."""
    mock_executor.get_context = AsyncMock(
        return_value={
            "branch": "main",
            "totalCost": 1.234,
        }
    )
    app = BrokkApp(executor=mock_executor)

    # Manually trigger refresh
    app._executor_ready = True
    await app._refresh_context_panel()

    assert app.session_total_cost == pytest.approx(1.234, rel=1e-6)
    assert app.current_job_cost == 0.0

    # Simulate a COST event incrementing from the seed
    event = {
        "type": "NOTIFICATION",
        "data": {"level": "COST", "message": "Cost: $0.10", "cost": 0.10},
    }
    app._handle_event(event)

    assert app.current_job_cost == 0.10
    assert app.session_total_cost == pytest.approx(1.334, rel=1e-6)


@pytest.mark.asyncio
async def test_brokk_app_session_cost_ignores_missing_or_bad_total_cost(mock_executor):
    """Verify that missing or non-numeric totalCost in context doesn't reset or crash the app."""
    app = BrokkApp(executor=mock_executor)
    app.session_total_cost = 0.5

    # Case 1: Missing totalCost
    mock_executor.get_context = AsyncMock(return_value={"branch": "main"})
    app._executor_ready = True
    await app._refresh_context_panel()
    assert app.session_total_cost == 0.5

    # Case 2: Malformed totalCost
    mock_executor.get_context = AsyncMock(return_value={"branch": "main", "totalCost": "expensive"})
    await app._refresh_context_panel()
    assert app.session_total_cost == 0.5


@pytest.mark.asyncio
async def test_brokk_app_session_cost_resets_on_session_switch_to_lower_total_cost(mock_executor):
    """
    Verify that session_total_cost correctly updates to a lower value when switching sessions,
    preventing the bug where it would stay stuck at the previous session's higher cost.
    """
    mock_executor.session_id = "sess-high"

    async def context_side_effect():
        if mock_executor.session_id == "sess-high":
            return {"branch": "main", "totalCost": 5.0}
        elif mock_executor.session_id == "sess-low":
            return {"branch": "main", "totalCost": 1.0}
        return {"branch": "main"}

    mock_executor.get_context = AsyncMock(side_effect=context_side_effect)

    async def switch_side_effect(session_id: str) -> dict:
        mock_executor.session_id = session_id
        return {"id": session_id}

    mock_executor.switch_session = AsyncMock(side_effect=switch_side_effect)
    mock_executor.get_conversation = AsyncMock(return_value={"entries": []})

    app = BrokkApp(executor=mock_executor)
    fake_chat = FakeChat()
    app._maybe_chat = MagicMock(return_value=fake_chat)
    app._executor_ready = True

    # 1. Seed with high cost
    await app._refresh_context_panel()
    assert app.session_total_cost == pytest.approx(5.0)
    assert app.session_total_cost_id == "sess-high"
    assert fake_chat.last_session_cost == pytest.approx(5.0)
    assert fake_chat.status_line.last_kwargs["session_cost"] == pytest.approx(5.0)

    # 2. Switch to session with lower cost
    await app._switch_to_session("sess-low")

    # 3. Verify reset and update
    assert app.session_total_cost == pytest.approx(1.0)
    assert app.session_total_cost_id == "sess-low"
    assert fake_chat.last_session_cost == pytest.approx(1.0)
    assert fake_chat.status_line.last_kwargs["session_cost"] == pytest.approx(1.0)
    assert app.current_job_cost == 0.0


@pytest.mark.asyncio
async def test_brokk_app_session_cost_preserved_on_switch_failure(mock_executor):
    """Verify that cost state is restored if switch_session raises an exception."""
    mock_executor.session_id = "sess-original"
    mock_executor.switch_session = AsyncMock(side_effect=Exception("Network error"))

    app = BrokkApp(executor=mock_executor)
    app.session_total_cost = 5.0
    app.current_job_cost = 0.5
    app._executor_ready = True

    fake_chat = FakeChat()
    app._maybe_chat = MagicMock(return_value=fake_chat)

    # Attempt switch that fails
    await app._switch_to_session("sess-other")

    # Assert costs are preserved/restored
    assert app.session_total_cost == 5.0
    assert app.current_job_cost == 0.5
