import asyncio
import logging
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional
from urllib.parse import quote

import httpx

from brokk_code.workspace import resolve_workspace_dir

logger = logging.getLogger(__name__)

BUNDLED_EXECUTOR_VERSION = "0.23.0.beta2"
_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_EXECUTOR_MAIN_CLASS = "ai.brokk.executor.HeadlessExecutorMain"


class ExecutorError(Exception):
    """Custom error for ExecutorManager operations."""

    pass


def resolve_jbang_binary() -> Optional[str]:
    """Finds the jbang binary on the system."""
    # 1. Check PATH
    jbang_path = shutil.which("jbang")
    if jbang_path:
        return jbang_path

    # 2. Check common install locations
    home = Path.home()
    candidates = [
        home / ".jbang" / "bin" / "jbang",
        Path("/opt/homebrew/bin/jbang"),
        Path("/usr/local/bin/jbang"),
    ]
    if sys.platform == "win32":
        candidates.append(home / ".jbang" / "bin" / "jbang.cmd")

    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    return None


def install_jbang() -> str:
    """Installs jbang via the official script and trusts the brokk catalog."""
    is_windows = sys.platform == "win32"
    timeout_s = 120.0

    logger.info("Installing jbang...")
    try:
        if is_windows:
            cmd = [
                "powershell",
                "-Command",
                'iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"',
            ]
        else:
            cmd = ["bash", "-c", "curl -Ls https://sh.jbang.dev | bash -s - app setup"]

        # Use capture_output=True (which sets both stdout and stderr to PIPE)
        # subprocess.run handles draining the pipes to avoid deadlocks.
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_s,
        )
        if proc.returncode != 0:
            stderr_hint = f": {proc.stderr.strip()}" if proc.stderr else ""
            raise ExecutorError(f"jbang installer exited with code {proc.returncode}{stderr_hint}")

    except subprocess.TimeoutExpired:
        raise ExecutorError("jbang installation timed out after 2 minutes")
    except ExecutorError:
        raise
    except Exception as e:
        raise ExecutorError(f"Failed to run jbang installer: {e}")

    jbang_path = resolve_jbang_binary()
    if not jbang_path:
        raise ExecutorError(
            "jbang was installed but could not be found. You may need to restart your terminal."
        )

    # Trust the brokk catalog
    try:
        trust_proc = subprocess.run(
            [jbang_path, "trust", "add", "https://github.com/BrokkAi/brokk-releases"],
            capture_output=True,
            text=True,
        )
        if trust_proc.returncode != 0:
            logger.warning(
                "Failed to trust brokk catalog: %s",
                trust_proc.stderr.strip()
                if trust_proc.stderr
                else f"exit code {trust_proc.returncode}",
            )
    except Exception as e:
        logger.warning("Failed to run trust command: %s", e)

    return jbang_path


