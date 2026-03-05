import pytest
from pathlib import Path
from unittest.mock import MagicMock, AsyncMock
from brokk_code.app import BrokkApp
from brokk_code.widgets.chat_panel import ChatPanel, ChatContainer


@pytest.mark.asyncio
async def test_worktree_output_isolation(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    async with app.run_test() as pilot:
        await pilot.pause()
        container = app.query_one("#chat-container", ChatContainer)
        # Force panels to be created within the mounted app context
        panel_a = container.get_panel(path_a)
        panel_b = container.get_panel(path_b)
        await pilot.pause()

        assert panel_a is not panel_b

        # Verify log isolation
        panel_a.add_system_message("Message A")
        panel_b.add_system_message("Message B")

        assert any("Message A" in m.get("content", "") for m in panel_a._message_history)
        assert not any("Message B" in m.get("content", "") for m in panel_a._message_history)
        assert any("Message B" in m.get("content", "") for m in panel_b._message_history)


@pytest.mark.asyncio
async def test_worktree_spinner_isolation(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    async with app.run_test() as pilot:
        await pilot.pause()
        container = app.query_one(ChatContainer)
        panel_a = container.get_panel(path_a)
        panel_b = container.get_panel(path_b)
        await pilot.pause()

        panel_a.set_job_running(True)
        assert panel_a._job_start_time is not None
        assert panel_b._job_start_time is None

        panel_a.set_job_running(False)
        assert panel_a._job_start_time is None


@pytest.mark.asyncio
async def test_selection_switches_visibility(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)
    async with app.run_test() as pilot:
        await pilot.pause()
        container = app.query_one(ChatContainer)
        panel_a = container.get_panel(path_a)
        panel_b = container.get_panel(path_b)
        await pilot.pause()

        container.switch_to(path_a)
        assert not panel_a.has_class("hidden")
        assert panel_b.has_class("hidden")

        container.switch_to(path_b)
        assert panel_a.has_class("hidden")
        assert not panel_b.has_class("hidden")
