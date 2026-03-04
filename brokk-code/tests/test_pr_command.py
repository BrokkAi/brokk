import pytest
from unittest.mock import AsyncMock, MagicMock
from brokk_code.app import BrokkApp

@pytest.mark.asyncio
async def test_pr_command_in_catalog():
    app = BrokkApp()
    commands = app.get_slash_commands()
    pr_cmd = next((c for c in commands if c["command"] == "/pr"), None)
    assert pr_cmd is not None
    assert "Pull Request" in pr_cmd["description"]

@pytest.mark.asyncio
async def test_handle_command_pr_routing():
    # Mock executor and chat panel
    mock_executor = MagicMock()
    app = BrokkApp(executor=mock_executor)
    app._executor_ready = True
    
    # Mock the job runner to avoid real network/streaming
    app._run_pr_create_job = AsyncMock()
    
    # Simulate /pr command
    app._handle_command("/pr")
    
    # Verify routing
    app._run_pr_create_job.assert_called_once()

@pytest.mark.asyncio
async def test_handle_command_pr_not_ready():
    mock_executor = MagicMock()
    app = BrokkApp(executor=mock_executor)
    app._executor_ready = False
    
    # We need a chat panel to receive the system message
    mock_chat = MagicMock()
    app.query_one = MagicMock(return_value=mock_chat)
    
    app._handle_command("/pr")
    
    # Should show warning instead of running job
    mock_chat.add_system_message.assert_called_once()
    args, kwargs = mock_chat.add_system_message.call_args
    assert "not yet ready" in args[0]
