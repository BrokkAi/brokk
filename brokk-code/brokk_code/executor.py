import asyncio
import contextlib
import json
import logging
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any, AsyncIterator, Dict, Iterator, List, Optional
from urllib.parse import quote

import httpx

from brokk_code.workspace import resolve_workspace_dir

logger = logging.getLogger(__name__)

BUNDLED_EXECUTOR_VERSION = "0.23.5.beta6"
_EXECUTOR_JAR_BASE_URL = "https://github.com/BrokkAi/brokk-releases/releases/download"
_EXECUTOR_MAIN_CLASS = "ai.brokk.executor.HeadlessExecutorMain"
_READY_SENTINEL = "Executor listening on http://"
_STARTUP_LINE_TIMEOUT = 120.0
_JOB_CREATE_TIMEOUT_SECONDS = 120.0

_BROKK_TRUST_URLS = [
    "https://github.com/BrokkAi/brokk-releases",
    "https://github.com/BrokkAi/brokk-releases/releases/download/",
]
_JBANG_SETUP_LOCK_PATH: Optional[Path] = None
_DEFAULT_JBANG_TIMEOUT_SECONDS = 300.0


def _parse_jbang_timeout() -> float:
    raw = os.environ.get("BROKK_JBANG_TIMEOUT")
    if raw is None:
        return _DEFAULT_JBANG_TIMEOUT_SECONDS
    try:
        value = float(raw)
    except ValueError:
        logger.warning(
            "BROKK_JBANG_TIMEOUT=%r is not a number, using default %ss",
            raw,
            int(_DEFAULT_JBANG_TIMEOUT_SECONDS),
        )
        return _DEFAULT_JBANG_TIMEOUT_SECONDS
    if value <= 0:
        logger.warning(
            "BROKK_JBANG_TIMEOUT=%s must be positive, using default %ss",
            raw,
            int(_DEFAULT_JBANG_TIMEOUT_SECONDS),
        )
        return _DEFAULT_JBANG_TIMEOUT_SECONDS
    return value


_JBANG_SETUP_LOCK_TIMEOUT_SECONDS = _parse_jbang_timeout()


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
    if sys.platform == "win32":
        candidates = [
            home / ".jbang" / "bin" / "jbang.cmd",
            home / ".jbang" / "bin" / "jbang.exe",
            home / ".jbang" / "bin" / "jbang",
        ]
    else:
        candidates = [
            home / ".jbang" / "bin" / "jbang",
            Path("/opt/homebrew/bin/jbang"),
            Path("/usr/local/bin/jbang"),
        ]

    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    return None


def _is_jbang_trusted() -> bool:
    """Returns True if both brokk URLs appear in ~/.jbang/trusted-sources.json."""
    trusted_sources_path = Path.home() / ".jbang" / "trusted-sources.json"
    try:
        content = trusted_sources_path.read_text(encoding="utf-8")
        data = json.loads(content)
        sources = data.get("trustedSources", [])
        return all(url in sources for url in _BROKK_TRUST_URLS)
    except Exception:
        return False


def _is_pid_alive(pid: int) -> bool:
    """Best-effort process liveness check."""
    if pid <= 0:
        return False

    if sys.platform == "win32":
        import ctypes

        process_query_limited_information = 0x1000
        still_active = 259
        handle = ctypes.windll.kernel32.OpenProcess(process_query_limited_information, False, pid)
        if not handle:
            return False
        try:
            exit_code = ctypes.c_ulong()
            ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
            return bool(ok) and exit_code.value == still_active
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)

    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


