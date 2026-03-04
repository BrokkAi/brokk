import pytest
from unittest.mock import AsyncMock, MagicMock
from textual.app import App
from brokk_code.app import BrokkApp

@pytest.mark.asyncio
async def test_pr_command_stability():
    """Assert /pr is in the slash command catalog."""
    app = BrokkApp()
    commands = app.get_slash_commands()
    pr_cmd = next((c for c in commands if c["command"] == "/pr"), None)
    assert pr_cmd is not None
    assert "Pull Request" in pr_cmd["description"]

@pytest.mark.asyncio
async def test_pr_command_routing_tui():
    """Verify /pr triggers the PR creation worker."""
    app = BrokkApp()
    app._executor_ready = True
    app._run_pr_create_job = AsyncMock()
    
    # Simulate /pr command handling
    app._handle_command("/pr")
    
    app._run_pr_create_job.assert_called_once()
