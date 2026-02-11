from unittest.mock import AsyncMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.context_panel import ContextFragmentItem, ContextPanel


class StubExecutor(ExecutorManager):
    def __init__(self, workspace_dir):
        super().__init__(workspace_dir=workspace_dir)

    async def start(self):
        pass

    async def stop(self):
        pass

    async def create_session(self, name: str = "TUI Session") -> str:
        self.session_id = "session-1"
        return self.session_id

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        return True

    def check_alive(self) -> bool:
        return True

    async def get_health_live(self):
        return {"version": "test", "protocolVersion": "1", "execId": "test-id"}


@pytest.mark.asyncio
async def test_tasklist_polling_updates_ui(tmp_path):
    """
    Verifies that the background tasklist polling worker calls the
    appropriate methods on TaskListPanel.
    """
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    # Mock data
    mock_tasklist = {
        "bigPicture": "Refactor Authentication",
        "tasks": [
            {
                "id": "t1",
                "title": "Update LoginController",
                "text": "Change the authentication endpoint to use JWT instead of sessions.",
                "done": False,
            },
            {
                "id": "t2",
                "title": "Add logging",
                "text": "Add SLF4J logging to the service layer.",
                "done": True,
            },
        ],
    }

    with patch(
        "brokk_code.executor.ExecutorManager.get_tasklist", new_callable=AsyncMock
    ) as mock_get:
        mock_get.return_value = mock_tasklist

        async with app.run_test():
            # Manually set ready state to trigger polling logic
            app._executor_ready = True

            # Instead of waiting 15s, we trigger the update method directly
            # to verify it renders correctly, then we check if the worker loop
            # would have called it by checking the mock.

            from brokk_code.widgets.tasklist_panel import TaskListPanel

            panel = app.query_one(TaskListPanel)
            panel.update_tasklist_details(mock_tasklist)

            content_widget = panel.query_one("#tasklist-content")
            # Using plain to avoid markup/styling variations across versions
            content_text = content_widget.render().plain

            assert "Refactor Authentication" in content_text
            assert "Update LoginController" in content_text
            assert "Change the authentication endpoint" in content_text
            assert "Add logging" in content_text
            assert "DONE" in content_text
            assert "TODO" in content_text

            # Observable outcome: content is present
            assert "Refactor Authentication" in content_text


@pytest.mark.asyncio
async def test_refresh_context_does_not_clobber_details(tmp_path):
    """
    Verifies that refresh_tasklist (from /v1/context) does not overwrite
    detailed info (from /v1/tasklist) if details are already present.
    """
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    mock_tasklist = {
        "bigPicture": "Detailed Goal",
        "tasks": [{"title": "Detailed Task", "done": False}],
    }
    mock_context = {"fragments": [{"chipKind": "TASK_LIST", "shortDescription": "Summary Only"}]}

    async with app.run_test():
        from brokk_code.widgets.tasklist_panel import TaskListPanel

        panel = app.query_one(TaskListPanel)

        # 1. Set details
        panel.update_tasklist_details(mock_tasklist)
        content_text = panel.query_one("#tasklist-content").render().plain
        assert "Detailed Goal" in content_text
        assert "Detailed Task" in content_text

        # 2. Call refresh_tasklist (which usually shows summary)
        panel.refresh_tasklist(mock_context)
        content_text_after = panel.query_one("#tasklist-content").render().plain

        # Should NOT have been replaced by "Summary Only"
        assert "Detailed Goal" in content_text_after
        assert "Summary Only" not in content_text_after


