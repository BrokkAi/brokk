import asyncio
import logging
import signal
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import (
    TextContent,
    Tool,
)

from brokk_code.executor import ExecutorManager

logger = logging.getLogger(__name__)

_BEMS_MAIN_CLASS = "ai.brokk.mcpserver.McpMain"
_BEMS_DEFAULT_PORT = 3001
_BEMS_DEFAULT_IDLE = 300


class _BemsExecutorManager(ExecutorManager):
    """ExecutorManager variant that launches McpMain instead of HeadlessExecutorMain."""

    def __init__(
        self,
        *args: Any,
        port: int = _BEMS_DEFAULT_PORT,
        idle: int = _BEMS_DEFAULT_IDLE,
        **kwargs: Any,
    ) -> None:
        super().__init__(*args, **kwargs)
        self._port = port
        self._idle = idle

    @property
    def _main_class(self) -> str:
        return _BEMS_MAIN_CLASS

    def _get_executor_args(self, exec_id: str) -> list[str]:
        return ["--port", str(self._port), "--idle", str(self._idle)]

    def _make_http_client(self, base_url: str) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=base_url, timeout=300.0)

    async def _await_ready(self, exec_id: str) -> int:
        """
        BEMS listens on the configured port. Drain stdout in the background (so the
        process doesn't block on a full pipe), then return the known port immediately.
        """
        asyncio.get_event_loop().create_task(self._drain_stdout())
        return self._port

    async def _drain_stdout(self) -> None:
        """Consume subprocess stdout so the process never blocks on a full pipe."""
        if self._process is None or self._process.stdout is None:
            return
        try:
            while True:
                line_bytes = await self._process.stdout.readline()
                if not line_bytes:
                    break
                logger.debug("BEMS: %s", line_bytes.decode().strip())
        except Exception:
            pass

async def _fetch_bems_tools(http_client: httpx.AsyncClient) -> list[dict[str, Any]]:
    """Fetch the MCP tool list from BEMS via its JSON-RPC endpoint."""
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/list",
        "params": {},
    }
    resp = await http_client.post("/message", json=payload)
    resp.raise_for_status()
    data = resp.json()
    return data.get("result", {}).get("tools", [])


async def _call_bems_tool(
    http_client: httpx.AsyncClient, name: str, arguments: dict[str, Any]
) -> Any:
    """Invoke a BEMS tool via JSON-RPC and return the result content."""
    payload = {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    resp = await http_client.post("/message", json=payload)
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError(f"BEMS tool error: {data['error']}")
    return data.get("result", {})


class _BemsConnection:
    """
    Manages a single live connection to BEMS.

    The proxy tries to use whatever server is already listening on the configured
    port; on message-level connection failures it restarts the managed process and
    retries once.
    """

    def __init__(
        self,
        workspace_dir: Path,
        jar_path: Optional[Path],
        executor_version: Optional[str],
        executor_snapshot: bool,
        vendor: Optional[str],
        port: int = _BEMS_DEFAULT_PORT,
        idle: int = _BEMS_DEFAULT_IDLE,
    ) -> None:
        self._workspace_dir = workspace_dir
        self._jar_path = jar_path
        self._executor_version = executor_version
        self._executor_snapshot = executor_snapshot
        self._vendor = vendor
        self._port = port
        self._idle = idle
        self._base_url = f"http://127.0.0.1:{port}"

        self._bems: Optional[_BemsExecutorManager] = None
        self._http_client: Optional[httpx.AsyncClient] = None

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    async def get_tools(self) -> list[dict[str, Any]]:
        """Fetch tool definitions from BEMS."""
        return await self._request_with_retry(_fetch_bems_tools)

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """Call a BEMS tool, restarting and retrying on connection failure."""
        return await self._request_with_retry(
            lambda http_client: _call_bems_tool(http_client, name, arguments)
        )

    async def stop(self) -> None:
        """Shut down the managed BEMS process (if we own it)."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        if self._bems is not None:
            await self._bems.stop()
            self._bems = None

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _request_with_retry(
        self, send: Callable[[httpx.AsyncClient], Awaitable[Any]]
    ) -> Any:
        """Run one normal MCP message and recover only on transport failure."""
        if self._http_client is None:
            self._http_client = httpx.AsyncClient(base_url=self._base_url, timeout=300.0)

        for attempt in range(3):
            try:
                assert self._http_client is not None
                return await send(self._http_client)
            except (httpx.ConnectError, httpx.RemoteProtocolError) as exc:
                if attempt == 2:
                    logger.error("BEMS request failed after retry: %s", exc)
                    raise
                logger.warning(
                    "BEMS transport failed (%s); restarting managed BEMS and retrying.",
                    exc,
                )
                await self._restart()
                await asyncio.sleep(2.0)

    async def _start_fresh(self) -> None:
        """Launch a new BEMS subprocess and replace the HTTP client."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None

        bems = _BemsExecutorManager(
            workspace_dir=self._workspace_dir,
            jar_path=self._jar_path,
            executor_version=self._executor_version,
            executor_snapshot=self._executor_snapshot,
            vendor=self._vendor,
            port=self._port,
            idle=self._idle,
        )
        await bems.start()
        self._bems = bems
        assert bems._http_client is not None
        self._http_client = bems._http_client

    async def _restart(self) -> None:
        """Stop any managed BEMS and start a fresh one."""
        logger.info("Restarting BEMS...")
        if self._bems is not None:
            old = self._bems
            self._bems = None
            await old.stop()
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        await self._start_fresh()


async def run_mcp_proxy(
    workspace_dir: Path,
    jar_path: Optional[Path] = None,
    executor_version: Optional[str] = None,
    executor_snapshot: bool = True,
    vendor: Optional[str] = None,
    port: int = _BEMS_DEFAULT_PORT,
    idle: int = _BEMS_DEFAULT_IDLE,
) -> None:
    """
    Lazily connect to (or start) BrokkExternalMcpServer over HTTP, then expose
    it as an MCP stdio server so that MCP clients connecting via stdin/stdout
    can use it.
    """
    conn = _BemsConnection(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        port=port,
        idle=idle,
    )

    server = Server("brokk-mcp-proxy")

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        mcp_tools_raw = await conn.get_tools()
        mcp_tools = [
            Tool(
                name=t["name"],
                description=t.get("description", ""),
                inputSchema=t.get("inputSchema", {"type": "object", "properties": {}}),
            )
            for t in mcp_tools_raw
        ]
        return mcp_tools

    @server.call_tool()
    async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
        result = await conn.call_tool(name, arguments)
        content_blocks = result.get("content", [])
        if isinstance(content_blocks, list):
            texts = [
                block.get("text", "") if isinstance(block, dict) else str(block)
                for block in content_blocks
            ]
            return [TextContent(type="text", text="\n".join(texts))]
        return [TextContent(type="text", text=str(result))]

    loop = asyncio.get_running_loop()

    async def _shutdown() -> None:
        logger.info("Shutdown signal received; stopping BEMS...")
        await conn.stop()

    def _handle_signal() -> None:
        loop.create_task(_shutdown())

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, _handle_signal)
        except (NotImplementedError, OSError):
            pass

    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())
