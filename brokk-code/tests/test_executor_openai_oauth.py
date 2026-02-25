import pytest
import httpx
from unittest.mock import AsyncMock, MagicMock
from brokk_code.executor import ExecutorManager, ExecutorError

@pytest.mark.asyncio
async def test_start_openai_oauth_success():
    # Setup
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "started"}
    mock_client.post.return_value = mock_response
    manager._http_client = mock_client

    # Execute
    result = await manager.start_openai_oauth()

    # Verify
    assert result == {"status": "started"}
    mock_client.post.assert_called_once_with("/v1/openai/oauth/start")

@pytest.mark.asyncio
async def test_start_openai_oauth_not_started():
    manager = ExecutorManager()
    manager._http_client = None

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.start_openai_oauth()

@pytest.mark.asyncio
async def test_start_openai_oauth_http_error():
    # Setup
    manager = ExecutorManager()
    manager.base_url = "http://localhost:1234"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    
    # Create a real request for the exception to wrap
    request = httpx.Request("POST", "http://localhost:1234/v1/openai/oauth/start")
    response = httpx.Response(404, request=request)
    
    # Mock post to raise HTTPStatusError
    mock_client.post.side_effect = httpx.HTTPStatusError("Not Found", request=request, response=response)
    manager._http_client = mock_client

    # Execute and Verify
    with pytest.raises(ExecutorError) as exc_info:
        await manager.start_openai_oauth()
    
    assert "404" in str(exc_info.value)
    assert "/v1/openai/oauth/start" in str(exc_info.value)
