import asyncio
import logging
import signal
from pathlib import Path
from urllib.parse import parse_qs, urlparse
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

_BEMS_MAIN_CLASS = "ai.brokk.mcpserver.McpMain"
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
    Tries /sse so we can confirm the HTTP endpoint is reachable.
    """
    base_url = f"http://127.0.0.1:{port}"
    probe_client = httpx.AsyncClient(base_url=base_url, timeout=5.0)
    deadline = asyncio.get_event_loop().time() + _BEMS_PROBE_TIMEOUT
    try:
        while asyncio.get_event_loop().time() < deadline:
            try:
                async with probe_client.stream("GET", "/sse") as response:
                    if response.status_code < 500:
                        return
            except httpx.ConnectError:
                pass
            await asyncio.sleep(_BEMS_PROBE_INTERVAL)
    finally:
        await probe_client.aclose()

    raise ExecutorError(
        f"BEMS did not become reachable on port {port} within {_BEMS_PROBE_TIMEOUT:.0f}s"
    )


def _extract_session_id(sse_data: str) -> Optional[str]:
    parsed = urlparse(sse_data)
    return parse_qs(parsed.query).get("sessionId", [None])[0]


async def _start_bems_session(base_url: str) -> str:
    session_client = httpx.AsyncClient(base_url=base_url, timeout=5.0)
    try:
        async with session_client.stream("GET", "/sse") as response:
            if response.status_code >= 500:
                raise ExecutorError(f"BEMS returned HTTP {response.status_code} from /sse")

            async for line in response.aiter_lines():
                if not line.startswith("data:"):
                    continue

                value = line.removeprefix("data:").strip()
                session_id = _extract_session_id(value)
                if session_id:
                    return session_id

            raise ExecutorError("BEMS /sse stream returned no sessionId")
    finally:
        await session_client.aclose()


async def _fetch_bems_tools(
    http_client: httpx.AsyncClient, session_id: str
) -> list[dict[str, Any]]:
    """Fetch the MCP tool list from BEMS via its JSON-RPC endpoint."""
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/list",
        "params": {},
    }
    resp = await http_client.post(f"/message?sessionId={session_id}", json=payload)
    resp.raise_for_status()
    data = resp.json()
    return data.get("result", {}).get("tools", [])


async def _call_bems_tool(
    http_client: httpx.AsyncClient, session_id: str, name: str, arguments: dict[str, Any]
) -> Any:
    """Invoke a BEMS tool via JSON-RPC and return the result content."""
    payload = {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    resp = await http_client.post(f"/message?sessionId={session_id}", json=payload)
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError(f"BEMS tool error: {data['error']}")
    return data.get("result", {})


class _BemsConnection:
    """
    Manages a single live connection to BEMS.

    Responsibilities:
    - Connect to an existing BEMS if available.
    - Start or restart BEMS only when normal MCP messages fail.
    - Retry once after recovering from transport/session failures.
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
        self._session_id: Optional[str] = None

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    async def get_tools(self) -> list[dict[str, Any]]:
        """Fetch and return the MCP tool list with one recovery retry."""
        for attempt in range(2):
            try:
                await self._ensure_http_client()
                assert self._http_client is not None
                session_id = await self._ensure_session()
                return await _fetch_bems_tools(self._http_client, session_id)
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code == 400 and attempt == 0:
                    logger.debug("BEMS tools/list failed with HTTP 400; refreshing session.")
                    self._session_id = None
                    continue
                raise
            except (ExecutorError, httpx.ConnectError, httpx.RemoteProtocolError) as exc:
                if attempt == 0:
                    logger.warning(
                        "BEMS tools/list transport failure (%s); recovering and retrying.",
                        exc,
                    )
                    await self._start_or_restart()
                    continue
                raise

        raise RuntimeError("unreachable")

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """Call a BEMS tool with one recovery retry."""
        for attempt in range(2):
            try:
                await self._ensure_http_client()
                assert self._http_client is not None
                session_id = await self._ensure_session()
                return await _call_bems_tool(self._http_client, session_id, name, arguments)
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code == 400 and attempt == 0:
                    logger.debug("BEMS tools/call failed with HTTP 400; refreshing session.")
                    self._session_id = None
                    continue
                raise
            except (ExecutorError, httpx.ConnectError, httpx.RemoteProtocolError) as exc:
                if attempt == 0:
                    logger.warning(
                        "BEMS tools/call transport failure (%s); recovering and retrying.",
                        exc,
                    )
                    await self._start_or_restart()
                    continue
                raise

        raise RuntimeError("unreachable")

    async def stop(self) -> None:
        """Shut down the HTTP client and the managed BEMS process (if we own it)."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        if self._bems is not None:
            await self._bems.stop()
            self._bems = None
        self._session_id = None

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _ensure_http_client(self) -> None:
        if self._http_client is None:
            self._http_client = httpx.AsyncClient(base_url=self._base_url, timeout=300.0)

    async def _start_fresh(self) -> None:
        """Launch a new BEMS subprocess and set up the HTTP client."""
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
        self._session_id = None

    async def _restart(self) -> None:
        """Stop any existing BEMS and start a fresh one."""
        logger.info("Restarting BEMS...")
        if self._bems is not None:
            old = self._bems
            self._bems = None
            await old.stop()
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        self._session_id = None
        await self._start_fresh()

    async def _start_or_restart(self) -> None:
        if self._bems is None:
            logger.info("Starting BEMS...")
            await self._start_fresh()
        else:
            await self._restart()

    async def _ensure_session(self) -> str:
        """Return an active BEMS session id, creating one if needed."""
        if self._session_id is not None:
            return self._session_id
        self._session_id = await _start_bems_session(self._base_url)
        return self._session_id


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
        bems_tools_raw = await conn.get_tools()
        return [
            Tool(
                name=t["name"],
                description=t.get("description", ""),
                inputSchema=t.get("inputSchema", {"type": "object", "properties": {}}),
            )
            for t in bems_tools_raw
        ]

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