@contextlib.contextmanager
def _jbang_setup_lock() -> Iterator[None]:
    """File-based lock with PID stale detection."""
    lock_path = _JBANG_SETUP_LOCK_PATH or (Path.home() / ".jbang" / "brokk-setup.lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)

    deadline = time.monotonic() + _JBANG_SETUP_LOCK_TIMEOUT_SECONDS
    acquired = False

    while not acquired:
        try:
            fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            try:
                os.write(fd, str(os.getpid()).encode())
            finally:
                os.close(fd)
            acquired = True
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise ExecutorError("Could not acquire jbang setup lock")
            try:
                pid_text = lock_path.read_text(encoding="utf-8").strip()
                pid = int(pid_text)
                if _is_pid_alive(pid):
                    # Process is alive, wait and retry
                    time.sleep(0.5)
                else:
                    # Process is dead, remove stale lock and retry
                    lock_path.unlink(missing_ok=True)
            except Exception:
                time.sleep(0.5)

    try:
        yield
    finally:
        lock_path.unlink(missing_ok=True)


def ensure_jbang_ready() -> str:
    """Single entry point: ensures jbang is installed and brokk URLs are trusted.
    Idempotent, concurrency-safe. Returns the jbang binary path."""
    # Fast path: check without locking
    jbang_path = resolve_jbang_binary()
    if jbang_path and _is_jbang_trusted():
        return jbang_path

    # Slow path: acquire lock and do the work
    with _jbang_setup_lock():
        # Double-check after acquiring lock
        jbang_path = resolve_jbang_binary()
        if jbang_path and _is_jbang_trusted():
            return jbang_path

        # Install jbang if needed
        if not jbang_path:
            logger.info("Installing jbang...")
            is_windows = sys.platform == "win32"
            try:
                if is_windows:
                    cmd = [
                        "powershell",
                        "-Command",
                        'iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"',
                    ]
                else:
                    if not shutil.which("curl"):
                        raise ExecutorError(
                            "curl is required to install jbang but was not found. "
                            "Please install it (e.g. 'sudo apt install curl') and try again."
                        )
                    cmd = [
                        "bash",
                        "-c",
                        "set -o pipefail; curl -Ls https://sh.jbang.dev | bash -s - app setup",
                    ]

                proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=_JBANG_SETUP_LOCK_TIMEOUT_SECONDS,
                )
                if proc.returncode != 0:
                    stderr_hint = f": {proc.stderr.strip()}" if proc.stderr else ""
                    raise ExecutorError(
                        f"jbang installer exited with code {proc.returncode}{stderr_hint}"
                    )
            except subprocess.TimeoutExpired:
                timeout_s = int(_JBANG_SETUP_LOCK_TIMEOUT_SECONDS)
                raise ExecutorError(
                    f"jbang installation timed out after {timeout_s}s. "
                    "Set BROKK_JBANG_TIMEOUT to a higher value."
                )
            except ExecutorError:
                raise
            except Exception as e:
                raise ExecutorError(f"Failed to run jbang installer: {e}")

            jbang_path = resolve_jbang_binary()
            if not jbang_path:
                raise ExecutorError(
                    "jbang was installed but could not be found. "
                    "You may need to restart your terminal."
                )

        # Add trust for brokk URLs
        for url in _BROKK_TRUST_URLS:
            try:
                trust_proc = subprocess.run(
                    [jbang_path, "trust", "add", url],
                    capture_output=True,
                    text=True,
                )
                if trust_proc.returncode != 0:
                    logger.warning(
                        "Failed to trust %s: %s",
                        url,
                        trust_proc.stderr.strip()
                        if trust_proc.stderr
                        else f"exit code {trust_proc.returncode}",
                    )
            except Exception as e:
                logger.warning("Failed to run trust command for %s: %s", url, e)

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
        brokk_api_key: Optional[str] = None,
    ):
        self.workspace_dir = resolve_workspace_dir(workspace_dir or Path.cwd())
        self.jar_override = jar_path
        self.executor_version = executor_version
        self.use_snapshot = executor_snapshot
        self.vendor = vendor
        self.exit_on_stdin_eof = exit_on_stdin_eof
        self.brokk_api_key = brokk_api_key
        self.auth_token = str(uuid.uuid4())
        self.base_url: Optional[str] = None
        self.session_id: Optional[str] = None
        self.resolved_jar_path: Optional[Path] = None
        self.shutdown_context: Optional[str] = None
        self.environment_type: str = "tui"

        self._process: Optional[asyncio.subprocess.Process] = None
        # The stdin stream for the subprocess (when created with PIPE).
        # Stored so we can close it on shutdown.
        self._stdin: Optional[asyncio.StreamWriter] = None
        self._http_client: Optional[httpx.AsyncClient] = None

    @property
    def _main_class(self) -> str:
        return _EXECUTOR_MAIN_CLASS

    def _get_environment_flag(self) -> str:
        """Return the appropriate environment JVM flag based on environment_type."""
        if self.environment_type == "zed":
            return "-Dbrokk.zed=true"
        elif self.environment_type == "intellij":
            return "-Dbrokk.intellij=true"
        else:
            return "-Dbrokk.tui=true"

    def _parse_port_from_line(self, line: str) -> Optional[int]:
        """Extract the port number from a startup log line, or return None."""
        if _READY_SENTINEL in line:
            try:
                return int(line.split(":")[-1])
            except (ValueError, IndexError):
                return None
        return None

    async def _await_ready(self, exec_id: str) -> int:
        """
        Read subprocess stdout until a port is found via _parse_port_from_line.
        Returns the port number. Raises ExecutorError if none is found.
        Subclasses may override to use a different readiness strategy.
        """
        output_lines: List[str] = []
        while True:
            try:
                line_bytes = await asyncio.wait_for(
                    self._process.stdout.readline(), timeout=_STARTUP_LINE_TIMEOUT
                )
            except asyncio.TimeoutError:
                break
            if not line_bytes:
                break
            line = line_bytes.decode().strip()
            logger.debug("Executor: %s", line)
            output_lines.append(line)
            parsed = self._parse_port_from_line(line)
            if parsed is not None:
                return parsed

        output_summary = "\n".join(output_lines[-30:]) if output_lines else "(no output)"
        raise ExecutorError(
            f"Failed to extract port from executor output.\nLast output:\n{output_summary}"
        )

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
        if self.brokk_api_key is not None and str(self.brokk_api_key).strip():
            args.extend(["--brokk-api-key", str(self.brokk_api_key).strip()])
        if self.exit_on_stdin_eof:
            args.append("--exit-on-stdin-eof")
        return args

    def _get_direct_java_command(self, jar_path: Path, exec_id: str) -> List[str]:
        """Returns the command for Direct-Java mode (explicit JAR override)."""
        env_flag = self._get_environment_flag()
        cmd = [
            "java",
            env_flag,
            "-Djava.awt.headless=true",
            "-Dapple.awt.UIElement=true",
            "-cp",
            str(jar_path),
            self._main_class,
        ]
        cmd.extend(self._get_executor_args(exec_id))
        return cmd

    async def _get_jbang_command(self, exec_id: str) -> List[str]:
        """Returns the command for launching via jbang, installing if necessary."""
        jbang_bin = await asyncio.to_thread(ensure_jbang_ready)

        version = self.executor_version or BUNDLED_EXECUTOR_VERSION
        jar_url = f"{_EXECUTOR_JAR_BASE_URL}/{version}/brokk-{version}.jar"
        env_flag = self._get_environment_flag()
        cmd = [
            jbang_bin,
            "--java",
            "21",
            "-R",
            (
                f"{env_flag} "
                "-Djava.awt.headless=true "
                "-Dapple.awt.UIElement=true "
                "--enable-native-access=ALL-UNNAMED"
            ),
            "--main",
            self._main_class,
            jar_url,
        ]
        cmd.extend(self._get_executor_args(exec_id))
        return cmd

    async def start(self):
        """Starts the Java HeadlessExecutorMain subprocess."""
        exec_id = str(uuid.uuid4())

        if self.jar_override:
            self.resolved_jar_path = self.jar_override
            print(f"Running in dev mode with JAR: {self.jar_override}")
            cmd = self._get_direct_java_command(self.jar_override, exec_id)
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
                cwd=str(self.workspace_dir),
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

        try:
            port = await self._await_ready(exec_id)
        except ExecutorError:
            await self.stop()
            raise

        self.base_url = f"http://127.0.0.1:{port}"
        self._http_client = self._make_http_client(self.base_url)
        logger.info("Executor started at %s", self.base_url)

    def _make_http_client(self, base_url: str) -> httpx.AsyncClient:
        """Creates the HTTP client used to talk to the subprocess."""
        return httpx.AsyncClient(
            base_url=base_url,
            headers={"Authorization": f"Bearer {self.auth_token}"},
            timeout=30.0,
        )

    async def get_health_live(self) -> Dict[str, Any]:
        """Fetches unauthenticated liveness info (version, protocol, execId)."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        # Use a fresh client without Auth header for unauthenticated endpoint check
        async with httpx.AsyncClient(base_url=self.base_url, timeout=5.0) as client:
            resp = await client.get("/health/live")
            resp.raise_for_status()
            return resp.json()

    async def wait_live(self, timeout: float = 30.0) -> bool:
        """Polls /health/live until the executor is live."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        start_time = asyncio.get_event_loop().time()
        while (asyncio.get_event_loop().time() - start_time) < timeout:
            try:
                resp = await self._http_client.get("/health/live")
                if resp.status_code == 200:
                    return True
            except httpx.HTTPError:
                pass
            await asyncio.sleep(0.5)
        return False

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        """Compatibility alias for wait_live()."""
        return await self.wait_live(timeout=timeout)

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

    async def list_sessions(self) -> Dict[str, Any]:
        """Lists known sessions and the current active session ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/sessions")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/sessions")
            raise  # Should not be reached

    async def switch_session(self, session_id: str) -> Dict[str, Any]:
        """Switches the active session by ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not session_id or not session_id.strip():
            raise ExecutorError("session_id must not be blank")

        try:
            resp = await self._http_client.post(
                "/v1/sessions/switch", json={"sessionId": session_id}
            )
            resp.raise_for_status()
            self.session_id = session_id
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/sessions/switch (status={status}): {e}") from e

    async def rename_session(self, session_id: str, name: str) -> Dict[str, Any]:
        """Renames a session by ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not session_id or not session_id.strip():
            raise ExecutorError("session_id must not be blank")
        if not name or not name.strip():
            raise ExecutorError("name must not be blank")

        try:
            resp = await self._http_client.post(
                "/v1/sessions/rename", json={"sessionId": session_id, "name": name}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/sessions/rename")
            raise  # Should not be reached

    async def delete_session(self, session_id: str) -> Dict[str, Any]:
        """Deletes a session by ID."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not session_id or not session_id.strip():
            raise ExecutorError("session_id must not be blank")

        try:
            resp = await self._http_client.post(
                "/v1/sessions/delete", json={"sessionId": session_id}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/sessions/delete (status={status}): {e}") from e

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

    async def submit_pr_review_job(
        self,
        planner_model: str,
        github_token: str,
        owner: str,
        repo: str,
        pr_number: int,
        severity_threshold: str | None = None,
    ) -> str:
        """Submits a PR review job to the executor.

        Args:
            planner_model: The LLM model to use for the review.
            github_token: GitHub API token for accessing the PR.
            owner: GitHub repository owner.
            repo: GitHub repository name.
            pr_number: The pull request number to review.
            severity_threshold: Minimum severity for inline comments
                (CRITICAL, HIGH, MEDIUM, LOW). Defaults to HIGH on the server.

        Returns:
            The jobId of the created job.

        Raises:
            ExecutorError: If the executor is not started or the request fails.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: dict = {
            "plannerModel": planner_model,
            "githubToken": github_token,
            "owner": owner,
            "repo": repo,
            "prNumber": pr_number,
        }
        if severity_threshold:
            payload["severityThreshold"] = severity_threshold

        headers = {"Idempotency-Key": str(uuid.uuid4())}

        try:
            resp = await self._http_client.post(
                "/v1/jobs/pr-review",
                json=payload,
                headers=headers,
                timeout=_JOB_CREATE_TIMEOUT_SECONDS,
            )
            resp.raise_for_status()
            data = resp.json()
            response_session_id = data.get("sessionId")
            if isinstance(response_session_id, str) and response_session_id.strip():
                self.session_id = response_session_id
            return data["jobId"]
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/jobs/pr-review (status={status}): {e}") from e

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
        skip_verification: Optional[bool] = None,
        max_issue_fix_attempts: Optional[int] = None,
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
        if skip_verification is not None:
            payload["skipVerification"] = skip_verification
        if max_issue_fix_attempts is not None:
            payload["maxIssueFixAttempts"] = max_issue_fix_attempts

        headers = {"Idempotency-Key": str(uuid.uuid4())}
        # Prefer explicit argument, fall back to manager-level session_id if present.
        effective_session_id = session_id or self.session_id
        if effective_session_id:
            headers["X-Session-Id"] = effective_session_id

        resp = await self._http_client.post(
            "/v1/jobs",
            json=payload,
            headers=headers,
            timeout=_JOB_CREATE_TIMEOUT_SECONDS,
        )
        resp.raise_for_status()
        data = resp.json()
        response_session_id = data.get("sessionId")
        if isinstance(response_session_id, str) and response_session_id.strip():
            self.session_id = response_session_id
        return data["jobId"]

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
        terminal_empty_polls = 0
        max_terminal_empty_polls = 3

        while True:
            now = asyncio.get_event_loop().time()

            # 1. Check job status if enough time has passed
            if terminal_empty_polls == 0 and now - last_status_check > status_interval:
                status_resp = await self._http_client.get(f"/v1/jobs/{job_id}")
                status_resp.raise_for_status()
                status_data = status_resp.json()
                if isinstance(status_data, dict):
                    state = status_data.get("state", "QUEUED")
                last_status_check = now

            # 2. Fetch events
            events_url = f"/v1/jobs/{job_id}/events?after={after_seq}&limit=100"
            events_resp = await self._http_client.get(events_url)
            events_resp.raise_for_status()
            events_data = events_resp.json()

            if not isinstance(events_data, dict):
                await asyncio.sleep(current_sleep)
                continue

            events = events_data.get("events", [])
            after_seq = events_data.get("nextAfter", after_seq)

            for event in events:
                yield event

            # 3. Check for termination
            if state in terminal_states:
                if events:
                    terminal_empty_polls = 0
                else:
                    # The status endpoint can report terminal state slightly before
                    # the final events are visible. Drain a few extra empty event polls
                    # before exiting so callers do not miss terminal notifications.
                    terminal_empty_polls += 1
                    if terminal_empty_polls >= max_terminal_empty_polls:
                        break
                    await asyncio.sleep(min_sleep)
                    continue

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

        # Final status fetch to guarantee we emit the true terminal state
        try:
            final_resp = await self._http_client.get(f"/v1/jobs/{job_id}")
            final_resp.raise_for_status()
            final_data = final_resp.json()
            if isinstance(final_data, dict):
                state = final_data.get("state", state)
        except Exception:
            pass  # fall back to last known state

        yield {"type": "STATE_CHANGE", "data": {"state": state}}

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

        # Try to extract the server's error message from the response body
        server_message = ""
        if response is not None:
            try:
                body = response.json()
                if isinstance(body, dict):
                    msg = body.get("message", "")
                    details = body.get("details", "")
                    if isinstance(msg, str) and isinstance(details, str):
                        if msg and details:
                            server_message = f"{msg}: {details}"
                        elif msg:
                            server_message = msg
                        elif details:
                            server_message = details
            except Exception:
                pass

        status_str = str(status) if status is not None else "N/A"
        raw_method = getattr(getattr(e, "request", None), "method", None)
        method = raw_method if isinstance(raw_method, str) else "?"
        if server_message:
            raise ExecutorError(
                f"Failed {method} {endpoint} (status={status_str}): {server_message}"
            ) from e
        raise ExecutorError(
            f"Failed {method} {endpoint} (status={status_str}): {type(e).__name__}: {e}"
        ) from e

    async def get_session_costs(self) -> Dict[str, Any]:
        """Returns the current session's cost breakdown from the ledger."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/session/costs")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/session/costs")
            raise  # Should not be reached

    async def get_context(self) -> Dict[str, Any]:
        """Returns the current session context, including tokens and totalCost."""
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

    async def validate_brokk_auth(self) -> Dict[str, Any]:
        """Returns Brokk auth/account validation details for the current executor key."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/auth/validate")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/auth/validate")
            raise  # Should not be reached

    async def get_model_config(self) -> Dict[str, Any]:
        """Returns the persisted CODE and ARCHITECT model configs from the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/model-config")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/model-config")
            raise  # Should not be reached

    async def set_model_config(
        self,
        role: str,
        model: str,
        reasoning: Optional[str] = None,
        tier: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Updates a persisted CODE or ARCHITECT model config in the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: Dict[str, Any] = {"role": role, "model": model}
        if reasoning:
            payload["reasoning"] = reasoning
        if tier:
            payload["tier"] = tier

        try:
            resp = await self._http_client.post("/v1/model-config", json=payload)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/model-config")
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

    async def get_conversation(self) -> Dict[str, Any]:
        """Returns displayable conversation entries for the current session context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/context/conversation")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/context/conversation")
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

    async def start_openai_oauth(self) -> Dict[str, Any]:
        """Initiates the OpenAI OAuth flow."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/openai/oauth/start")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            status = getattr(getattr(e, "response", None), "status_code", "N/A")
            raise ExecutorError(f"Failed POST /v1/openai/oauth/start (status={status}): {e}") from e

    async def get_openai_oauth_status(self) -> Dict[str, Any]:
        """Checks the connection status of OpenAI OAuth."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/openai/oauth/status")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/openai/oauth/status")
            raise  # Should not be reached

    async def start_github_oauth(self) -> Dict[str, Any]:
        """Initiates the GitHub OAuth flow (Device Flow)."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.post("/v1/github/oauth/start")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/github/oauth/start")
            raise  # Should not be reached

    async def get_github_oauth_status(self) -> Dict[str, Any]:
        """Checks the connection status of GitHub OAuth."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.get("/v1/github/oauth/status")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/github/oauth/status")
            raise  # Should not be reached

    async def disconnect_github_oauth(self) -> Dict[str, Any]:
        """Revokes the GitHub OAuth authorization."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        try:
            resp = await self._http_client.delete("/v1/github/oauth/authorization")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/github/oauth/authorization")
            raise  # Should not be reached

    async def commit_context(self, message: Optional[str] = None) -> Dict[str, Any]:
        """Commits current changes with an optional message.

        If message is None or blank, the executor will generate a commit message.
        Returns commit metadata including commitId and firstLine on success,
        or {"status": "no_changes"} if there are no uncommitted changes.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: Dict[str, Any] = {}
        if message is not None:
            payload["message"] = message

        try:
            resp = await self._http_client.post("/v1/repo/commit", json=payload)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/repo/commit")
            raise  # Should not be reached

    async def pr_suggest(
        self,
        source_branch: Optional[str] = None,
        target_branch: Optional[str] = None,
        github_token: Optional[str] = None,
        session_ids: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        """Suggests PR title and description based on branch diff.

        Args:
            source_branch: Source branch (defaults to current branch on server)
            target_branch: Target branch (defaults to default branch on server)
            github_token: Optional GitHub token for authentication
            session_ids: Optional list of session UUIDs to include in context

        Returns:
            Dict with title, description, usedCommitMessages, sourceBranch, targetBranch
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: Dict[str, Any] = {}
        if source_branch:
            payload["sourceBranch"] = source_branch
        if target_branch:
            payload["targetBranch"] = target_branch
        if session_ids:
            payload["sessionIds"] = session_ids

        headers: Dict[str, str] = {}
        if github_token:
            headers["X-Github-Token"] = github_token

        try:
            resp = await self._http_client.post(
                "/v1/repo/pr/suggest",
                json=payload,
                headers=headers if headers else None,
                timeout=120.0,
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/repo/pr/suggest")
            raise  # Should not be reached

    async def pr_sessions(
        self,
        source_branch: Optional[str] = None,
        target_branch: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Fetches overlapping sessions for a PR based on branch diff.

        Args:
            source_branch: Source branch (defaults to current branch on server)
            target_branch: Target branch (defaults to default branch on server)

        Returns:
            Dict with sessions (list of {id, name, taskCount}), sourceBranch, targetBranch
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: Dict[str, Any] = {}
        if source_branch:
            payload["sourceBranch"] = source_branch
        if target_branch:
            payload["targetBranch"] = target_branch

        try:
            resp = await self._http_client.post("/v1/repo/pr/sessions", json=payload)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/repo/pr/sessions")
            raise  # Should not be reached

    async def pr_create(
        self,
        title: str,
        body: str,
        source_branch: Optional[str] = None,
        target_branch: Optional[str] = None,
        github_token: Optional[str] = None,
        session_ids: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        """Creates a pull request.

        Args:
            title: PR title (required)
            body: PR body/description (required)
            source_branch: Source branch (defaults to current branch on server)
            target_branch: Target branch (defaults to default branch on server)
            github_token: Optional GitHub token for authentication
            session_ids: Optional list of session UUIDs to embed in PR body

        Returns:
            Dict with url, sourceBranch, targetBranch
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: Dict[str, Any] = {"title": title, "body": body}
        if source_branch:
            payload["sourceBranch"] = source_branch
        if target_branch:
            payload["targetBranch"] = target_branch
        if session_ids:
            payload["sessionIds"] = session_ids

        headers: Dict[str, str] = {}
        if github_token:
            headers["X-Github-Token"] = github_token

        try:
            resp = await self._http_client.post(
                "/v1/repo/pr/create",
                json=payload,
                headers=headers if headers else None,
                timeout=None,
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/repo/pr/create")
            raise  # Should not be reached

    async def submit_review_job(
        self,
        planner_model: str,
        severity_threshold: str | None = None,
    ) -> str:
        """Submits a guided review job to the executor.

        Reviews all branch changes vs the merge-base with the default branch,
        including uncommitted working tree changes.

        Args:
            planner_model: The LLM model to use for the review.
            severity_threshold: Minimum severity for review notes
                (CRITICAL, HIGH, MEDIUM, LOW). Defaults to LOW (show everything).

        Returns:
            The jobId of the created review job.

        Raises:
            ExecutorError: If the executor is not started or the request fails.
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload: dict = {"plannerModel": planner_model}
        if severity_threshold:
            payload["severityThreshold"] = severity_threshold

        headers = {"Idempotency-Key": str(uuid.uuid4())}
        effective_session_id = self.session_id
        if effective_session_id:
            headers["X-Session-Id"] = effective_session_id

        try:
            resp = await self._http_client.post(
                "/v1/review/submit",
                json=payload,
                headers=headers,
                timeout=_JOB_CREATE_TIMEOUT_SECONDS,
            )
            resp.raise_for_status()
            return resp.json()["jobId"]
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/review/submit")
            raise  # Should not be reached

    async def get_dependencies(self) -> Dict[str, Any]:
        """Returns all dependencies with their metadata and live status."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.get("/v1/dependencies")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/dependencies")
            raise

    async def update_live_dependencies(self, names: List[str]) -> Dict[str, Any]:
        """Updates the set of live dependencies by name."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.put(
                "/v1/dependencies", json={"liveDependencyNames": names}
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/dependencies")
            raise

    async def update_dependency(self, name: str) -> Dict[str, Any]:
        """Triggers an update of a dependency from its source."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not name or not name.strip():
            raise ExecutorError("name must not be blank")
        endpoint = f"/v1/dependencies/{quote(name, safe='')}/update"
        try:
            resp = await self._http_client.post(endpoint, timeout=120.0)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, endpoint)
            raise

    async def get_settings(self) -> Dict[str, Any]:
        """Returns the full project settings from the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.get("/v1/settings")
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/settings")
            raise

    async def update_all_settings(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """Saves all settings atomically via a single POST."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.post("/v1/settings", json=data)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/settings")
            raise

    async def delete_dependency(self, name: str) -> Dict[str, Any]:
        """Deletes a dependency by name."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not name or not name.strip():
            raise ExecutorError("name must not be blank")
        endpoint = f"/v1/dependencies/{quote(name, safe='')}"
        try:
            resp = await self._http_client.delete(endpoint)
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, endpoint)
            raise

    async def list_remote_refs(self, repo_url: str) -> Dict[str, Any]:
        """Lists branches and tags from a remote Git repository."""
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.post(
                "/v1/dependencies/remote-refs",
                json={"repoUrl": repo_url},
                timeout=30.0,
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/dependencies/remote-refs")
            raise

    async def import_dependency(
        self,
        name: str,
        source_path: Optional[str] = None,
        repo_url: Optional[str] = None,
        ref: Optional[str] = None,
        mark_live: bool = True,
    ) -> Dict[str, Any]:
        """Imports a new dependency from a local path or GitHub repository.

        Args:
            name: Name for the dependency (will be the directory name)
            source_path: For local imports, the absolute path to source directory
            repo_url: For Git imports, the repository URL
            ref: For Git imports, the branch/tag/commit (default: main on server)
            mark_live: Whether to mark the dependency as live after import

        Returns:
            Dict with status, name, and path of the imported dependency

        Raises:
            ExecutorError: If the executor is not started or the request fails
        """
        if not self._http_client:
            raise ExecutorError("Executor not started")
        if not name or not name.strip():
            raise ExecutorError("name must not be blank")

        payload: Dict[str, Any] = {"name": name.strip(), "markLive": mark_live}

        if source_path:
            payload["type"] = "local"
            payload["sourcePath"] = source_path
        elif repo_url:
            payload["type"] = "git"
            payload["repoUrl"] = repo_url
            if ref:
                payload["ref"] = ref
        else:
            raise ExecutorError("Either source_path or repo_url must be provided")

        try:
            resp = await self._http_client.post(
                "/v1/dependencies/import",
                json=payload,
                timeout=120.0,
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.HTTPError as e:
            await self._handle_http_error(e, "/v1/dependencies/import")
            raise

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
                    await asyncio.wait_for(self._process.wait(), timeout=30.0)
                except asyncio.TimeoutError:
                    if self.shutdown_context:
                        logger.warning(
                            "Executor didn't terminate in time, killing... (%s)",
                            self.shutdown_context,
                        )
                    else:
                        logger.warning("Executor didn't terminate in time, killing...")
                    self._process.kill()
                    await self._process.wait()
            except ProcessLookupError:
                pass
            self._process = None

        logger.info("Executor stopped")
