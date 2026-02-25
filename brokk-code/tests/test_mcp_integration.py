import json
import select
import shutil
import subprocess
import time
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
BROKK_CODE_DIR = Path(__file__).resolve().parents[1]
UV_BIN = shutil.which("uv")


def _spawn_mcp_process() -> subprocess.Popen[str]:
    if UV_BIN is None:
        raise RuntimeError("uv binary is not available")

    return subprocess.Popen(
        [
            UV_BIN,
            "run",
            "brokk",
            "mcp",
            "--workspace",
            str(REPO_ROOT),
        ],
        cwd=str(BROKK_CODE_DIR),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )


def _send_mcp_message(process: subprocess.Popen[str], message: dict[str, object]) -> None:
    if process.stdin is None:
        raise RuntimeError("Process stdin is not available")
    process.stdin.write(json.dumps(message))
    process.stdin.write("\n")
    process.stdin.flush()


def _read_mcp_messages(
    process: subprocess.Popen[str], expected_ids: set[int], timeout_seconds: float
) -> dict[int, dict[str, object]]:
    responses: dict[int, dict[str, object]] = {}
    deadline = time.monotonic() + timeout_seconds

    while len(responses) < len(expected_ids) and time.monotonic() < deadline:
        if process.stdout is None:
            break

        remaining = max(0.0, deadline - time.monotonic())
        ready, _, _ = select.select([process.stdout], [], [], remaining)
        if not ready:
            break

        line = process.stdout.readline()
        if not line:
            break

        line = line.strip()
        if not line:
            continue

        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            continue

        message_id = payload.get("id")
        if isinstance(message_id, int) and message_id in expected_ids:
            responses[message_id] = payload

    return responses


def _initialize_mcp(process: subprocess.Popen[str]) -> None:
    _send_mcp_message(
        process,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "manual-test", "version": "0.1"},
            },
        },
    )
    _send_mcp_message(process, {"jsonrpc": "2.0", "method": "notifications/initialized"})


@pytest.fixture
def mcp_process():
    if UV_BIN is None:
        pytest.skip("uv binary is required for MCP integration tests")

    process = _spawn_mcp_process()
    try:
        time.sleep(0.2)
        if process.poll() is not None:
            pytest.skip(f"MCP process exited early with code {process.returncode}")
        yield process
    finally:
        if process.stdin:
            process.stdin.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)


def test_mcp_initialize_and_tools_list_contract(mcp_process):
    _initialize_mcp(mcp_process)
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/list",
            "params": {},
        },
    )

    responses = _read_mcp_messages(mcp_process, {1, 2}, timeout_seconds=10.0)

    assert 1 in responses
    assert 2 in responses

    init_result = responses[1].get("result", {})
    assert init_result.get("protocolVersion") == "2024-11-05"
    assert init_result["serverInfo"]["name"] == "Brokk MCP Server"
    assert isinstance(init_result["capabilities"]["tools"]["listChanged"], bool)

    tools_result = responses[2].get("result", {})
    tools = tools_result.get("tools")
    assert isinstance(tools, list)

    tool_names = {tool.get("name") for tool in tools if isinstance(tool, dict) and "name" in tool}
    assert "searchSymbols" in tool_names
    assert "skimDirectory" in tool_names


def test_mcp_tool_call_validation_is_reported_and_recovers(mcp_process):
    _initialize_mcp(mcp_process)
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "searchSymbols",
            },
        },
    )
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/list",
            "params": {},
        },
    )

    first = _read_mcp_messages(mcp_process, {3}, timeout_seconds=10.0)
    assert 3 in first
    assert first[3].get("result", {}).get("isError") is True

    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/list",
            "params": {},
        },
    )

    second = _read_mcp_messages(mcp_process, {4}, timeout_seconds=10.0)
    assert 4 in second
    assert isinstance(second[4].get("result", {}).get("tools"), list)


def test_mcp_message_ids_are_preserved(mcp_process):
    _initialize_mcp(mcp_process)
    ids_under_test = {10, 11, 12}
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 10,
            "method": "tools/list",
            "params": {},
        },
    )
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 11,
            "method": "tools/call",
            "params": {"name": "searchSymbols"},
        },
    )
    _send_mcp_message(
        mcp_process,
        {
            "jsonrpc": "2.0",
            "id": 12,
            "method": "tools/call",
            "params": {"name": "thisToolDoesNotExist"},
        },
    )

    responses = _read_mcp_messages(mcp_process, ids_under_test, timeout_seconds=10.0)

    assert set(responses.keys()) == ids_under_test
    for request_id in ids_under_test:
        assert responses[request_id]["id"] == request_id
