from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


@pytest.fixture
def mock_executor():
    executor = MagicMock()
    executor.workspace_dir = "/tmp/fake-workspace"
    executor.start = AsyncMock()
    executor.stop = AsyncMock()
    executor.check_alive.return_value = True
    return executor


def test_brokk_app_cost_tracking_basic(mock_executor):
    """Verify that COST notifications update job and session totals."""
    app = BrokkApp(executor=mock_executor)

    # Simulate a COST event
    event = {
        "type": "NOTIFICATION",
        "data": {"level": "COST", "message": "Cost: $0.10", "cost": 0.10},
    }

    app._handle_event(event)

    assert app.current_job_cost == 0.10
    assert app.session_total_cost == 0.10

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
