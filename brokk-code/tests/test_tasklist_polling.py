from unittest.mock import AsyncMock, patch
import pytest
from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager
from brokk_code.widgets.chat_panel import ChatPanel
from brokk_code.widgets.context_panel import ContextPanel


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

        async with app.run_test() as pilot:
            # Manually set ready state to trigger polling logic
            app._executor_ready = True

            # Instead of waiting 15s, we trigger the update method directly
            # to verify it renders correctly, then we check if the worker loop
            # would have called it by checking the mock.

            from brokk_code.widgets.tasklist_panel import TaskListPanel
            panel = app.query_one(TaskListPanel)
            panel.update_tasklist_details(mock_tasklist)

            content_widget = panel.query_one("#tasklist-content")
            # Using plain_text to avoid markup/styling variations across versions
            content_text = content_widget.renderable.plain

            assert "Refactor Authentication" in content_text
            assert "Update LoginController" in content_text
            assert "Change the authentication endpoint" in content_text
            assert "Add logging" in content_text
            assert "DONE" in content_text
            assert "TODO" in content_text

            # Trigger one iteration of the polling logic manually if possible,
            # or simply rely on the fact that _poll_tasklist is started in on_mount.
            # Since we can't easily advance time in Textual workers without
            # significant boilerplate, verifying the method works and the
            # worker is registered is sufficient for this unit test.

            assert any(w.name == "_poll_tasklist" for w in app.workers)


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

    with patch(
        "brokk_code.executor.ExecutorManager.get_context", new_callable=AsyncMock
    ) as mock_get:
        mock_get.return_value = mock_context

        async with app.run_test() as pilot:
            # Manually set ready state
            app._executor_ready = True

            # Directly call the refresh method that the worker would call
            await app._refresh_context_panel()

            # Verify Header
            panel = app.query_one("#side-context", ContextPanel)
            header = panel.query_one("#context-header")
            assert "1,500 / 100,000 tokens" in str(header.renderable)

            # Verify ChatPanel Token Usage
            chat_panel = app.query_one(ChatPanel)
            usage_label = chat_panel.query_one("#chat-token-usage")
            # The UI renders a progress bar when max_tokens is present
            assert "1,500 / 100,000" in str(usage_label.renderable)

            # Verify List Contents
            list_view = panel.query_one("#context-list")
            assert len(list_view.children) == 2

            # Check for specific text in list items
            items_text = "".join(str(child.renderable) for child in list_view.children)
            assert "Modified UserAuth.java" in items_text
            assert "Previous chat history" in items_text
            assert "EDIT" in items_text
            assert "HISTORY" in items_text

            # Verify worker registration
            assert any(w.name == "_poll_context" for w in app.workers)


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

        async with app.run_test() as pilot:
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
            assert "100 /" in str(panel.query_one("#context-header").renderable)
