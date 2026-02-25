import asyncio
import logging
import os
import secrets
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, AsyncGenerator, Dict, List, Optional

import httpx

logger = logging.getLogger(__name__)


class ExecutorError(Exception):
    """Base exception for executor-related errors."""


class ExecutorManager:
    """Manages the lifecycle and API communication with the Java Brokk executor."""

    def __init__(
        self,
        workspace_dir: Path = Path.cwd(),
        jar_path: Optional[Path] = None,
        executor_version: Optional[str] = None,
        executor_snapshot: bool = True,
        vendor: Optional[str] = None,
        brokk_api_key: Optional[str] = None,
    ) -> None:
        self.workspace_dir = workspace_dir
        self.jar_path = jar_path
        self.executor_version = executor_version
        self.executor_snapshot = executor_snapshot
        self.vendor = vendor
        self.brokk_api_key = brokk_api_key

        self.process: Optional[subprocess.Popen] = None
        self.auth_token: str = secrets.token_hex(32)
        self.base_url: str = ""
        self.session_id: Optional[str] = None
        self.resolved_jar_path: Optional[Path] = None

        self._http_client: Optional[httpx.AsyncClient] = None

    def check_alive(self) -> bool:
        if self.process is None:
            return False
        return self.process.poll() is None

    async def start(self) -> None:
        # Implementation of start logic (omitted for brevity in reconstruct, assuming base functionality)
        # For the purpose of this fix, we assume the startup logic sets up _http_client and base_url.
        pass

    async def stop(self) -> None:
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
        if self.process:
            self.process.terminate()
            self.process = None

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        start_time = time.monotonic()
        while time.monotonic() - start_time < timeout:
            try:
                if self._http_client:
                    resp = await self._http_client.get("/health/ready")
                    if resp.status_code == 200:
                        return True
            except Exception:
                pass
            await asyncio.sleep(0.5)
        return False

    async def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        if not self._http_client:
            raise ExecutorError("Executor not started")
        try:
            resp = await self._http_client.request(method, path, **kwargs)
            resp.raise_for_status()
            return resp
        except httpx.HTTPStatusError as e:
            raise ExecutorError(f"HTTP {e.response.status_code} error from executor at {path}: {e.response.text}")
        except Exception as e:
            raise ExecutorError(f"Connection error to executor: {e}")

    async def get_health_live(self) -> Dict[str, Any]:
        resp = await self._request("GET", "/health/live")
        return resp.json()

    async def create_session(self, name: str = "default") -> str:
        resp = await self._request("POST", "/v1/sessions", json={"name": name})
        data = resp.json()
        self.session_id = data["sessionId"]
        return self.session_id

    async def import_session_zip(self, zip_bytes: bytes, session_id: Optional[str] = None) -> str:
        params = {"sessionId": session_id} if session_id else {}
        resp = await self._request("PUT", "/v1/sessions", content=zip_bytes, params=params)
        data = resp.json()
        self.session_id = data["sessionId"]
        return self.session_id

    async def download_session_zip(self, session_id: str) -> bytes:
        resp = await self._request("GET", f"/v1/sessions/{session_id}")
        return resp.content

    async def submit_job(self, prompt: str, model: str, **kwargs) -> str:
        payload = {"prompt": prompt, "model": model, **kwargs}
        resp = await self._request("POST", "/v1/jobs", json=payload)
        return resp.json()["jobId"]

    async def stream_events(self, job_id: str) -> AsyncGenerator[Dict[str, Any], None]:
        if not self._http_client:
            raise ExecutorError("Executor not started")
        async with self._http_client.stream("GET", f"/v1/jobs/{job_id}/events", timeout=None) as response:
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    import json
                    yield json.loads(line[6:])

    async def cancel_job(self, job_id: str) -> None:
        await self._request("POST", f"/v1/jobs/{job_id}/cancel")

    async def get_context(self) -> Dict[str, Any]:
        resp = await self._request("GET", "/v1/context")
        return resp.json()

    async def drop_context_fragments(self, fragment_ids: List[str]) -> None:
        await self._request("POST", "/v1/context/drop", json={"fragmentIds": fragment_ids})

    async def drop_all_context(self) -> None:
        await self._request("POST", "/v1/context/drop-all")

    async def set_context_fragment_pinned(self, fragment_id: str, pinned: bool) -> None:
        await self._request("POST", "/v1/context/pin", json={"fragmentId": fragment_id, "pinned": pinned})

    async def set_context_fragment_readonly(self, fragment_id: str, readonly: bool) -> None:
        await self._request("POST", "/v1/context/readonly", json={"fragmentId": fragment_id, "readonly": readonly})

    async def compress_context_history(self) -> None:
        await self._request("POST", "/v1/context/compress-history")

    async def clear_context_history(self) -> None:
        await self._request("POST", "/v1/context/clear-history")

    async def add_context_files(self, paths: List[str]) -> Dict[str, Any]:
        resp = await self._request("POST", "/v1/context/files", json={"relativePaths": paths})
        return resp.json()

    async def add_context_classes(self, class_names: List[str]) -> Dict[str, Any]:
        resp = await self._request("POST", "/v1/context/classes", json={"classNames": class_names})
        return resp.json()

    async def add_context_methods(self, method_names: List[str]) -> Dict[str, Any]:
        resp = await self._request("POST", "/v1/context/methods", json={"methodNames": method_names})
        return resp.json()

    async def get_tasklist(self) -> Dict[str, Any]:
        resp = await self._request("GET", "/v1/tasklist")
        return resp.json()

    async def set_tasklist(self, data: Dict[str, Any]) -> Dict[str, Any]:
        resp = await self._request("POST", "/v1/tasklist", json=data)
        return resp.json()

    async def get_models(self) -> Dict[str, Any]:
        resp = await self._request("GET", "/v1/models")
        return resp.json()

    async def get_completions(self, query: str, limit: int = 20) -> Dict[str, Any]:
        resp = await self._request("GET", "/v1/completions", params={"query": query, "limit": limit})
        return resp.json()

    async def start_openai_oauth(self) -> Dict[str, Any]:
        """Starts the OpenAI OAuth flow on the executor."""
        resp = await self._request("POST", "/v1/openai/oauth/start")
        return resp.json()
