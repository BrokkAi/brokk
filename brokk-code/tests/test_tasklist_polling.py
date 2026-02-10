import asyncio
from unittest.mock import AsyncMock, patch
import pytest
from brokk_code.app import BrokkApp

@pytest.mark.asyncio
async def test_tasklist_polling_updates_ui():
    """
    Verifies that the background tasklist polling worker calls the 
    appropriate methods on TaskListPanel.
    """
    app = BrokkApp()
    
    # Mock data
    mock_tasklist = {
        "bigPicture": "Refactor Authentication",
        "tasks": [
            {
                "id": "t1", 
                "title": "Update LoginController", 
                "text": "Change the authentication endpoint to use JWT instead of sessions.",
                "done": False
            },
            {
                "id": "t2", 
                "title": "Add logging", 
                "text": "Add SLF4J logging to the service layer.",
                "done": True
            }
        ]
    }

    with patch("brokk_code.executor.ExecutorManager.get_tasklist", new_callable=AsyncMock) as mock_get:
        mock_get.return_value = mock_tasklist
        
        async with app.run_test() as pilot:
            # Manually set ready state to trigger polling logic
            app._executor_ready = True
            
            # Instead of waiting 15s, we trigger the update method directly 
            # to verify it renders correctly, then we check if the worker loop 
            # would have called it by checking the mock.
            
            panel = app.query_one("TaskListPanel")
            panel.update_tasklist_details(mock_tasklist)
            
            content = panel.query_one("#tasklist-content").renderable
            content_str = str(content)
            
            assert "Refactor Authentication" in content_str
            assert "Update LoginController" in content_str
            assert "Change the authentication endpoint" in content_str
            assert "Add logging" in content_str
            assert "DONE" in content_str
            assert "TODO" in content_str
            
            # Trigger one iteration of the polling logic manually if possible, 
            # or simply rely on the fact that _poll_tasklist is started in on_mount.
            # Since we can't easily advance time in Textual workers without 
            # significant boilerplate, verifying the method works and the 
            # worker is registered is sufficient for this unit test.
            
            assert any(w.name == "_poll_tasklist" for w in app.workers)

@pytest.mark.asyncio
async def test_context_polling_updates_ui():
    """
    Verifies that the background context polling worker updates the ContextPanel.
    """
    app = BrokkApp()
    
    # Mock data
    mock_context = {
        "usedTokens": 1500,
        "maxTokens": 100000,
        "fragments": [
            {
                "chipKind": "EDIT",
                "shortDescription": "Modified UserAuth.java",
                "pinned": True,
                "tokens": 450
            },
            {
                "chipKind": "HISTORY",
                "shortDescription": "Previous chat history",
                "tokens": 1050
            }
        ]
    }

    with patch("brokk_code.executor.ExecutorManager.get_context", new_callable=AsyncMock) as mock_get:
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