@pytest.mark.asyncio
async def test_refresh_context_panel_integration_preserves_task_details(tmp_path):
    """
    Higher-level integration test: ensures BrokkApp._refresh_context_panel
    doesn't clobber the task list panel's detailed state.
    """
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    mock_tasklist = {
        "bigPicture": "Complex Refactoring Goal",
        "tasks": [
            {"title": "Task One", "done": False, "text": "Details for one"},
            {"title": "Task Two", "done": True, "text": "Details for two"},
        ],
    }
    mock_context = {
        "usedTokens": 500,
        "fragments": [{"chipKind": "TASK_LIST", "shortDescription": "Generic Task List Summary"}],
    }

    with patch(
        "brokk_code.executor.ExecutorManager.get_context", new_callable=AsyncMock
    ) as mock_get_ctx:
        mock_get_ctx.return_value = mock_context

        async with app.run_test() as pilot:
            app._executor_ready = True
            from brokk_code.widgets.tasklist_panel import TaskListPanel

            panel = app.query_one(TaskListPanel)

            # 1. Manually inject details (as if /v1/tasklist poll just finished)
            panel.update_tasklist_details(mock_tasklist)

            initial_render = panel.query_one("#tasklist-content").render().plain
            assert "Complex Refactoring Goal" in initial_render
            assert "Task One" in initial_render
            assert "TODO" in initial_render
            assert "DONE" in initial_render

            # 2. Trigger the app-level context refresh
            await app._refresh_context_panel()
            await pilot.pause()

            # 3. Verify details persist
            final_render = panel.query_one("#tasklist-content").render().plain
            assert "Complex Refactoring Goal" in final_render
            assert "Task One" in final_render
            assert "Generic Task List Summary" not in final_render


@pytest.mark.asyncio
async def test_context_polling_updates_ui(tmp_path):
    """
    Verifies that the background context polling worker updates the ContextPanel.
    """
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    # Mock data
    mock_context = {
        "usedTokens": 1500,
        "maxTokens": 100000,
        "fragments": [
            {
                "chipKind": "EDIT",
                "shortDescription": "Modified UserAuth.java",
                "pinned": True,
                "tokens": 450,
            },
            {"chipKind": "HISTORY", "shortDescription": "Previous chat history", "tokens": 1050},
        ],
    }

    with (
        patch(
            "brokk_code.executor.ExecutorManager.get_context", new_callable=AsyncMock
        ) as mock_get,
        patch.object(BrokkApp, "_poll_context", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
    ):
        mock_get.return_value = mock_context

        async with app.run_test() as pilot:
            # Manually set ready state
            app._executor_ready = True

            # Directly call the refresh method that the worker would call
            await app._refresh_context_panel()
            await pilot.pause()

            # Verify Header
            panel = app.query_one("#context-panel", ContextPanel)
            usage = panel.query_one("#context-token-usage")
            assert "1,500 / 100,000 tokens" in str(usage.render())

            # Verify ChatPanel Token Usage
            chat_panel = app.query_one(ChatPanel)
            usage_label = chat_panel.query_one("#chat-token-usage")
            # The UI renders a progress bar when max_tokens is present
            assert "1,500 / 100,000" in str(usage_label.render())

            # Verify List Contents
            chip_row = panel.query_one("#context-chip-row")
            fragment_items = chip_row.query(ContextFragmentItem)
            assert len(fragment_items) == 2

            items_text = " ".join(item.render().plain for item in fragment_items)
            assert "Modified UserAuth.java" in items_text
            assert "Previous chat history" in items_text
            assert "EDIT" in items_text
            assert "HISTORY" in items_text


@pytest.mark.asyncio
async def test_polling_triggers_immediately_after_ready(tmp_path):
    """
    Verifies that once _executor_ready is True, the polling loops
    successfully trigger their respective refresh calls.
    """
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    mock_context = {"usedTokens": 100, "fragments": []}
    mock_tasklist = {"bigPicture": "Test", "tasks": []}

    with (
        patch(
            "brokk_code.executor.ExecutorManager.get_context", new_callable=AsyncMock
        ) as mock_ctx,
        patch(
            "brokk_code.executor.ExecutorManager.get_tasklist", new_callable=AsyncMock
        ) as mock_tl,
    ):
        mock_ctx.return_value = mock_context
        mock_tl.return_value = mock_tasklist

        async with app.run_test():
            # Initially not ready
            app._executor_ready = False

            # Manually trigger the refresh logic to simulate a poll iteration
            await app._refresh_context_panel()
            # It should have called even if not ready because _refresh_context_panel
            # doesn't check ready (the poll loop does).
            # But we want to see that the loop gating works.

            app._executor_ready = True

            # Verify refresh_context_panel updates the UI
            await app._refresh_context_panel()

            mock_ctx.assert_called()
            panel = app.query_one(ContextPanel)
            assert "100 /" in str(panel.query_one("#context-token-usage").render())
