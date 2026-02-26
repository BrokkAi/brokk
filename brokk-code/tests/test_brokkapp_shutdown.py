"""
Regression tests for BrokkApp shutdown behavior.

These tests verify:
1. Normal quit via action_quit calls stop once.
2. Fallback on_unmount also stops correctly.
3. Shutdown remains idempotent and retryable on stop failure.
"""

import asyncio
from pathlib import Path
from typing import Optional

import pytest

from brokk_code.app import BrokkApp


class FakeExecutor:
    """
    Lightweight fake ExecutorManager for testing BrokkApp shutdown behavior.

    - stop: marks stopped and records that it was called.
    - check_alive: returns current alive state.
    - session_id: simple attribute.
    """

    def __init__(
        self,
        *,
        alive: bool = True,
        workspace_dir: Optional[Path] = None,
        fail_stop_attempts: int = 0,
    ):
        self._alive = alive
        self._stopped = False
        self._fail_stop_attempts = fail_stop_attempts

        # Tracking
        self.stop_calls = 0

        # session id externally visible
        self.session_id: Optional[str] = None

        # workspace_dir needed for some code paths
        self.workspace_dir = workspace_dir or Path.cwd()

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
async def test_action_quit_stops_executor(caplog, tmp_path):
    """
    Normal quit path should call stop exactly once.
    """
    fake = FakeExecutor(alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-123"

    app = BrokkApp(executor=fake)
    app._executor_ready = True

    await app.action_quit()

    assert fake.stop_calls == 1


@pytest.mark.asyncio
async def test_on_unmount_stops_executor(caplog, tmp_path):
    """
    Fallback on_unmount should call stop exactly once.
    """
    fake = FakeExecutor(alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-456"

    app = BrokkApp(executor=fake)
    app._executor_ready = False

    await app.on_unmount()

    assert fake.stop_calls == 1


@pytest.mark.asyncio
async def test_idempotent_shutdown_via_quit_then_unmount(caplog, tmp_path):
    """
    If action_quit is called first, then on_unmount is called (as might happen
    during normal Textual teardown), stop should still only be called once total.
    """
    fake = FakeExecutor(alive=True, workspace_dir=tmp_path)
    fake.session_id = "sess-idem"

    app = BrokkApp(executor=fake)
    app._executor_ready = True

    # First via action_quit
    await app.action_quit()
    # Then via on_unmount (simulating Textual teardown)
    await app.on_unmount()

    # stop must be called only once total
    assert fake.stop_calls == 1


@pytest.mark.asyncio
async def test_shutdown_retries_stop_after_failure(tmp_path):
    """A failed stop attempt should not permanently block later shutdown retries."""
    fake = FakeExecutor(
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
