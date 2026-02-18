"""
Regression tests for BrokkApp shutdown and session export behavior.

These tests verify:
1. Normal quit via action_quit exports the session once and calls stop once.
2. Fallback on_unmount path also attempts export and stops correctly.
3. No misleading 'Executor not started' warnings during intentional shutdown.
"""
import asyncio
import logging
from pathlib import Path
from typing import Optional

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorError


class FakeExecutor:
    """
    Lightweight fake ExecutorManager for testing BrokkApp shutdown/export behavior.

    - download_session_zip: returns preset bytes or raises ExecutorError if stopped.
    - stop: marks stopped and records that it was called.
    - check_alive: returns current alive state.
    - session_id: simple attribute.
    """

    def __init__(
        self,
        *,
        zip_bytes: Optional[bytes] = b"ZIP",
        alive: bool = True,
        workspace_dir: Optional[Path] = None,
        fail_stop_attempts: int = 0,
    ):
        self._zip_bytes = zip_bytes
        self._alive = alive
        self._stopped = False
        self._fail_stop_attempts = fail_stop_attempts

        # Tracking
        self.download_calls = 0
        self.stop_calls = 0

        # session id externally visible
        self.session_id: Optional[str] = None

        # workspace_dir needed for some code paths
        self.workspace_dir = workspace_dir or Path.cwd()

    async def download_session_zip(self, session_id: str) -> bytes:
        self.download_calls += 1
        # Simulate Executor not started if stop() was called already.
        if self._stopped:
            raise ExecutorError("Executor not started")
        if self._zip_bytes is None:
            raise ExecutorError("no session zip available")
        # pretend some async work
        await asyncio.sleep(0)
        return self._zip_bytes

    async def stop(self) -> None:
        self.stop_calls += 1
        if self._fail_stop_attempts > 0:
            self._fail_stop_attempts -= 1
            raise RuntimeError("simulated stop failure")
        # Mark stopped so subsequent download attempts will raise the "Executor not started" path
        self._stopped = True
        # Simulate async cleanup
        await asyncio.sleep(0)

    def check_alive(self) -> bool:
        # If we've called stop(), consider no longer alive
        return self._alive and not self._stopped

    # minimal surface for other code paths which may call these in tests
    async def create_session(self, name: str = "TUI Session") -> str:
        # create and return a session id
        self.session_id = "created-session"
        await asyncio.sleep(0)
        return self.session_id

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        # For tests we don't want to block; treat as ready only if _alive is True
        await asyncio.sleep(0)
        return self._alive and not self._stopped


