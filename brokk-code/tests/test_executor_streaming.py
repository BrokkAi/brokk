from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from brokk_code.executor import ExecutorManager


@pytest.mark.asyncio
async def test_stream_events_polling_logic():
    """
    Verify that stream_events:
    1. Correctstly advances after_seq.
    2. Yields events.
    3. Handles the polling loop and termination state.
    """
    executor = ExecutorManager()
    executor.base_url = "http://127.0.0.1:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    job_id = "test-job"

    # Mock responses:
    # 1. Status check -> RUNNING
    # 2. Events check -> returns 2 events, nextAfter 10
    # 3. Status check -> COMPLETED
    # 4. Events check -> returns 0 events, nextAfter 10 (loop terminates)

    status_running = MagicMock(spec=httpx.Response)
    status_running.status_code = 200
    status_running.json.return_value = {"state": "RUNNING"}

    status_completed = MagicMock(spec=httpx.Response)
    status_completed.status_code = 200
    status_completed.json.return_value = {"state": "COMPLETED"}

    events_payload = MagicMock(spec=httpx.Response)
    events_payload.status_code = 200
    events_payload.json.return_value = {
        "events": [
            {"seq": 9, "type": "LLM_TOKEN", "data": {"token": "a"}},
            {"seq": 10, "type": "LLM_TOKEN", "data": {"token": "b"}},
        ],
        "nextAfter": 10,
    }

    empty_events = MagicMock(spec=httpx.Response)
    empty_events.status_code = 200
    empty_events.json.return_value = {"events": [], "nextAfter": 10}

    mock_client.get.side_effect = [
        status_running,  # Loop 1 Status
        events_payload,  # Loop 1 Events
        status_completed,  # Loop 2 Status
        empty_events,  # Loop 2 Events
    ]

    # Use a small sleep to speed up test
    with (
        patch("asyncio.sleep", AsyncMock()),
        patch("asyncio.get_event_loop") as mock_loop_factory,
    ):
        mock_loop = MagicMock()
        # Advance time by 3.0s on each call to trigger status_interval (2.0s) checks.
        # stream_events calls time() at the start of each while loop iteration.
        # We provide a very long sequence to avoid StopAsyncIteration/RuntimeError
        # if the loop runs many times.
        mock_loop.time.side_effect = [float(i * 3.0) for i in range(1000)]
        mock_loop_factory.return_value = mock_loop
        collected_events = []
        async for event in executor.stream_events(job_id):
            collected_events.append(event)

    assert len(collected_events) == 2
    assert collected_events[0]["seq"] == 9
    assert collected_events[1]["seq"] == 10

    # Verify calls
    # Call 1: Status
    # Call 2: Events with after=-1
    # Call 3: Status
    # Call 4: Events with after=10
    mock_client.get.assert_any_call(f"/v1/jobs/{job_id}/events?after=-1&limit=100")
    mock_client.get.assert_any_call(f"/v1/jobs/{job_id}/events?after=10&limit=100")


@pytest.mark.asyncio
async def test_stream_events_adaptive_backoff():
    """Verify that current_sleep increases when no events are found."""
    executor = ExecutorManager()
    executor.base_url = "http://127.0.0.1:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    # Mock responses for 3 iterations of "no events"
    status_running = MagicMock(spec=httpx.Response)
    status_running.status_code = 200
    status_running.json.return_value = {"state": "RUNNING"}

    status_completed = MagicMock(spec=httpx.Response)
    status_completed.status_code = 200
    status_completed.json.return_value = {"state": "COMPLETED"}

    empty_events = MagicMock(spec=httpx.Response)
    empty_events.status_code = 200
    empty_events.json.return_value = {"events": [], "nextAfter": -1}

    # Sequence:
    # 1. Start: last_status_check is -inf -> triggers status check
    # 2. Iter 1: Get status (RUNNING), Get events (Empty) -> last_status_check set to 0.0,
    #    sleep 0.05, last_status_check reset to 0.0
    # 3. Iter 2: now (3.0) - last_status_check (0.0) > 2.0 -> triggers status check
    # 4. Iter 2: Get status (RUNNING), Get events (Empty) -> sleep 0.1,
    #    last_status_check reset to 0.0
    # 5. Iter 3: now (6.0) - last_status_check (0.0) > 2.0 -> triggers status check
    # 6. Iter 3: Get status (COMPLETED), Get events (Empty) -> Exit
    mock_client.get.side_effect = [
        status_running,
        empty_events,  # Iter 1: sleep 0.05
        status_running,
        empty_events,  # Iter 2: sleep 0.1
        status_completed,
        empty_events,  # Iter 3: exit
    ]

    with (
        patch("asyncio.sleep", AsyncMock()) as mock_sleep,
        patch("asyncio.get_event_loop") as mock_loop_factory,
    ):
        mock_loop = MagicMock()
        # Advance time to ensure status checks trigger and loop can exit.
        # stream_events calls time() at the start of each while loop iteration.
        mock_loop.time.side_effect = [float(i * 3.0) for i in range(1000)]
        mock_loop_factory.return_value = mock_loop

        async for _ in executor.stream_events("job"):
            pass

        sleep_calls = [call.args[0] for call in mock_sleep.call_args_list]
        assert len(sleep_calls) >= 2
        assert sleep_calls[0] == 0.05
        assert sleep_calls[1] == 0.1
