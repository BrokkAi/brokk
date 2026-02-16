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
async def test_list_sessions_calls_expected_endpoint_and_returns_json():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    fake_json = {"sessions": [{"sessionId": "a", "name": "One"}]}
    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.json.return_value = fake_json
    mock_client.get.return_value = mock_response

    result = await executor.list_sessions()
    assert result == fake_json
    mock_client.get.assert_called_with("/v1/sessions")


@pytest.mark.asyncio
async def test_delete_session_success_and_failure():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    # success case: 204 No Content
    mock_response_ok = MagicMock(spec=httpx.Response)
    mock_response_ok.status_code = 204
    mock_client.delete.return_value = mock_response_ok

    await executor.delete_session("some-id")
    mock_client.delete.assert_called_with("/v1/sessions/some-id")

    # failure case: 404 Not Found -> should raise ExecutorError
    mock_response_notfound = MagicMock(spec=httpx.Response)
    mock_response_notfound.status_code = 404
    mock_response_notfound.json.return_value = {"code": "NOT_FOUND", "message": "Session not found"}
    # Simulate httpx raising HTTPStatusError with attached response
    err = httpx.HTTPStatusError("Not found", request=MagicMock(), response=mock_response_notfound)
    mock_client.delete.side_effect = err

    with pytest.raises(Exception):
        await executor.delete_session("missing-id")


@pytest.mark.asyncio
async def test_undo_and_redo_context_endpoints():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    # undo success
    undo_resp = MagicMock(spec=httpx.Response)
    undo_resp.status_code = 200
    undo_resp.json.return_value = {"wasUndone": True, "hasMoreUndo": False}
    # redo success
    redo_resp = MagicMock(spec=httpx.Response)
    redo_resp.status_code = 200
    redo_resp.json.return_value = {"wasRedone": True, "hasMoreRedo": False}

    async def post_side_effect(path, *args, **kwargs):
        if path == "/v1/context/redo":
            return redo_resp
        if path == "/v1/context/undo":
            return undo_resp
        # default
        m = MagicMock(spec=httpx.Response)
        m.status_code = 200
        m.json.return_value = {}
        return m

    mock_client.post.side_effect = post_side_effect

    res = await executor.undo_context()
    assert res == {"wasUndone": True, "hasMoreUndo": False}
    mock_client.post.assert_any_call("/v1/context/undo")

    res2 = await executor.redo_context()
    assert res2 == {"wasRedone": True, "hasMoreRedo": False}
    mock_client.post.assert_any_call("/v1/context/redo")


@pytest.mark.asyncio
async def test_undo_context_returns_none_on_400():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    # simulate nothing to undo (server responds 400) -> undo_context should return None
    bad_resp = MagicMock(spec=httpx.Response)
    bad_resp.status_code = 400
    err400 = httpx.HTTPStatusError("Bad request", request=MagicMock(), response=bad_resp)
    mock_client.post.side_effect = err400

    res = await executor.undo_context()
    assert res is None


@pytest.mark.asyncio
async def test_redo_context_returns_none_on_400():
    executor = ExecutorManager()
    executor.base_url = "http://localhost:8080"
    mock_client = AsyncMock(spec=httpx.AsyncClient)
    executor._http_client = mock_client

    bad_resp = MagicMock(spec=httpx.Response)
    bad_resp.status_code = 400
    err400 = httpx.HTTPStatusError("Bad request", request=MagicMock(), response=bad_resp)
    mock_client.post.side_effect = err400

    res = await executor.redo_context()
    assert res is None
