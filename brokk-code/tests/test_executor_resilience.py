from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

from brokk_code.executor import ExecutorError, ExecutorManager


@pytest.mark.asyncio
async def test_get_tasklist_404_with_diagnostic():
    """Test that get_tasklist handles 404 with executor version diagnostics."""
    manager = ExecutorManager()
    # Mock the internal http client
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client
    manager.base_url = "http://127.0.0.1:1234"

    # 1. Setup 404 for tasklist
    tasklist_response = MagicMock(spec=httpx.Response)
    tasklist_response.status_code = 404

    # 2. Setup 200 for executor info diagnostic
    executor_response = MagicMock(spec=httpx.Response)
    executor_response.status_code = 200
    executor_response.json.return_value = {"version": "0.9.0", "protocolVersion": "1"}

    def side_effect(url, **kwargs):
        if "/v1/tasklist" in url:
            raise httpx.HTTPStatusError(
                "404 Not Found", request=MagicMock(), response=tasklist_response
            )
        if "/v1/executor" in url:
            return executor_response
        return MagicMock()

    mock_client.get.side_effect = side_effect

    with pytest.raises(ExecutorError) as exc_info:
        await manager.get_tasklist()

    msg = str(exc_info.value)
    assert "/v1/tasklist" in msg
    assert "404" in msg
    assert "version may be too old" in msg
    assert "0.9.0" in msg
    assert "Protocol: 1" in msg


@pytest.mark.asyncio
async def test_get_context_404_with_diagnostic():
    """Test that get_context handles 404 with executor version diagnostics."""
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client
    manager.base_url = "http://127.0.0.1:1234"

    # Setup 404 for context
    context_response = MagicMock(spec=httpx.Response)
    context_response.status_code = 404

    # Setup 200 for executor info diagnostic
    executor_response = MagicMock(spec=httpx.Response)
    executor_response.status_code = 200
    executor_response.json.return_value = {"version": "0.9.5", "protocolVersion": "2"}

    def side_effect(url, **kwargs):
        if "/v1/context" in url:
            raise httpx.HTTPStatusError(
                "404 Not Found", request=MagicMock(), response=context_response
            )
        if "/v1/executor" in url:
            return executor_response
        return MagicMock()

    mock_client.get.side_effect = side_effect

    with pytest.raises(ExecutorError) as exc_info:
        await manager.get_context()

    msg = str(exc_info.value)
    assert "/v1/context" in msg
    assert "404" in msg
    assert "version may be too old" in msg
    assert "0.9.5" in msg
    assert "Protocol: 2" in msg


@pytest.mark.asyncio
async def test_get_models_404_with_diagnostic():
    """Test that get_models handles 404 with executor version diagnostics."""
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client
    manager.base_url = "http://127.0.0.1:1234"

    models_response = MagicMock(spec=httpx.Response)
    models_response.status_code = 404

    executor_response = MagicMock(spec=httpx.Response)
    executor_response.status_code = 200
    executor_response.json.return_value = {"version": "1.0.0", "protocolVersion": "2"}

    def side_effect(url, **kwargs):
        if "/v1/models" in url:
            raise httpx.HTTPStatusError(
                "404 Not Found", request=MagicMock(), response=models_response
            )
        if "/v1/executor" in url:
            return executor_response
        return MagicMock()

    mock_client.get.side_effect = side_effect

    with pytest.raises(ExecutorError) as exc_info:
        await manager.get_models()

    msg = str(exc_info.value)
    assert "/v1/models" in msg
    assert "404" in msg
    assert "version may be too old" in msg
    assert "1.0.0" in msg
    assert "Protocol: 2" in msg


@pytest.mark.asyncio
async def test_get_models_success():
    """Verify get_models success path."""
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client

    mock_data = {"models": [{"name": "gpt-5.2"}]}
    success_response = MagicMock(spec=httpx.Response)
    success_response.status_code = 200
    success_response.json.return_value = mock_data
    success_response.raise_for_status.return_value = None

    mock_client.get.return_value = success_response

    result = await manager.get_models()
    assert result == mock_data
    mock_client.get.assert_called_once_with("/v1/models")


@pytest.mark.asyncio
async def test_get_tasklist_generic_http_error():
    """Test that get_tasklist handles non-404 HTTP errors consistently."""
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client

    error_response = MagicMock(spec=httpx.Response)
    error_response.status_code = 500

    mock_client.get.side_effect = httpx.HTTPStatusError(
        "Internal Server Error", request=MagicMock(), response=error_response
    )

    with pytest.raises(ExecutorError) as exc_info:
        await manager.get_tasklist()

    msg = str(exc_info.value)
    assert "/v1/tasklist" in msg
    assert "status=500" in msg
    assert "HTTPStatusError" in msg


@pytest.mark.asyncio
async def test_get_tasklist_success():
    """Verify success path remains intact."""
    manager = ExecutorManager()
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    manager._http_client = mock_client

    mock_data = {"bigPicture": "Goal", "tasks": []}
    success_response = MagicMock(spec=httpx.Response)
    success_response.status_code = 200
    success_response.json.return_value = mock_data
    success_response.raise_for_status.return_value = None

    mock_client.get.return_value = success_response

    result = await manager.get_tasklist()
    assert result == mock_data
    mock_client.get.assert_called_once_with("/v1/tasklist")
