import asyncio
import types
from pathlib import Path
from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.widgets.status_line import StatusLine


def _rendered_text(status: StatusLine) -> str:
    renderable = status.renderable
    plain = getattr(renderable, "plain", None)
    if isinstance(plain, str):
        return plain
    return str(renderable)


def test_statusline_widget_present_when_mounted_and_queryable_by_id() -> None:
    exec_mock = MagicMock()
    exec_mock.workspace_dir = Path("/tmp/brokk-workspace")

    app = BrokkApp(executor=exec_mock)

    async def on_mount_override(self: BrokkApp) -> None:
        self._update_status_line()

    app.on_mount = types.MethodType(on_mount_override, app)

    async def run() -> None:
        async with app.run_test() as pilot:
            status = pilot.app.query_one("#status-line", StatusLine)
            assert status.id == "status-line"

    asyncio.run(run())


def test_toggle_statusline_action_toggles_hidden_class() -> None:
    exec_mock = MagicMock()
    exec_mock.workspace_dir = Path("/tmp/brokk-workspace")

    app = BrokkApp(executor=exec_mock)

    async def on_mount_override(self: BrokkApp) -> None:
        self._update_status_line()

    app.on_mount = types.MethodType(on_mount_override, app)

    async def run() -> None:
        async with app.run_test() as pilot:
            status = pilot.app.query_one("#status-line", StatusLine)

            assert status.has_class("hidden")

            pilot.app.action_toggle_statusline()
            assert not status.has_class("hidden")

            pilot.app.action_toggle_statusline()
            assert status.has_class("hidden")

    asyncio.run(run())


def test_statusline_live_updates_reflect_mode_model_reasoning_and_dir() -> None:
    exec_mock = MagicMock()
    exec_mock.workspace_dir = Path("/tmp/brokk-workspace/project-a")

    app = BrokkApp(executor=exec_mock)

    async def on_mount_override(self: BrokkApp) -> None:
        self._update_status_line()

    app.on_mount = types.MethodType(on_mount_override, app)

    async def run() -> None:
        async with app.run_test() as pilot:
            status = pilot.app.query_one("#status-line", StatusLine)

            pilot.app._set_mode("ASK", announce=False)
            text = _rendered_text(status)
            assert "Mode:" in text
            assert "ASK" in text

            pilot.app.current_model = "example-model-42"
            pilot.app._update_status_line()
            text = _rendered_text(status)
            assert "Model:" in text
            assert "example-model-42" in text

            pilot.app.reasoning_level = "high"
            pilot.app._update_status_line()
            text = _rendered_text(status)
            assert "Reasoning:" in text
            assert "high" in text

            text = _rendered_text(status)
            assert "Dir:" in text
            assert "brokk-workspace" in text

    asyncio.run(run())
