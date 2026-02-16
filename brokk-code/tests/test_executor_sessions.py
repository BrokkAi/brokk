from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

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


@pytest.mark.asyncio
async def test_list_sessions():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "sessions": [{"id": "abc", "name": "Test", "current": True}],
        "currentSessionId": "abc",
    }
    mock_client.get.return_value = mock_response

    result = await executor.list_sessions()

    assert result["currentSessionId"] == "abc"
    assert len(result["sessions"]) == 1
    mock_client.get.assert_called_once_with("/v1/sessions")


@pytest.mark.asyncio
async def test_switch_session():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "switched", "sessionId": "new-id"}
    mock_client.post.return_value = mock_response

    result = await executor.switch_session("new-id")

    assert result["status"] == "switched"
    assert executor.session_id == "new-id"
    args, kwargs = mock_client.post.call_args
    assert args[0] == "/v1/sessions/switch"
    assert kwargs["json"]["sessionId"] == "new-id"


@pytest.mark.asyncio
async def test_rename_session():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "renamed", "sessionId": "sid", "name": "New Name"}
    mock_client.post.return_value = mock_response

    result = await executor.rename_session("sid", "New Name")

    assert result["status"] == "renamed"
    args, kwargs = mock_client.post.call_args
    assert args[0] == "/v1/sessions/rename"
    assert kwargs["json"]["sessionId"] == "sid"
    assert kwargs["json"]["name"] == "New Name"


@pytest.mark.asyncio
async def test_delete_session():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "deleted", "sessionId": "del-id"}
    mock_client.delete.return_value = mock_response

    result = await executor.delete_session("del-id")

    assert result["status"] == "deleted"
    mock_client.delete.assert_called_once_with("/v1/sessions/del-id")


@pytest.mark.asyncio
async def test_undo_context():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "undone"}
    mock_client.post.return_value = mock_response

    result = await executor.undo_context()

    assert result["status"] == "undone"
    mock_client.post.assert_called_once_with("/v1/context/undo")


@pytest.mark.asyncio
async def test_redo_context():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "redone"}
    mock_client.post.return_value = mock_response

    result = await executor.redo_context()

    assert result["status"] == "redone"
    mock_client.post.assert_called_once_with("/v1/context/redo")
