import asyncio
import io
import logging
import re
import tarfile
import uuid
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional

import httpx

logger = logging.getLogger(__name__)


class ExecutorError(Exception):
    """Custom error for ExecutorManager operations."""

    pass


class ExecutorManager:
    def __init__(
        self,
        workspace_dir: Optional[Path] = None,
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = False,
    ):
        self.workspace_dir = (workspace_dir or Path.cwd()).resolve()
        self.jar_override = jar_path
        self.executor_version = executor_version
        self.use_snapshot = executor_snapshot
        self.auth_token = str(uuid.uuid4())
        self.base_url: Optional[str] = None
        self.session_id: Optional[str] = None
        self.resolved_jar_path: Optional[Path] = None

        self._process: Optional[asyncio.subprocess.Process] = None
        self._http_client: Optional[httpx.AsyncClient] = None

    def _sanitize_tag_for_filename(self, tag: str) -> str:
        """Sanitize a git tag for use in a filename."""
        stripped = tag.strip()
        sanitized = re.sub(r"[^A-Za-z0-9._-]+", "_", stripped)
        sanitized = sanitized.strip("._-")
        return sanitized or "unknown"

    def _cached_jar_path(self, version: Optional[str]) -> Path:
        """Returns the local cache path for a given executor version (or latest when None)."""
        dest_dir = Path.home() / ".brokk"
        if not version:
            if self.use_snapshot:
                return dest_dir / "brokk-snapshot.jar"
            return dest_dir / "brokk.jar"
        safe_version = self._sanitize_tag_for_filename(version)
        return dest_dir / f"brokk-{safe_version}.jar"

    def _find_jar(self) -> Path:
        """Locates the brokk.jar file with fallback to download."""
        # 1. Explicit override
        if self.jar_override:
            if not self.jar_override.exists():
                raise ExecutorError(f"Provided jar path does not exist: {self.jar_override}")
            return self.jar_override

        # 2. Check cached download location (versioned if executor_version is set)
        cached_jar = self._cached_jar_path(self.executor_version)
        if cached_jar.exists():
            return cached_jar

        # 3. Search upward for local development builds (only for "latest" mode)
        if not self.executor_version:
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

        # 4. Download from GitHub (versioned if executor_version is set)
        return self._download_jar(self.executor_version)

    def _download_jar(self, version: Optional[str] = None) -> Path:
        """Downloads the requested or latest release JAR from GitHub.

        Tag matching policy when 'version' is provided:
        - We perform an exact, case-sensitive match against the release 'tag_name'
          after applying .strip() to the provided version string.
        """
        api_url = "https://api.github.com/repos/BrokkAi/brokk-releases/releases"
        dest_jar = self._cached_jar_path(version)
        dest_jar.parent.mkdir(parents=True, exist_ok=True)

        requested = version.strip() if version else None
        logger.info(
            "Fetching release information from GitHub (requested_tag=%r, snapshot_mode=%s)...",
            requested,
            self.use_snapshot,
        )
        try:
            with httpx.Client(follow_redirects=True, timeout=30.0) as client:
                response = client.get(api_url)
                response.raise_for_status()
                releases = response.json()

                if self.use_snapshot:
                    all_tags = [r.get("tag_name", "") for r in releases]
                    logger.info("Available releases (snapshot mode): %s", all_tags)

                target_release: Optional[Dict[str, Any]] = None
                if requested:
                    for release in releases:
                        if release.get("tag_name") == requested:
                            target_release = release
                            break
                    if not target_release:
                        available = [r.get("tag_name", "") for r in releases]
                        raise ExecutorError(
                            "Executor release tag not found on GitHub: "
                            f"tag='{requested}'. Available tags: {available}"
                        )
                else:
                    if self.use_snapshot:
                        # In snapshot mode, just take the first release (no filtering)
                        if releases:
                            target_release = releases[0]
                            logger.info(
                                "Using first available release (snapshot mode): %s",
                                target_release.get("tag_name", "unknown"),
                            )
                    else:
                        # In normal mode, skip releases with "snapshot" in the name
                        for release in releases:
                            tag_name = release.get("tag_name", "")
                            if "snapshot" not in tag_name.lower():
                                target_release = release
                                break

                    if not target_release:
                        mode = "snapshot" if self.use_snapshot else "non-snapshot"
                        available = [r.get("tag_name", "") for r in releases]
                        raise ExecutorError(
                            f"No suitable release found on GitHub (mode={mode}). "
                            f"Available: {available}"
                        )

                assets = target_release.get("assets", [])
                jar_asset: Optional[Dict[str, Any]] = None
                archive_assets: List[Dict[str, Any]] = []

                for asset in assets:
                    name = asset.get("name", "")
                    if name.endswith(".jar"):
                        jar_asset = asset
                        break
                    if name.endswith((".tgz", ".tar.gz")):
                        archive_assets.append(asset)

                tgz_asset: Optional[Dict[str, Any]] = None
                if not jar_asset and archive_assets:
                    # Logic to find the best archive
                    # 1. Exact match for brokk-{requested}.tgz
                    if requested:
                        match_names = {f"brokk-{requested}.tgz", f"brokk-{requested}.tar.gz"}
                        tgz_asset = next(
                            (a for a in archive_assets if a.get("name") in match_names), None
                        )

                    # 2. Prefer brokk-* and NOT Brokk.Installer*
                    if not tgz_asset:
                        tgz_asset = next(
                            (
                                a
                                for a in archive_assets
                                if a.get("name", "").lower().startswith("brokk-")
                                and not a.get("name", "").startswith("Brokk.Installer")
                            ),
                            None,
                        )

                    # 3. Fallback to any archive
                    if not tgz_asset:
                        tgz_asset = archive_assets[0]

                if jar_asset:
                    jar_url = jar_asset["browser_download_url"]
                    jar_name = jar_asset.get("name", "brokk.jar")
                    logger.info(
                        "Downloading executor jar (tag=%s, asset=%s) ...",
                        target_release.get("tag_name"),
                        jar_name,
                    )
                    jar_response = client.get(jar_url)
                    jar_response.raise_for_status()
                    dest_jar.write_bytes(jar_response.content)
                elif tgz_asset:
                    tgz_url = tgz_asset["browser_download_url"]
                    asset_filename = tgz_asset.get("name", "archive.tgz")
                    logger.info(
                        "Downloading executor archive (tag=%s, asset=%s) ...",
                        target_release.get("tag_name"),
                        asset_filename,
                    )
                    tgz_response = client.get(tgz_url)
                    tgz_response.raise_for_status()
                    jar_bytes = self._extract_jar_from_tgz(
                        tgz_response.content, requested, asset_filename
                    )
                    dest_jar.write_bytes(jar_bytes)
                else:
                    tag = target_release.get("tag_name", "unknown")
                    asset_names = [a.get("name", "") for a in assets]
                    raise ExecutorError(
                        f"Executor release has no .jar or .tgz asset: "
                        f"tag='{tag}', assets={asset_names}"
                    )

        except httpx.HTTPError as e:
            raise ExecutorError(f"Failed to download brokk.jar from GitHub: {e}")
        except (KeyError, IndexError, TypeError, ValueError) as e:
            raise ExecutorError(f"Failed to parse GitHub release info: {e}")

        logger.info("Downloaded %s to %s", jar_name, dest_jar)
        return dest_jar

    def _extract_jar_from_tgz(
        self, tgz_content: bytes, version: Optional[str], asset_name: str
    ) -> bytes:
        """Extracts the best-matching JAR from a TGZ archive bytes."""
        with tarfile.open(fileobj=io.BytesIO(tgz_content), mode="r:gz") as tar:
            members: List[tarfile.TarInfo] = [m for m in tar.getmembers() if m.isfile()]
            jar_members = [m for m in members if m.name.endswith(".jar")]

            if not jar_members:
                raise ExecutorError(f"No .jar files found in archive: {asset_name}")

            # 1. Exact path match for versioned bundles
            if version:
                exact_path = f"package/jdeploy-bundle/brokk-{version}.jar"
                for m in jar_members:
                    if m.name == exact_path:
                        return tar.extractfile(m).read()  # type: ignore

            # 2. Contains jdeploy-bundle/ and brokk in basename
            # This handles cases like 'package/jdeploy-bundle/brokk-b441ac1.jar'
            for m in jar_members:
                path_parts = Path(m.name).parts
                basename = path_parts[-1]
                if "jdeploy-bundle" in path_parts and "brokk" in basename.lower():
                    logger.info("Found JAR in archive via jdeploy-bundle path: %s", m.name)
                    return tar.extractfile(m).read()  # type: ignore

            # 3. Any jar containing 'brokk'
            for m in jar_members:
                if "brokk" in Path(m.name).name.lower():
                    logger.info("Found JAR in archive via name match: %s", m.name)
                    return tar.extractfile(m).read()  # type: ignore

            member_names = [m.name for m in jar_members]
            raise ExecutorError(
                f"Could not find a suitable Brokk JAR in {asset_name}. Found JARs: {member_names}"
            )

    async def start(self):
        """Starts the Java HeadlessExecutorMain subprocess."""
        jar_path = self._find_jar()
        self.resolved_jar_path = jar_path
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
                "Java executable not found. "
                "Please ensure JDK 21+ is installed and 'java' is in your PATH."
            )

        # Parse stdout for the listening URL
        port = None
        # We use a timeout for the initial port readout to avoid hanging
        # if the JAR crashes immediately
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
        mode: str = "LUTZ",
        tags: Optional[Dict[str, str]] = None,
    ) -> str:
        """Submits a new job to the executor."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        job_tags = (tags or {}).copy()
        if "mode" not in job_tags:
            job_tags["mode"] = mode

        payload = {
            "taskInput": task_input,
            "plannerModel": planner_model,
            "autoCommit": True,
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
        resp = await self._http_client.post("/v1/jobs", json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["jobId"]

    async def stream_events(self, job_id: str) -> AsyncIterator[Dict[str, Any]]:
        """Streams events for a specific job until it reaches a terminal state with adaptive polling."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        after_seq = -1
        terminal_states = {"COMPLETED", "FAILED", "CANCELLED"}
        
        # Polling configuration
        min_sleep = 0.05
        max_sleep = 0.5
        current_sleep = min_sleep
        
        last_status_check = 0.0
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
                # If we just hit a terminal state, check one last time for any race-condition events
                if not events:
                    break
                # If we did get events, we continue one more loop without sleeping to clear the buffer

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

    async def get_context(self) -> Dict[str, Any]:
        """Returns the current session context."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        resp = await self._http_client.get("/v1/context", params={"tokens": "true"})
        resp.raise_for_status()
        return resp.json()

    async def get_tasklist(self) -> Dict[str, Any]:
        """Returns the current task list data."""
        if not self._http_client:
            raise ExecutorError("Executor not started")

        resp = await self._http_client.get("/v1/tasklist")
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