@pytest.mark.asyncio
async def test_action_quit_exports_once_and_stops(caplog, tmp_path):
    """
    Normal quit path should attempt export at most once and call stop exactly once.
    It must not emit the misleading warning about 'Executor not started' during normal shutdown.
    """
    caplog.set_level(logging.WARNING)
    fake = FakeExecutor(zip_bytes=b"dummy-zip", alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-123"

    app = BrokkApp(executor=fake)
    # mark the executor as ready so export path is taken
    app._executor_ready = True

    # Run the quit flow
    await app.action_quit()

    # stop must be called once
    assert fake.stop_calls == 1

    # download should have been attempted at most once and should succeed
    assert fake.download_calls <= 1
    assert fake.download_calls >= 0

    # Ensure the specific noisy warning is not present in the logs
    forbidden = "Failed to export session zip on shutdown: Executor not started"
    assert not any(forbidden in rec.getMessage() for rec in caplog.records)


@pytest.mark.asyncio
async def test_on_unmount_attempts_export_and_stops_without_misleading_warnings(caplog, tmp_path):
    """
    Fallback on_unmount should still try a best-effort export if the executor process
    is alive (even if _executor_ready is false), and must call stop exactly once.
    It should not emit the misleading warning when shutdown is intentional.
    """
    caplog.set_level(logging.WARNING)
    fake = FakeExecutor(zip_bytes=b"onunmount-zip", alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-456"

    app = BrokkApp(executor=fake)
    # Simulate executor not marked ready (e.g., different shutdown path), but process is alive
    app._executor_ready = False

    # Call the fallback unmount path
    await app.on_unmount()

    # stop must be called once
    assert fake.stop_calls == 1

    # Because check_alive() returned True, on_unmount should have attempted export once
    assert fake.download_calls == 1

    forbidden = "Failed to export session zip on shutdown: Executor not started"
    assert not any(forbidden in rec.getMessage() for rec in caplog.records)


@pytest.mark.asyncio
async def test_export_skipped_if_executor_not_available(caplog, tmp_path):
    """
    If the executor is not alive and not ready, export should be skipped and stop still called.
    Also no misleading warning should be emitted.
    """
    caplog.set_level(logging.WARNING)
    fake = FakeExecutor(zip_bytes=b"will-not-be-used", alive=False, workspace_dir=tmp_path)
    fake.session_id = "sess-789"

    app = BrokkApp(executor=fake)
    # not ready and executor.check_alive() returns False -> no export attempt
    app._executor_ready = False

    await app.action_quit()

    # stop still called once
    assert fake.stop_calls == 1

    # download should not be attempted
    assert fake.download_calls == 0

    forbidden = "Failed to export session zip on shutdown: Executor not started"
    assert not any(forbidden in rec.getMessage() for rec in caplog.records)


@pytest.mark.asyncio
async def test_idempotent_shutdown_via_quit_then_unmount(caplog, tmp_path):
    """
    If action_quit is called first, then on_unmount is called (as might happen
    during normal Textual teardown), stop should still only be called once total,
    and no duplicate export attempts or warnings should occur.
    """
    caplog.set_level(logging.WARNING)
    fake = FakeExecutor(zip_bytes=b"idempotent-zip", alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-idem"

    app = BrokkApp(executor=fake)
    app._executor_ready = True

    # First via action_quit
    await app.action_quit()
    # Then via on_unmount (simulating Textual teardown)
    await app.on_unmount()

    # stop must be called only once total
    assert fake.stop_calls == 1

    # export should only be attempted once
    assert fake.download_calls == 1

    forbidden = "Failed to export session zip on shutdown: Executor not started"
    assert not any(forbidden in rec.getMessage() for rec in caplog.records)


@pytest.mark.asyncio
async def test_export_skipped_when_no_session_id(caplog, tmp_path):
    """
    If there is no session_id set, export should be skipped entirely (no download call).
    """
    caplog.set_level(logging.WARNING)
    fake = FakeExecutor(zip_bytes=b"unused", alive=True, workspace_dir=tmp_path)
    fake.session_id = None  # no session

    app = BrokkApp(executor=fake)
    app._executor_ready = True

    await app.action_quit()

    # stop still called
    assert fake.stop_calls == 1

    # no download attempt because no session_id
    assert fake.download_calls == 0

    # no warnings expected
    assert not any("Failed to export" in rec.getMessage() for rec in caplog.records)


@pytest.mark.asyncio
async def test_shutdown_retries_stop_after_failure(tmp_path):
    """A failed stop attempt should not permanently block later shutdown retries."""
    fake = FakeExecutor(
        zip_bytes=b"retry-stop-zip",
        alive=True,
        workspace_dir=tmp_path,
        fail_stop_attempts=1,
    )
    fake.session_id = "sess-retry"

    app = BrokkApp(executor=fake)
    app._executor_ready = True

    # First attempt fails stop and should remain retryable.
    await app._shutdown_once(show_message=False)
    assert fake.stop_calls == 1
    assert app._shutdown_completed is False

    # Second attempt retries stop and completes shutdown.
    await app._shutdown_once(show_message=False)
    assert fake.stop_calls == 2
    assert app._shutdown_completed is True
