import asyncio
import json
import logging
import uuid
from pathlib import Path
from typing import AsyncIterator, Optional, Dict, Any

import httpx

logger = logging.getLogger(__name__)


class ExecutorError(Exception):
    """Custom error for ExecutorManager operations."""

    pass


class ExecutorManager:
    def __init__(self, workspace_dir: Optional[Path] = None, jar_path: Optional[Path] = None):
        self.workspace_dir = (workspace_dir or Path.cwd()).resolve()
        self.jar_override = jar_path
        self.auth_token = str(uuid.uuid4())
        self.base_url: Optional[str] = None
        self.session_id: Optional[str] = None

        self._process: Optional[asyncio.subprocess.Process] = None
        self._http_client: Optional[httpx.AsyncClient] = None

    def _find_jar(self) -> Path:
        """Locates the brokk.jar file with fallback to download."""
        # 1. Explicit override
        if self.jar_override:
            if not self.jar_override.exists():
                raise ExecutorError(f"Provided jar path does not exist: {self.jar_override}")
            return self.jar_override

        # 2. Check well-known download location
        downloaded_jar = Path.home() / ".brokk" / "brokk.jar"
        if downloaded_jar.exists():
            return downloaded_jar

        # 3. Search upward for local development builds
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

        # 4. Download from GitHub
        return self._download_jar()

    def _download_jar(self) -> Path:
        """Downloads the latest release JAR from GitHub."""
        api_url = "https://api.github.com/repos/BrokkAi/brokk-releases/releases"
        dest_dir = Path.home() / ".brokk"
        dest_jar = dest_dir / "brokk.jar"

        dest_dir.mkdir(parents=True, exist_ok=True)

        logger.info("Fetching release information from GitHub...")
        try:
            # Synchronous request to get releases
            with httpx.Client(follow_redirects=True, timeout=30.0) as client:
                # Get list of releases
                response = client.get(api_url)
                response.raise_for_status()
                releases = response.json()

                # Find first non-snapshot release
                target_release = None
                for release in releases:
                    tag_name = release.get("tag_name", "")
                    if "snapshot" not in tag_name.lower():
                        target_release = release
                        break

                if not target_release:
                    raise ExecutorError("No non-snapshot release found")

                # Find the JAR asset
                jar_asset = None
                for asset in target_release.get("assets", []):
                    if asset.get("name", "").endswith(".jar"):
                        jar_asset = asset
                        break

                if not jar_asset:
                    raise ExecutorError(
                        f"No JAR asset found in release {target_release.get('tag_name')}"
                    )

                jar_url = jar_asset["browser_download_url"]
                jar_name = jar_asset["name"]

                logger.info(f"Downloading {jar_name} from GitHub releases...")
                jar_response = client.get(jar_url)
                jar_response.raise_for_status()
                dest_jar.write_bytes(jar_response.content)

        except httpx.HTTPError as e:
            raise ExecutorError(f"Failed to download brokk.jar: {e}")
        except (KeyError, IndexError) as e:
            raise ExecutorError(f"Failed to parse GitHub release info: {e}")

        logger.info(f"Downloaded {jar_name} to {dest_jar}")
        return dest_jar

    async def start(self):
        """Starts the Java HeadlessExecutorMain subprocess."""
        jar_path = self._find_jar()
        exec_id = str(uuid.uuid4())

        cmd = [
            "java",
            "-cp",
            str(jar_path),
            "ai.brokk.executor.HeadlessExecutorMain",
            "--exec-id",
            exec_id,
            "--listen-addr",
            "127.0.0.1:0",
            "--auth-token",
            self.auth_token,
            "--workspace-dir",
            str(self.workspace_dir),
        ]

        logger.info(f"Starting executor: {' '.join(cmd)}")

        try:
            self._process = await asyncio.create_subprocess_exec(
                *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
            )
        except FileNotFoundError:
            raise ExecutorError(
                "Java executable not found. Please ensure JDK 21+ is installed and 'java' is in your PATH."
            )

        # Parse stdout for the listening URL
        port = None
        # We use a timeout for the initial port readout to avoid hanging if the JAR crashes immediately
        while True:
            try:
                line_bytes = await asyncio.wait_for(self._process.stdout.readline(), timeout=10.0)
            except asyncio.TimeoutError:
                break
            if not line_bytes:
                break
            line = line_bytes.decode().strip()
            logger.debug(f"Executor: {line}")

            if "Executor listening on http://" in line:
                # Line format: "Executor listening on http://127.0.0.1:PORT"
                try:
                    port = int(line.split(":")[-1])
                    break
                except (ValueError, IndexError):
                    continue

        if port is None:
            await self.stop()
            raise ExecutorError("Failed to extract port from executor output")

        self.base_url = f"http://127.0.0.1:{port}"
        self._http_client = httpx.AsyncClient(
            base_url=self.base_url,
            headers={"Authorization": f"Bearer {self.auth_token}"},
            timeout=30.0,
        )
        logger.info(f"Executor started at {self.base_url}")

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

    async def submit_job(
        self,
        task_input: str,
        planner_model: str,
        code_model: Optional[str] = None,
        reasoning_level: Optional[str] = None,
        reasoning_level_code: Optional[str] = None,
    ) -> str:
        """Submits a new job to the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        payload = {
            "taskInput": task_input,
            "plannerModel": planner_model,
            "autoCommit": True,
            "autoCompress": True,
            "tags": {"mode": "LUTZ"},
        }

        # Add optional fields only if they are set
        if code_model:
            payload["codeModel"] = code_model
        if reasoning_level:
            payload["reasoningLevel"] = reasoning_level
        if reasoning_level_code:
            payload["reasoningLevelCode"] = reasoning_level_code

        headers = {"Idempotency-Key": str(uuid.uuid4())}
        resp = await self._http_client.post("/v1/jobs", json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["jobId"]

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        """Streams events for a specific job until it reaches a terminal state."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        after_seq = -1
        terminal_states = {"COMPLETED", "FAILED", "CANCELLED"}

        while True:
            # Check job status
            status_resp = await self._http_client.get(f"/v1/jobs/{job_id}")
            status_resp.raise_for_status()
            status_data = status_resp.json()
            state = status_data.get("state")

            # Fetch events
            events_url = f"/v1/jobs/{job_id}/events?after={after_seq}&limit=100"
            events_resp = await self._http_client.get(events_url)
            events_resp.raise_for_status()
            events_data = events_resp.json()

            for event in events_data.get("events", []):
                yield event
                after_seq = max(after_seq, event.get("seq", -1))

            if state in terminal_states:
                break

            await asyncio.sleep(0.5)

    async def get_context(self) -> Dict[str, Any]:
        """Returns the current session context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        resp = await self._http_client.get("/v1/context", params={"tokens": "true"})
        resp.raise_for_status()
        return resp.json()

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
