import pytest
import httpx
from brokk_code.executor import ExecutorManager, ExecutorError


@pytest.mark.asyncio
async def test_start_openai_oauth_success(monkeypatch):
    manager = ExecutorManager()
    manager.base_url = "http://localhost:1234"
    manager.auth_token = "test-token"

    expected_response = {"status": "started"}

    async def mock_post(self, url, **kwargs):
        assert url == "/v1/openai/oauth/start"
        request = httpx.Request("POST", url)
        return httpx.Response(200, json=expected_response, request=request)

    monkeypatch.setattr(httpx.AsyncClient, "post", mock_post)
    manager._http_client = manager._make_http_client(manager.base_url)

    result = await manager.start_openai_oauth()
    assert result == expected_response


@pytest.mark.asyncio
async def test_start_openai_oauth_error(monkeypatch):
    manager = ExecutorManager()
    manager.base_url = "http://localhost:1234"

    async def mock_post(self, url, **kwargs):
        request = httpx.Request("POST", url)
        return httpx.Response(500, content=b"Server Error", request=request)

    monkeypatch.setattr(httpx.AsyncClient, "post", mock_post)
    manager._http_client = manager._make_http_client(manager.base_url)

    with pytest.raises(ExecutorError) as excinfo:
        await manager.start_openai_oauth()
    assert "/v1/openai/oauth/start" in str(excinfo.value)
    assert "status=500" in str(excinfo.value)


@pytest.mark.asyncio
async def test_get_openai_oauth_status_success(monkeypatch):
    manager = ExecutorManager()
    manager.base_url = "http://localhost:1234"

    expected_response = {"connected": True}

    async def mock_get(self, url, **kwargs):
        assert url == "/v1/openai/oauth/status"
        request = httpx.Request("GET", url)
        return httpx.Response(200, json=expected_response, request=request)

    monkeypatch.setattr(httpx.AsyncClient, "get", mock_get)
    manager._http_client = manager._make_http_client(manager.base_url)

    result = await manager.get_openai_oauth_status()
    assert result == expected_response


@pytest.mark.asyncio
async def test_get_openai_oauth_status_404_diagnostic(monkeypatch):
    manager = ExecutorManager()
    manager.base_url = "http://localhost:1234"

    async def mock_request(self, method, url, **kwargs):
        request = httpx.Request(method, url)
        if method == "GET" and str(url) == "/v1/openai/oauth/status":
            return httpx.Response(404, request=request)
        if method == "GET" and str(url) == "/v1/executor":
            return httpx.Response(
                200, json={"version": "1.0.0", "protocolVersion": "1"}, request=request
            )
        return httpx.Response(404, request=request)

    monkeypatch.setattr(httpx.AsyncClient, "request", mock_request)
    manager._http_client = manager._make_http_client(manager.base_url)

    with pytest.raises(ExecutorError) as excinfo:
        await manager.get_openai_oauth_status()
    assert "status=404" not in str(excinfo.value)  # Wrapped by _handle_http_error
    assert "Executor Version: 1.0.0" in str(excinfo.value)
    assert "/v1/openai/oauth/status" in str(excinfo.value)
