import asyncio
import logging
import signal
from pathlib import Path
from typing import Optional

from brokk_code.executor import ExecutorManager

logger = logging.getLogger(__name__)

_BEMS_MAIN_CLASS = "ai.brokk.mcpserver.BrokkExternalMcpServer"


class _BemsStdioExecutorManager(ExecutorManager):
    @property
    def _main_class(self) -> str:
        return _BEMS_MAIN_CLASS

    def _get_executor_args(self, exec_id: str) -> list[str]:
        return ["--exit-on-stdin-eof"] if self.exit_on_stdin_eof else []

    async def start_stdio(self) -> asyncio.subprocess.Process:
        exec_id = "mcp-stdio"

        if self.jar_override:
            self.resolved_jar_path = self.jar_override
            cmd = self._get_direct_java_command(self.jar_override, exec_id)
        else:
            dev_jar = self._find_dev_jar()
            if dev_jar:
                self.resolved_jar_path = dev_jar
                cmd = self._get_direct_java_command(dev_jar, exec_id)
            else:
                cmd = await self._get_jbang_command(exec_id)

        logger.info("Starting MCP stdio server: %s", " ".join(cmd))

        self._process = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=str(self.workspace_dir),
        )
        return self._process


async def run_mcp_proxy(
    workspace_dir: Path,
    jar_path: Optional[Path] = None,
    executor_version: Optional[str] = None,
    executor_snapshot: bool = True,
    vendor: Optional[str] = None,
    port: int = 0,
    idle: int = 0,
) -> None:
    del vendor, port, idle

    bems = _BemsStdioExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        exit_on_stdin_eof=True,
    )

    proc = await bems.start_stdio()

    loop = asyncio.get_running_loop()

    async def _shutdown() -> None:
        if proc.returncode is not None:
            return
        try:
            proc.terminate()
            await asyncio.wait_for(proc.wait(), timeout=3.0)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()

    def _handle_signal() -> None:
        loop.create_task(_shutdown())

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, _handle_signal)
        except (NotImplementedError, OSError):
            pass

    await proc.wait()
