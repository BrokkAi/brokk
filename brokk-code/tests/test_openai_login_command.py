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


@pytest.mark.asyncio
async def test_is_openai_codex_connected_helper(tmp_path, monkeypatch):
    from brokk_code.settings import is_openai_codex_connected, get_brokk_properties_path

    # Point properties path to tmp_path
    props_path = tmp_path / "brokk.properties"
    monkeypatch.setattr("brokk_code.settings.get_brokk_properties_path", lambda: props_path)

    # 1. Missing file
    assert is_openai_codex_connected() is False

    # 2. Key set to true
    props_path.write_text("openAiCodexOauthConnected=true\n", encoding="utf-8")
    assert is_openai_codex_connected() is True

    # 3. Key set to false
    props_path.write_text("openAiCodexOauthConnected=false\n", encoding="utf-8")
    assert is_openai_codex_connected() is False


@pytest.mark.asyncio
async def test_info_command_includes_codex_status():
    app = BrokkApp()
    mock_chat = MagicMock()

    with patch.object(app, "query_one", return_value=mock_chat), \
         patch("brokk_code.app.is_openai_codex_connected", return_value=True):
        app._render_info()

    # Verify info markup contains OpenAI Codex status
    call_args = mock_chat.add_system_message_markup.call_args[0][0]
    assert "OpenAI Codex: [bold]Connected[/]" in call_args
