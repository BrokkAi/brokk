import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorError

@pytest.mark.asyncio
async def test_get_slash_commands_includes_login_openai():
    commands = BrokkApp.get_slash_commands()
    login_cmd = next((c for c in commands if c["command"] == "/login-openai"), None)
    assert login_cmd is not None
    assert "OpenAI" in login_cmd["description"]

@pytest.mark.asyncio
async def test_handle_command_login_openai_executor_not_ready():
    app = BrokkApp()
    app._executor_ready = False
    
    mock_chat = MagicMock()
    with patch.object(app, "query_one", return_value=mock_chat):
        app._handle_command("/login-openai")
        
    mock_chat.add_system_message.assert_called_with(
        "Executor must be ready before connecting OpenAI.", level="ERROR"
    )

@pytest.mark.asyncio
async def test_handle_command_login_openai_executor_ready_triggers_worker():
    app = BrokkApp()
    app._executor_ready = True
    app.executor = MagicMock()
    
    mock_chat = MagicMock()
    with patch.object(app, "query_one", return_value=mock_chat), \
         patch.object(app, "run_worker") as mock_run_worker:
        app._handle_command("/login-openai")
        
        mock_chat.add_system_message.assert_called_with("Opening browser for OpenAI login...")
        mock_run_worker.assert_called_once()

@pytest.mark.asyncio
async def test_start_openai_login_success():
    app = BrokkApp()
    app.executor = AsyncMock()
    app.executor.start_openai_oauth.return_value = {"status": "started"}
    
    mock_chat = MagicMock()
    with patch.object(app, "_maybe_chat", return_value=mock_chat):
        await app._start_openai_login()
        
    app.executor.start_openai_oauth.assert_called_once()
    mock_chat.add_system_message.assert_called_with(
        "OpenAI login flow initiated. Please complete it in your browser."
    )

@pytest.mark.asyncio
async def test_start_openai_login_failure():
    app = BrokkApp()
    app.executor = AsyncMock()
    app.executor.start_openai_oauth.side_effect = ExecutorError("Connection failed")
    
    mock_chat = MagicMock()
    with patch.object(app, "_maybe_chat", return_value=mock_chat):
        await app._start_openai_login()
        
    mock_chat.add_system_message.assert_called_with(
        "OpenAI login failed: Connection failed", level="ERROR"
    )