class ExecutorManager:
    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = True,
        vendor: Optional[str] = None,
        exit_on_stdin_eof: bool = False,
    ):
        self.workspace_dir = resolve_workspace_dir(workspace_dir or Path.cwd())
        self.jar_override = jar_path
        self.executor_version = executor_version
        self.use_snapshot = executor_snapshot
        self.vendor = vendor
        self.exit_on_stdin_eof = exit_on_stdin_eof
        self.auth_token = str(uuid.uuid4())
        self.base_url: Optional[str] = None
        self.session_id: Optional[str] = None
        self.resolved_jar_path: Optional[Path] = None

        self._process: Optional[asyncio.subprocess.Process] = None
        # The stdin stream for the subprocess (when created with PIPE).
        # Stored so we can close it on shutdown.
        self._stdin: Optional[asyncio.StreamWriter] = None
        self._http_client: Optional[httpx.AsyncClient] = None

    def _get_executor_args(self, exec_id: str) -> List[str]:
        """Returns the common command-line arguments for the HeadlessExecutorMain."""
        args = [
            "--exec-id",
            exec_id,
            "--listen-addr",
            "127.0.0.1:0",
            "--auth-token",
            self.auth_token,
            "--workspace-dir",
            str(self.workspace_dir),
        ]
        if self.vendor is not None and str(self.vendor).strip():
            args.extend(["--vendor", str(self.vendor).strip()])
        if self.exit_on_stdin_eof:
            args.append("--exit-on-stdin-eof")
        return args

    def _get_direct_java_command(self, jar_path: Path, exec_id: str) -> List[str]:
        """Returns the command for Direct-Java mode (explicit JAR override)."""
        cmd = [
            "java",
            "-Djava.awt.headless=true",
            "-Dapple.awt.UIElement=true",
            "-cp",
            str(jar_path),
            "ai.brokk.executor.HeadlessExecutorMain",
        ]
        cmd.extend(self._get_executor_args(exec_id))
        return cmd

    async def _get_jbang_command(self, exec_id: str) -> List[str]:
        """Returns the command for launching via jbang, installing if necessary."""
        jbang_bin = resolve_jbang_binary()
        if not jbang_bin:
            jbang_bin = install_jbang()

        version = self.executor_version or BUNDLED_EXECUTOR_VERSION
        jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-{version}.jar"
        cmd = [
            jbang_bin,
            "-R",
            "-Djava.awt.headless=true",
            "-R",
            "-Dapple.awt.UIElement=true",
            "--java",
            "21",
            "-R",
            "--enable-native-access=ALL-UNNAMED",
            "--main",
            _EXECUTOR_MAIN_CLASS,
            jar_url,
        ]
        cmd.extend(self._get_executor_args(exec_id))
        return cmd

    def _find_dev_jar(self) -> Optional[Path]:
        """Searches for a local development JAR in the project structure."""
        shadow_jar = self.workspace_dir / "app" / "build" / "libs" / "brokk.jar"
        if shadow_jar.exists():
            return shadow_jar

        curr = self.workspace_dir
        while curr != curr.parent:
            if (curr / "gradlew").exists():
                potential_jar = curr / "app" / "build" / "libs" / "brokk.jar"
                if potential_jar.exists():
                    return potential_jar
            curr = curr.parent
        return None

    async def start(self):
        """Starts the Java HeadlessExecutorMain subprocess."""
        exec_id = str(uuid.uuid4())

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

        logger.info(f"Starting executor: {' '.join(cmd)}")

        try:
            # Create subprocess with a dedicated stdin pipe so the Java
            # executor can detect parent death.
            logger.info(f"Launching executor via {cmd[0]}...")
            #
            # Implementation note / lifecycle guarantee:
            # - We intentionally open the child's stdin as a PIPE and retain the StreamWriter
            #   (self._stdin) reference. The Java HeadlessExecutorMain watches System.in for EOF
            #   and treats that as a parent-death signal, initiating a controlled shutdown.
            # - IDEs like IntelliJ will close the child's stdin when the run/debug profile is
            #   terminated or the parent process is killed. Relying on stdin EOF allows the Java
            #   executor to exit even when the Python process's 'finally' cleanup does not run,
            #   preventing lingering brokk.jar/HeadlessExecutorMain processes.
            #
            # See HeadlessExecutorMain's stdin monitor for more details.
            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
            )
            # Store the stdin stream for later closure in stop()
            # Note: Process.stdin is a StreamWriter-like object when stdin=PIPE.
            self._stdin = self._process.stdin  # type: ignore[attr-defined]
        except FileNotFoundError:
            binary = cmd[0]
            if "jbang" in binary.lower():
                raise ExecutorError(
                    f"jbang executable not found at '{binary}'. "
                    "Please ensure jbang is installed or provide a local JAR with --jar."
                )
            else:
                raise ExecutorError(
                    f"Java executable not found ('{binary}'). "
                    "Please ensure JDK 21+ is installed and 'java' is in your PATH."
                )

        # Parse stdout for the listening URL
        port = None
        output_lines: List[str] = []
        # Use a generous timeout per-line to accommodate first-time JAR downloads
        # (JBang may be silent for 60+ seconds while fetching a large remote JAR).
        while True:
            try:
                line_bytes = await asyncio.wait_for(self._process.stdout.readline(), timeout=120.0)
            except asyncio.TimeoutError:
                break
            if not line_bytes:
                break
            line = line_bytes.decode().strip()
            logger.debug(f"Executor: {line}")
            output_lines.append(line)

            if "Executor listening on http://" in line:
                # Line format: "Executor listening on http://127.0.0.1:PORT"
                try:
                    port = int(line.split(":")[-1])
                    break
                except (ValueError, IndexError):
                    continue

        if port is None:
            await self.stop()
            output_summary = "\n".join(output_lines[-30:]) if output_lines else "(no output)"
            raise ExecutorError(
                f"Failed to extract port from executor output.\nLast output:\n{output_summary}"
            )

        self.base_url = f"http://127.0.0.1:{port}"
        self._http_client = httpx.AsyncClient(
            base_url=self.base_url,
            headers={"Authorization": f"Bearer {self.auth_token}"},
            timeout=30.0,
        )
        logger.info(f"Executor started at {self.base_url}")

    async def get_health_live(self) -> Dict[str, Any]:
        """Fetches unauthenticated liveness info (version, protocol, execId)."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        # Use a fresh client without Auth header for unauthenticated endpoint check
        async with httpx.AsyncClient(base_url=self.base_url, timeout=5.0) as client:
            resp = await client.get("/health/live")
            resp.raise_for_status()
            return resp.json()

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        """Polls /health/ready until the executor is ready."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        start_time = asyncio.get_event_loop().time()
        while (asyncio.get_event_loop().time() - start_time) < timeout:
            try:
                resp = await self._http_client.get("/health/ready")
                if resp.status_code == 200:
                    return True
            except httpx.HTTPError:
                pass
            await asyncio.sleep(0.5)
        return False

    async def create_session(self, name: str = "TUI Session") -> str:
        """Creates a new session and returns the sessionId."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/sessions", json={"name": name})
            resp.raise_for_status()
            data = resp.json()
            self.session_id = data["sessionId"]
            return self.session_id
        except httpx.HTTPError as e:
            raise ExecutorError(f"Failed to create session: {e}")

    async def download_session_zip(self, session_id: str) -> bytes:
        """Downloads the ZIP archive for a specific session."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get(f"/v1/sessions/{session_id}")
            resp.raise_for_status()
            return resp.content
        except httpx.HTTPError as e:
            raise ExecutorError(f"Failed to download session {session_id}: {e}")

    async def import_session_zip(self, zip_bytes: bytes, session_id: Optional[str] = None) -> str:
        """
        Imports a session ZIP archive.
        If session_id is provided, it is sent via X-Session-Id header.
        Returns the sessionId from the response.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        headers = {"Content-Type": "application/zip"}
        if session_id:
            headers["X-Session-Id"] = session_id

        try:
            resp = await self._http_client.put("/v1/sessions", content=zip_bytes, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            new_id = data["sessionId"]
            self.session_id = new_id
            return new_id
        except httpx.HTTPError as e:
            raise ExecutorError(f"Failed to import session: {e}")

    async def submit_job(
        self,
        task_input: str,
        planner_model: str,
        code_model: Optional[str] = None,
        reasoning_level: Optional[str] = None,
        reasoning_level_code: Optional[str] = None,
        mode: str = "LUTZ",
        tags: Optional[Dict[str, str]] = None,
        session_id: Optional[str] = None,
        auto_commit: bool = True,
    ) -> str:
        """Submits a new job to the executor.

        Backwards-compatible: session_id is optional. If provided (or if
        self.session_id was previously set via create_session/import_session_zip),
        the header 'X-Session-Id' will be included on the POST to /v1/jobs.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        job_tags = (tags or {}).copy()
        if "mode" not in job_tags:
            job_tags["mode"] = mode

        payload = {
            "taskInput": task_input,
            "plannerModel": planner_model,
            "autoCommit": auto_commit,
            "autoCompress": True,
            "tags": job_tags,
        }

        # Add optional fields only if they are set
        if code_model:
            payload["codeModel"] = code_model
        if reasoning_level:
            payload["reasoningLevel"] = reasoning_level
        if reasoning_level_code:
            payload["reasoningLevelCode"] = reasoning_level_code

        headers = {"Idempotency-Key": str(uuid.uuid4())}
        # Prefer explicit argument, fall back to manager-level session_id if present.
        effective_session_id = session_id or self.session_id
        if effective_session_id:
            headers["X-Session-Id"] = effective_session_id

        resp = await self._http_client.post("/v1/jobs", json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["jobId"]

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        """
        Streams events for a specific job until it reaches a terminal state
        with adaptive polling.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        after_seq = -1
        terminal_states = {"COMPLETED", "FAILED", "CANCELLED"}

        # Polling configuration
        min_sleep = 0.05
        max_sleep = 0.5
        current_sleep = min_sleep

        last_status_check = -float("inf")
        status_interval = 2.0  # Seconds between status checks when events are flowing
        state = "QUEUED"

        while True:
            now = asyncio.get_event_loop().time()

            # 1. Check job status if enough time has passed
            if now - last_status_check > status_interval:
                status_resp = await self._http_client.get(f"/v1/jobs/{job_id}")
                status_resp.raise_for_status()
                status_data = status_resp.json()
                state = status_data.get("state", "QUEUED")
                last_status_check = now

            # 2. Fetch events
            events_url = f"/v1/jobs/{job_id}/events?after={after_seq}&limit=100"
            events_resp = await self._http_client.get(events_url)
            events_resp.raise_for_status()
            events_data = events_resp.json()

            events = events_data.get("events", [])
            after_seq = events_data.get("nextAfter", after_seq)

            for event in events:
                yield event

            # 3. Check for termination
            if state in terminal_states:
                # If we just hit a terminal state, check one last time for any
                # race-condition events.
                if not events:
                    break
                # If we did get events, we continue one more loop without sleeping
                # to clear the buffer.

            # 4. Adaptive sleep
            if events:
                # Events are flowing, stay aggressive
                current_sleep = min_sleep
                # We don't sleep at all if we got a full batch, to catch up faster
                if len(events) < 100:
                    await asyncio.sleep(min_sleep)
            else:
                # No events, back off and check status on next loop if we haven't recently
                if state in terminal_states:
                    break

                await asyncio.sleep(current_sleep)
                current_sleep = min(max_sleep, current_sleep * 2)
                # Force status check on next loop if we are idling
                last_status_check = 0.0

    async def _handle_http_error(self, e: httpx.HTTPError, endpoint: str) -> None:
        """Centralized error handling for executor HTTP calls."""
        response = getattr(e, "response", None)
        status = getattr(response, "status_code", None)

        if status == 404:
            # Perform best-effort diagnostic to check version/protocol
            diag_info = ""
            try:
                # Use base_url directly to avoid recursion if _http_client logic is complex
                diag_resp = await self._http_client.get("/v1/executor")
                if diag_resp.status_code == 200:
                    data = diag_resp.json()
                    ver = data.get("version", "unknown")
                    p_ver = data.get("protocolVersion", "unknown")
                    diag_info = f" (Executor Version: {ver}, Protocol: {p_ver})"
            except Exception:
                pass
            raise ExecutorError(
                f"Endpoint {endpoint} not found (404). "
                f"Your executor version may be too old{diag_info}."
            ) from e

        status_str = str(status) if status is not None else "N/A"
        raise ExecutorError(
            f"Failed GET {endpoint} (status={status_str}): {type(e).__name__}: {e}"
        ) from e

    async def get_context(self) -> Dict[str, Any]:
        """Returns the current session context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/context", params={"tokens": "true"})
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/context")
            raise  # Should not be reached

    async def get_context_fragment(self, fragment_id: str) -> Dict[str, Any]:
        """Returns embedded-resource content for a context fragment by ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not fragment_id or not fragment_id.strip():
            raise ExecutorError("fragment_id must not be blank")

        endpoint = f"/v1/context/fragments/{quote(fragment_id, safe='')}"
        try:
            resp = await self._http_client.get(endpoint)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, endpoint)
            raise  # Should not be reached

    async def get_models(self) -> Dict[str, Any]:
        """Returns runtime-available model information from the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/models")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/models")
            raise  # Should not be reached

    async def get_completions(self, query: str, limit: int = 20) -> Dict[str, Any]:
        """Returns file/symbol completions for a query string."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        query_text = query.strip()
        if not query_text:
            return {"completions": []}

        bounded_limit = max(1, min(limit, 50))
        try:
            resp = await self._http_client.get(
                "/v1/completions", params={"query": query_text, "limit": str(bounded_limit)}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/completions")
            raise  # Should not be reached

    async def drop_context_fragments(self, fragment_ids: List[str]) -> Dict[str, Any]:
        """Drops specific fragments from context by ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not fragment_ids:
            raise ExecutorError("fragment_ids must not be empty")

        try:
            resp = await self._http_client.post(
                "/v1/context/drop", json={"fragmentIds": fragment_ids}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/drop (status={status}): {e}") from e

    async def set_context_fragment_pinned(self, fragment_id: str, pinned: bool) -> Dict[str, Any]:
        """Sets pin state for a specific context fragment."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post(
                "/v1/context/pin", json={"fragmentId": fragment_id, "pinned": pinned}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/pin (status={status}): {e}") from e

    async def set_context_fragment_readonly(
        self, fragment_id: str, readonly: bool
    ) -> Dict[str, Any]:
        """Sets readonly state for a specific editable context fragment."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post(
                "/v1/context/readonly", json={"fragmentId": fragment_id, "readonly": readonly}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/readonly (status={status}): {e}") from e

    async def compress_context_history(self) -> Dict[str, Any]:
        """Requests history compression for the current context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/context/compress-history")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(
                f"Failed POST /v1/context/compress-history (status={status}): {e}"
            ) from e

    async def clear_context_history(self) -> Dict[str, Any]:
        """Clears history fragments from current context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/context/clear-history")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(
                f"Failed POST /v1/context/clear-history (status={status}): {e}"
            ) from e

    async def drop_all_context(self) -> Dict[str, Any]:
        """Drops all context fragments."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/context/drop-all")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/drop-all (status={status}): {e}") from e

    async def get_tasklist(self) -> Dict[str, Any]:
        """Returns the current task list data."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/tasklist")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/tasklist")
            raise  # Should not be reached

    async def add_context_files(self, relative_paths: List[str]) -> Dict[str, Any]:
        """Adds files to context by workspace-relative paths."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not relative_paths:
            return {"added": []}
        try:
            resp = await self._http_client.post(
                "/v1/context/files", json={"relativePaths": relative_paths}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/files (status={status}): {e}") from e

    async def add_context_classes(self, class_names: List[str]) -> Dict[str, Any]:
        """Adds class summaries to context by fully-qualified class names."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not class_names:
            return {"added": []}
        try:
            resp = await self._http_client.post(
                "/v1/context/classes", json={"classNames": class_names}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/classes (status={status}): {e}") from e

    async def add_context_methods(self, method_names: List[str]) -> Dict[str, Any]:
        """Adds method sources to context by fully-qualified method names."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not method_names:
            return {"added": []}
        try:
            resp = await self._http_client.post(
                "/v1/context/methods", json={"methodNames": method_names}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/context/methods (status={status}): {e}") from e

    async def set_tasklist(self, tasklist_data: Dict[str, Any]) -> Dict[str, Any]:
        """Replaces the current task list data."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/tasklist", json=tasklist_data)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/tasklist")
            raise  # Should not be reached

    async def cancel_job(self, job_id: str):
        """Cancels an active job."""
        if not self._http_client:
            return
        try:
            await self._http_client.post(f"/v1/jobs/{job_id}/cancel")
        except httpx.HTTPError:
            pass

    def check_alive(self) -> bool:
        """Checks if the executor subprocess is still running."""
        return self._process is not None and self._process.returncode is None

    async def stop(self):
        """Gracefully stops the executor and cleans up resources."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None

        if self._process:
            logger.info("Stopping executor subprocess...")
            # First, attempt to close stdin so the child process can
            # observe EOF and exit if it chooses.
            if self._stdin is not None:
                try:
                    # StreamWriter.close() is synchronous; wait for wait_closed() if available.
                    #
                    # Closing the child's stdin is the preferred first step for shutdown because
                    # HeadlessExecutorMain treats stdin EOF as a signal to perform a controlled
                    # shutdown. This helps ensure the Java process exits even if the Python
                    # interpreter is killed abruptly by the IDE and its own cleanup handlers
                    # do not run.
                    self._stdin.close()
                    wait_closed = getattr(self._stdin, "wait_closed", None)
                    if callable(wait_closed):
                        try:
                            await wait_closed()
                        except (BrokenPipeError, ConnectionResetError):
                            # Child already gone or closed the pipe;
                            # ignore these expected conditions.
                            pass
                        except Exception:
                            logger.exception("Unexpected error while waiting for stdin to close")
                    # Clear reference
                except (BrokenPipeError, ConnectionResetError):
                    # Expected if the child has already exited or closed the pipe.
                    pass
                except Exception:
                    logger.exception("Unexpected error while closing subprocess stdin")
                finally:
                    self._stdin = None

            try:
                self._process.terminate()
                try:
                    await asyncio.wait_for(self._process.wait(), timeout=3.0)
                except asyncio.TimeoutError:
                    logger.warning("Executor didn't terminate in time, killing...")
                    self._process.kill()
                    await self._process.wait()
            except ProcessLookupError:
                pass
            self._process = None

        logger.info("Executor stopped")
