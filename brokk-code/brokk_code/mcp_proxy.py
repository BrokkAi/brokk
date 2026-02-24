import asyncio
import logging
import signal
import sys
from pathlib import Path
from typing import Any, List, Optional

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import (
    TextContent,
    Tool,
)

from brokk_code.executor import ExecutorManager, ExecutorError

logger = logging.getLogger(__name__)

_BEMS_MAIN_CLASS = "ai.brokk.mcp.McpMain"
_BEMS_DEFAULT_PORT = 3001
_BEMS_DEFAULT_IDLE = 300
_BEMS_PROBE_TIMEOUT = 120.0
_BEMS_PROBE_INTERVAL = 0.5


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

    def _get_executor_args(self, exec_id: str) -> List[str]:
        return ["--port", str(self._port), "--idle", str(self._idle)]

    def _make_http_client(self, base_url: str) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=base_url, timeout=300.0)

    async def _await_ready(self, exec_id: str) -> int:
        """
        BEMS listens on the configured port. Drain stdout in the background (so the
        process doesn't block on a full pipe) and probe the HTTP endpoint
        until it responds, then return the port.
        """
        asyncio.get_event_loop().create_task(self._drain_stdout())
        await _probe_bems(self._port)
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


async def _probe_bems(port: int) -> None:
    """
    Probe BEMS until it responds on the given port, or raise ExecutorError on timeout.
    Tries a tools/list call rather than /sse so we also confirm JSON-RPC is up.
    """
    base_url = f"http://127.0.0.1:{port}"
    probe_client = httpx.AsyncClient(base_url=base_url, timeout=5.0)
    deadline = asyncio.get_event_loop().time() + _BEMS_PROBE_TIMEOUT
    try:
        while asyncio.get_event_loop().time() < deadline:
            try:
                resp = await probe_client.post(
                    "/message",
                    json={"jsonrpc": "2.0", "id": 0, "method": "tools/list", "params": {}},
                )
                if resp.status_code < 500:
                    return
            except httpx.ConnectError:
                pass
            await asyncio.sleep(_BEMS_PROBE_INTERVAL)
    finally:
        await probe_client.aclose()

    raise ExecutorError(
        f"BEMS did not become reachable on port {port} within {_BEMS_PROBE_TIMEOUT:.0f}s"
    )


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

    Responsibilities:
    - Lazy-start BEMS if not already running (connect-first, start-second).
    - Hold an open SSE stream to keep the server's idle timer from firing.
    - On any request failure, restart BEMS and retry once.
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
        self._keepalive_task: Optional[asyncio.Task[None]] = None
        self._tools: list[dict[str, Any]] = []

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    async def ensure_running(self) -> None:
        """Ensure BEMS is reachable, starting it if necessary."""
        if await self._is_alive():
            return
        await self._start_fresh()

    async def get_tools(self) -> list[dict[str, Any]]:
        """Return the cached tool list, fetching it if needed."""
        await self.ensure_running()
        if not self._tools:
            assert self._http_client is not None
            self._tools = await _fetch_bems_tools(self._http_client)
        return self._tools

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """Call a BEMS tool, restarting and retrying once on connection failure."""
        await self.ensure_running()
        assert self._http_client is not None
        try:
            return await _call_bems_tool(self._http_client, name, arguments)
        except (httpx.ConnectError, httpx.RemoteProtocolError) as exc:
            logger.warning("BEMS connection lost (%s); restarting and retrying.", exc)
            await self._restart()
            assert self._http_client is not None
            return await _call_bems_tool(self._http_client, name, arguments)

    async def stop(self) -> None:
        """Shut down the keepalive task and the managed BEMS process (if we own it)."""
        await self._cancel_keepalive()
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        if self._bems is not None:
            await self._bems.stop()
            self._bems = None

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _is_alive(self) -> bool:
        """Return True if BEMS is already answering on the configured port."""
        if self._http_client is None:
            probe = httpx.AsyncClient(base_url=self._base_url, timeout=3.0)
            try:
                resp = await probe.post(
                    "/message",
                    json={"jsonrpc": "2.0", "id": 0, "method": "tools/list", "params": {}},
                )
                alive = resp.status_code < 500
            except httpx.ConnectError:
                alive = False
            finally:
                await probe.aclose()

            if alive:
                # An existing BEMS is running (not started by us); attach to it.
                self._http_client = httpx.AsyncClient(base_url=self._base_url, timeout=300.0)
                self._start_keepalive()
            return alive

        # We already have a client; verify the connection is still good.
        try:
            resp = await self._http_client.post(
                "/message",
                json={"jsonrpc": "2.0", "id": 0, "method": "tools/list", "params": {}},
            )
            return resp.status_code < 500
        except httpx.ConnectError:
            return False

    async def _start_fresh(self) -> None:
        """Launch a new BEMS subprocess and set up the HTTP client."""
        await self._cancel_keepalive()
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
        self._tools = []
        self._start_keepalive()

    async def _restart(self) -> None:
        """Stop any existing BEMS and start a fresh one."""
        logger.info("Restarting BEMS...")
        if self._bems is not None:
            old = self._bems
            self._bems = None
            await old.stop()
        await self._cancel_keepalive()
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        self._tools = []
        await self._start_fresh()

    def _start_keepalive(self) -> None:
        """Start a background task that holds an open SSE connection to BEMS."""
        if self._keepalive_task is not None and not self._keepalive_task.done():
            return
        self._keepalive_task = asyncio.get_event_loop().create_task(self._keepalive_loop())

    async def _cancel_keepalive(self) -> None:
        if self._keepalive_task is not None:
            self._keepalive_task.cancel()
            try:
                await self._keepalive_task
            except asyncio.CancelledError:
                pass
            self._keepalive_task = None

    async def _keepalive_loop(self) -> None:
        """
        Hold a long-lived GET /sse connection to BEMS so the server's idle
        watchdog sees an active SSE connection and does not shut down while
        this proxy is running.

        If the connection drops, we wait briefly and reconnect.
        """
        while True:
            try:
                async with httpx.AsyncClient(base_url=self._base_url, timeout=None) as client:
                    async with client.stream("GET", "/sse") as response:
                        # Consume the stream; we only care that it stays open.
                        async for _ in response.aiter_bytes(chunk_size=4096):
                            pass
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.debug("SSE keepalive dropped (%s); reconnecting in 2s.", exc)
                await asyncio.sleep(2.0)


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

    # Eagerly ensure BEMS is up before we accept any MCP requests.
    try:
        await conn.ensure_running()
    except Exception as exc:
        print(f"Error: Failed to connect to or start BEMS: {exc}", file=sys.stderr)
        sys.exit(1)

    # Fetch tool definitions once at startup.
    try:
        bems_tools_raw = await conn.get_tools()
    except Exception as exc:
        print(f"Error: Could not fetch tools from BEMS: {exc}", file=sys.stderr)
        await conn.stop()
        sys.exit(1)

    mcp_tools = [
        Tool(
            name=t["name"],
            description=t.get("description", ""),
            inputSchema=t.get("inputSchema", {"type": "object", "properties": {}}),
        )
        for t in bems_tools_raw
    ]

    server = Server("brokk-mcp-proxy")

    @server.list_tools()
    async def list_tools() -> list[Tool]:
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
