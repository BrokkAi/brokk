import asyncio
import json
from typing import Any
import pytest
import httpx

from brokk_code.executor import ExecutorManager, ExecutorError


@pytest.mark.asyncio
async def test_list_sessions_success_and_404_diagnostic():
    recorded = {"calls": []}

    async def handler(request: httpx.Request) -> httpx.Response:
        recorded["calls"].append((request.method, request.url.path))
        if request.url.path == "/v1/sessions":
            return httpx.Response(200, json=[{"id": "abc", "name": "S1"}])
        if request.url.path == "/v1/executor":
            return httpx.Response(200, json={"version": "1.2.3", "protocolVersion": "4"})
        return httpx.Response(404, json={"code": "NOT_FOUND"})

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(base_url="http://test", transport=transport)

    mgr = ExecutorManager()
    mgr._http_client = client

    sessions = await mgr.list_sessions()
    assert isinstance(sessions, list)
    assert sessions[0]["name"] == "S1"
    assert recorded["calls"][0][1] == "/v1/sessions"

    # Simulate 404 and ensure ExecutorError with diagnostic text is raised
    async def handler_404(request: httpx.Request) -> httpx.Response:
        # /v1/sessions returns 404
        if request.url.path == "/v1/sessions":
            return httpx.Response(404, json={"code": "NOT_FOUND"})
        # executor diag returns 200
        if request.url.path == "/v1/executor":
            return httpx.Response(200, json={"version": "x", "protocolVersion": "y"})
        return httpx.Response(404)

    transport2 = httpx.MockTransport(handler_404)
    client2 = httpx.AsyncClient(base_url="http://test", transport=transport2)
    mgr._http_client = client2

    with pytest.raises(ExecutorError) as excinfo:
        await mgr.list_sessions()
    assert "not found" in str(excinfo.value).lower()


@pytest.mark.asyncio
async def test_rename_delete_copy_session_and_bad_input():
    calls = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path, request.content))
        if request.method == "PATCH" and request.url.path.startswith("/v1/sessions/"):
            body = json.loads(request.content.decode())
            return httpx.Response(200, json={"id": request.url.path.split("/")[-1], "name": body.get("name")})
        if request.method == "DELETE" and request.url.path.startswith("/v1/sessions/"):
            return httpx.Response(200, json={"deleted": request.url.path.split("/")[-1]})
        if request.method == "POST" and request.url.path.endswith("/copy"):
            body = {}
            if request.content:
                try:
                    body = json.loads(request.content.decode())
                except Exception:
                    body = {}
            return httpx.Response(201, json={"id": "new-id", "name": body.get("name", "Copy")})
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(base_url="http://test", transport=transport)

    mgr = ExecutorManager()
    mgr._http_client = client

    # rename
    resp = await mgr.rename_session("sess-1", "New Name")
    assert resp["name"] == "New Name"
    assert calls[0][0] == "PATCH"
    assert "/v1/sessions/sess-1" in calls[0][1]

    # delete (no exception)
    await mgr.delete_session("sess-1")
    assert calls[1][0] == "DELETE"

    # copy with explicit name
    resp2 = await mgr.copy_session("sess-1", new_name="Copied Name")
    assert resp2["name"] == "Copied Name"
    assert calls[2][0] == "POST"
    assert calls[2][1].endswith("/copy")

    # copy without name
    resp3 = await mgr.copy_session("sess-1")
    assert resp3["name"] == "Copy"

    # invalid inputs
    with pytest.raises(ExecutorError):
        await mgr.rename_session("", "x")
    with pytest.raises(ExecutorError):
        await mgr.delete_session("")
    with pytest.raises(ExecutorError):
        await mgr.copy_session("", None)


@pytest.mark.asyncio
async def test_undo_redo_context():
    calls = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.url.path == "/v1/context/undo" and request.method == "POST":
            return httpx.Response(200, json={"status": "ok", "wasUndone": True, "steps": 1})
        if request.url.path == "/v1/context/redo" and request.method == "POST":
            return httpx.Response(200, json={"status": "ok", "wasRedone": True})
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(base_url="http://test", transport=transport)

    mgr = ExecutorManager()
    mgr._http_client = client

    res_undo = await mgr.undo_context()
    assert res_undo.get("wasUndone") is True
    assert calls[0][1] == "/v1/context/undo"

    res_redo = await mgr.redo_context()
    assert res_redo.get("wasRedone") is True
    assert calls[1][1] == "/v1/context/redo"

    # simulate server not supporting undo -> 404 should raise ExecutorError with diagnostic
    async def handler_404(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/v1/context/undo":
            return httpx.Response(404, json={"code": "NOT_FOUND"})
        if request.url.path == "/v1/executor":
            return httpx.Response(200, json={"version": "1", "protocolVersion": "1"})
        return httpx.Response(404)

    transport2 = httpx.MockTransport(handler_404)
    client2 = httpx.AsyncClient(base_url="http://test", transport=transport2)
    mgr._http_client = client2

    with pytest.raises(ExecutorError):
        await mgr.undo_context()
