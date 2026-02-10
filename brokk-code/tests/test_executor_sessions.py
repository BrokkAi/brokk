import httpx
import pytest
from unittest.mock import AsyncMock, MagicMock
from brokk_code.executor import ExecutorManager


@pytest.mark.asyncio
async def test_download_session_zip():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    session_id = "test-session-123"
    fake_content = b"fake-zip-data"

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.content = fake_content
    mock_client.get.return_value = mock_response

    result = await executor.download_session_zip(session_id)

    assert result == fake_content
    mock_client.get.assert_called_once_with(f"/v1/sessions/{session_id}")


@pytest.mark.asyncio
async def test_import_session_zip_no_id():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    fake_zip = b"new-session-zip"
    returned_id = "newly-generated-id"

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 201
    mock_response.json.return_value = {"sessionId": returned_id}
    mock_client.put.return_value = mock_response

    result = await executor.import_session_zip(fake_zip)

    assert result == returned_id
    assert executor.session_id == returned_id

    # Verify call details
    args, kwargs = mock_client.put.call_args
    assert args[0] == "/v1/sessions"
    assert kwargs["content"] == fake_zip
    assert kwargs["headers"]["Content-Type"] == "application/zip"
    assert "X-Session-Id" not in kwargs["headers"]


@pytest.mark.asyncio
async def test_import_session_zip_with_id():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    fake_zip = b"existing-session-zip"
    requested_id = "requested-id"

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 201
    mock_response.json.return_value = {"sessionId": requested_id}
    mock_client.put.return_value = mock_response

    result = await executor.import_session_zip(fake_zip, session_id=requested_id)

    assert result == requested_id

    # Verify headers
    args, kwargs = mock_client.put.call_args
    assert kwargs["headers"]["X-Session-Id"] == requested_id
    assert kwargs["headers"]["Content-Type"] == "application/zip"
