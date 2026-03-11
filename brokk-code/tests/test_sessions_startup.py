"""Tests for pick_session startup wiring in BrokkApp.

These tests verify that:
1. BrokkApp accepts pick_session=True without TypeError
2. When pick_session=True, _start_executor schedules _show_sessions exactly once
3. When pick_session=False (default), _start_executor does NOT schedule _show_sessions
"""

from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp


class TestPickSessionConstructor:
    """Tests for BrokkApp constructor accepting pick_session parameter."""

    def test_constructor_accepts_pick_session_true(self, tmp_path):
        """Verify BrokkApp(pick_session=True) constructs without TypeError."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=True)
        assert app.pick_session is True

    def test_constructor_accepts_pick_session_false(self, tmp_path):
        """Verify BrokkApp(pick_session=False) constructs without TypeError."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=False)
        assert app.pick_session is False

    def test_constructor_defaults_pick_session_false(self, tmp_path):
        """Verify pick_session defaults to False when not specified."""
        app = BrokkApp(workspace_dir=tmp_path)
        assert app.pick_session is False


class TestPickSessionStartupFlow:
    """Tests for _start_executor scheduling _show_sessions when pick_session=True."""

    @pytest.mark.asyncio
    async def test_pick_session_true_schedules_show_sessions(self, tmp_path):
        """When pick_session=True, _start_executor schedules _show_sessions exactly once."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=True)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = None

        async def create_session(name: str = "TUI Session") -> str:
            app.executor.session_id = "s-init"
            return "s-init"

        app.executor.start = AsyncMock()
        app.executor.create_session = AsyncMock(side_effect=create_session)
        app.executor.wait_ready = AsyncMock(return_value=True)
        app.executor.get_health_live = AsyncMock(return_value={})

        app._maybe_chat = MagicMock(return_value=None)

        scheduled: list[object] = []

        def run_worker_stub(coro):
            scheduled.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            await app._start_executor()

            assert app.pick_session is False, (
                "pick_session should be reset " + "to False after triggering"
            )

            show_sessions_scheduled = [
                coro
                for coro in scheduled
                if hasattr(coro, "cr_code") and coro.cr_code.co_name == "_show_sessions"
            ]
            assert len(show_sessions_scheduled) == 1, (
                "_show_sessions should " + "be scheduled exactly once"
            )
        finally:
            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass

    @pytest.mark.asyncio
    async def test_pick_session_false_does_not_schedule_show_sessions(self, tmp_path):
        """When pick_session=False, _start_executor does NOT schedule _show_sessions."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=False)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = "s1"

        app.executor.start = AsyncMock()
        app.executor.create_session = AsyncMock(return_value="s1")
        app.executor.wait_ready = AsyncMock(return_value=True)
        app.executor.get_health_live = AsyncMock(return_value={})

        app._maybe_chat = MagicMock(return_value=None)

        scheduled: list[object] = []

        def run_worker_stub(coro):
            scheduled.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            await app._start_executor()

            show_sessions_scheduled = [
                coro
                for coro in scheduled
                if hasattr(coro, "cr_code") and coro.cr_code.co_name == "_show_sessions"
            ]
            assert len(show_sessions_scheduled) == 0, "_show_sessions should NOT be scheduled"
        finally:
            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass

    @pytest.mark.asyncio
    async def test_pick_session_default_does_not_schedule_show_sessions(self, tmp_path):
        """Default pick_session=False should NOT schedule _show_sessions."""
        app = BrokkApp(workspace_dir=tmp_path)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = "s-default"

        app.executor.start = AsyncMock()
        app.executor.create_session = AsyncMock(return_value="s-default")
        app.executor.wait_ready = AsyncMock(return_value=True)
        app.executor.get_health_live = AsyncMock(return_value={})

        app._maybe_chat = MagicMock(return_value=None)

        scheduled: list[object] = []

        def run_worker_stub(coro):
            scheduled.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            await app._start_executor()

            show_sessions_scheduled = [
                coro
                for coro in scheduled
                if hasattr(coro, "cr_code") and coro.cr_code.co_name == "_show_sessions"
            ]
            assert len(show_sessions_scheduled) == 0, (
                "_show_sessions should NOT be scheduled by default"
            )
        finally:
            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass

    @pytest.mark.asyncio
    async def test_show_sessions_callback_switches_session_not_creates(self, tmp_path):
        """When callback receives a session ID, switch_session is called, not create_session."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=True)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = "s-current"
        app._executor_ready = True

        sessions_data = {
            "sessions": [
                {"id": "s-current", "name": "Current Session", "aiResponses": 2},
                {"id": "s-other", "name": "Other Session", "aiResponses": 5},
            ],
            "currentSessionId": "s-current",
        }
        app.executor.list_sessions = AsyncMock(return_value=sessions_data)
        app.executor.switch_session = AsyncMock(return_value={})
        app.executor.create_session = AsyncMock(return_value="s-new")
        app.executor.get_conversation = AsyncMock(return_value={"entries": []})

        app._maybe_chat = MagicMock(return_value=None)
        app.push_screen = MagicMock()

        await app._show_sessions()

        app.executor.list_sessions.assert_called_once()
        app.push_screen.assert_called_once()

        callback = app.push_screen.call_args[0][1]

        scheduled_coros: list[object] = []

        def run_worker_stub(coro):
            scheduled_coros.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            callback("s-other")

            assert len(scheduled_coros) == 1
            coro = scheduled_coros[0]
            assert hasattr(coro, "cr_code")
            assert coro.cr_code.co_name == "_switch_to_session"

            app.executor.create_session.assert_not_called()
        finally:
            for c in scheduled_coros:
                try:
                    c.close()
                except Exception:
                    pass

    @pytest.mark.asyncio
    async def test_pick_session_not_scheduled_when_wait_ready_fails(self, tmp_path):
        """When wait_ready returns False, _show_sessions should NOT be scheduled."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=True)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = None

        async def create_session(name: str = "TUI Session") -> str:
            app.executor.session_id = "s-init"
            return "s-init"

        app.executor.start = AsyncMock()
        app.executor.create_session = AsyncMock(side_effect=create_session)
        app.executor.wait_ready = AsyncMock(return_value=False)
        app.executor.get_health_live = AsyncMock(return_value={})

        app._maybe_chat = MagicMock(return_value=None)

        scheduled: list[object] = []

        def run_worker_stub(coro):
            scheduled.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            await app._start_executor()

            show_sessions_scheduled = [
                coro
                for coro in scheduled
                if hasattr(coro, "cr_code") and coro.cr_code.co_name == "_show_sessions"
            ]
            assert len(show_sessions_scheduled) == 0, (
                "_show_sessions should NOT be scheduled when wait_ready returns False"
            )
        finally:
            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass

    @pytest.mark.asyncio
    async def test_pick_session_triggers_only_once(self, tmp_path):
        """Verify pick_session flag is consumed and won't trigger again on subsequent calls."""
        app = BrokkApp(workspace_dir=tmp_path, pick_session=True)
        app.executor = MagicMock()
        app.executor.workspace_dir = tmp_path
        app.executor.session_id = None

        async def create_session(name: str = "TUI Session") -> str:
            app.executor.session_id = "s-init"
            return "s-init"

        app.executor.start = AsyncMock()
        app.executor.create_session = AsyncMock(side_effect=create_session)
        app.executor.wait_ready = AsyncMock(return_value=True)
        app.executor.get_health_live = AsyncMock(return_value={})

        app._maybe_chat = MagicMock(return_value=None)

        scheduled: list[object] = []

        def run_worker_stub(coro):
            scheduled.append(coro)
            return MagicMock()

        app.run_worker = MagicMock(side_effect=run_worker_stub)

        try:
            await app._start_executor()

            assert app.pick_session is False

            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass
            scheduled.clear()

            app._executor_ready = False
            app._executor_started = False

            await app._start_executor()

            show_sessions_scheduled = [
                coro
                for coro in scheduled
                if hasattr(coro, "cr_code") and coro.cr_code.co_name == "_show_sessions"
            ]
            assert len(show_sessions_scheduled) == 0, (
                "_show_sessions should NOT be scheduled on second call"
            )
        finally:
            for coro in scheduled:
                try:
                    coro.close()
                except Exception:
                    pass
