import logging
import sys
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

from brokk_code.executor import ExecutorError, ExecutorManager

logger = logging.getLogger(__name__)

VALID_MODES = {"LUTZ", "ASK", "SEARCH"}


def normalize_mode(mode: Optional[str]) -> str:
    if not mode:
        return "LUTZ"
    upper = str(mode).strip().upper()
    if upper in VALID_MODES:
        return upper
    return "LUTZ"


def extract_prompt_text(prompt: Any) -> str:
    if isinstance(prompt, str):
        return prompt.strip()

    parts: list[str] = []
    for block in prompt or []:
        block_type = getattr(block, "type", None)
        text = getattr(block, "text", None)
        if isinstance(block, dict):
            block_type = block.get("type")
            text = block.get("text")
        if block_type == "text" and isinstance(text, str):
            stripped = text.strip()
            if stripped:
                parts.append(stripped)
    return "\n".join(parts).strip()


def map_executor_event_to_session_update(
    event: dict[str, Any], update_agent_message_text: Callable[[str], Any]
) -> Optional[Any]:
    event_type = event.get("type")
    data = event.get("data", {})

    if event_type == "LLM_TOKEN":
        token = data.get("token", "")
        if not token:
            return None
        return update_agent_message_text(token)

    if event_type == "ERROR":
        msg = data.get("message", "Unknown error")
        return update_agent_message_text(f"\n[ERROR] {msg}\n")

    if event_type == "NOTIFICATION":
        level = data.get("level", "INFO")
        msg = data.get("message", "")
        if not msg:
            return None
        return update_agent_message_text(f"\n[{level}] {msg}\n")

    return None


def _extract_session_id_for_cancel(args: tuple[Any, ...], kwargs: dict[str, Any]) -> Optional[str]:
    direct = kwargs.get("session_id")
    if isinstance(direct, str) and direct:
        return direct

    params = kwargs.get("params")
    if isinstance(params, dict):
        sid = params.get("sessionId") or params.get("session_id")
        if isinstance(sid, str) and sid:
            return sid

    if args:
        first = args[0]
        if isinstance(first, str) and first:
            return first
        if isinstance(first, dict):
            sid = first.get("sessionId") or first.get("session_id")
            if isinstance(sid, str) and sid:
                return sid

    return None


class BrokkAcpBridge:
    def __init__(self, executor: ExecutorManager):
        self.executor = executor
        self._acp_to_brokk_session: dict[str, str] = {}
        self._active_job_by_session: dict[str, str] = {}
        self._started = False

    async def ensure_ready(self) -> None:
        if self._started:
            return
        await self.executor.start()
        # Executor readiness depends on having an active session.
        await self.executor.create_session(name="ACP Bootstrap Session")
        ready = await self.executor.wait_ready()
        if not ready:
            raise ExecutorError("Brokk executor failed readiness check")
        self._started = True

    async def _ensure_session(self, acp_session_id: str) -> str:
        existing = self._acp_to_brokk_session.get(acp_session_id)
        if existing:
            return existing
        session_id = await self.executor.create_session(name=f"ACP Session {acp_session_id}")
        self._acp_to_brokk_session[acp_session_id] = session_id
        return session_id

    async def prompt(
        self,
        prompt: Any,
        session_id: str,
        mode: str,
        planner_model: str,
        code_model: Optional[str],
        reasoning_level: Optional[str],
        reasoning_level_code: Optional[str],
        send_update: Callable[[str, Any], Awaitable[Any]],
        update_agent_message_text: Callable[[str], Any],
        **kwargs: Any,
    ) -> None:
        await self.ensure_ready()
        await self._ensure_session(session_id)

        prompt_text = extract_prompt_text(prompt)
        if not prompt_text:
            raise ExecutorError("Prompt must contain at least one non-empty text block.")

        job_id = await self.executor.submit_job(
            task_input=prompt_text,
            planner_model=planner_model,
            code_model=code_model,
            reasoning_level=reasoning_level,
            reasoning_level_code=reasoning_level_code,
            mode=mode,
        )
        self._active_job_by_session[session_id] = job_id

        try:
            async for event in self.executor.stream_events(job_id):
                update = map_executor_event_to_session_update(event, update_agent_message_text)
                if update:
                    await send_update(session_id, update)
        finally:
            active = self._active_job_by_session.get(session_id)
            if active == job_id:
                self._active_job_by_session.pop(session_id, None)

    async def cancel(self, *args: Any, **kwargs: Any) -> None:
        session_id = _extract_session_id_for_cancel(args, kwargs)
        if not session_id:
            return
        job_id = self._active_job_by_session.get(session_id)
        if not job_id:
            return
        await self.executor.cancel_job(job_id)


async def run_acp_server(
    workspace_dir: Path,
    jar_path: Optional[Path],
    executor_version: Optional[str],
    executor_snapshot: bool,
) -> None:
    try:
        from acp import (
            Agent,
            InitializeResponse,
            LoadSessionResponse,
            NewSessionResponse,
            PromptResponse,
            SetSessionModelResponse,
            SetSessionModeResponse,
            run_agent,
            update_agent_message_text,
        )
        from acp.schema import (
            AgentCapabilities,
            Implementation,
            ListSessionsResponse,
            ModelInfo,
            SessionInfo,
            SessionMode,
            SessionModelState,
            SessionModeState,
        )
    except ImportError as e:
        raise RuntimeError(
            "ACP mode requires the official ACP Python SDK. "
            "Install it with: pip install agent-client-protocol"
        ) from e

    logging.basicConfig(stream=sys.stderr, level=logging.INFO)

    executor = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
    )
    bridge = BrokkAcpBridge(executor)

    class BrokkAcpAgent(Agent):
        def __init__(self) -> None:
            self.client: Optional[Any] = None
            self._mode_by_session: dict[str, str] = {}
            self._model_by_session: dict[str, str] = {}
            self._cwd_by_session: dict[str, str] = {}

        def on_connect(self, client: Any) -> None:
            self.client = client

        async def initialize(
            self,
            protocol_version: int,
            client_capabilities: Any = None,
            client_info: Any = None,
            **kwargs: Any,
        ) -> InitializeResponse:
            return InitializeResponse(
                protocol_version=protocol_version,
                agent_info=Implementation(name="brokk-code", version="0.1.0"),
                agent_capabilities=AgentCapabilities(),
            )

        async def new_session(
            self,
            cwd: str,
            mcp_servers: Optional[list[Any]] = None,
            **kwargs: Any,
        ) -> NewSessionResponse:
            del mcp_servers, kwargs
            session_id = str(uuid.uuid4())
            self._mode_by_session[session_id] = "LUTZ"
            self._model_by_session[session_id] = "gpt-5.2"
            self._cwd_by_session[session_id] = cwd
            return NewSessionResponse(
                session_id=session_id,
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="ASK", name="ASK"),
                        SessionMode(id="SEARCH", name="SEARCH"),
                    ],
                    current_mode_id="LUTZ",
                ),
                models=SessionModelState(
                    available_models=[
                        ModelInfo(model_id="gpt-5.2", name="gpt-5.2"),
                        ModelInfo(
                            model_id="gemini-3-flash-preview",
                            name="gemini-3-flash-preview",
                        ),
                    ],
                    current_model_id="gpt-5.2",
                ),
            )

        async def load_session(
            self,
            cwd: str,
            session_id: str,
            mcp_servers: Optional[list[Any]] = None,
            **kwargs: Any,
        ) -> Optional[LoadSessionResponse]:
            del mcp_servers, kwargs
            if session_id not in self._mode_by_session:
                return None
            self._cwd_by_session[session_id] = cwd
            return LoadSessionResponse(
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="ASK", name="ASK"),
                        SessionMode(id="SEARCH", name="SEARCH"),
                    ],
                    current_mode_id=self._mode_by_session.get(session_id, "LUTZ"),
                ),
                models=SessionModelState(
                    available_models=[
                        ModelInfo(model_id="gpt-5.2", name="gpt-5.2"),
                        ModelInfo(
                            model_id="gemini-3-flash-preview",
                            name="gemini-3-flash-preview",
                        ),
                    ],
                    current_model_id=self._model_by_session.get(session_id, "gpt-5.2"),
                ),
            )

        async def list_sessions(
            self,
            cursor: Optional[str] = None,
            cwd: Optional[str] = None,
            **kwargs: Any,
        ) -> ListSessionsResponse:
            del cursor, cwd, kwargs
            sessions = [
                SessionInfo(
                    session_id=session_id,
                    cwd=self._cwd_by_session.get(session_id, str(workspace_dir)),
                )
                for session_id in self._mode_by_session
            ]
            return ListSessionsResponse(sessions=sessions, next_cursor=None)

        async def set_session_mode(
            self,
            mode_id: str,
            session_id: str,
            **kwargs: Any,
        ) -> SetSessionModeResponse:
            del kwargs
            self._mode_by_session[session_id] = normalize_mode(mode_id)
            return SetSessionModeResponse()

        async def set_session_model(
            self,
            model_id: str,
            session_id: str,
            **kwargs: Any,
        ) -> SetSessionModelResponse:
            del kwargs
            self._model_by_session[session_id] = model_id or "gpt-5.2"
            return SetSessionModelResponse()

        async def prompt(self, prompt: Any, session_id: str, **kwargs: Any) -> Any:
            mode = normalize_mode(kwargs.get("mode") or self._mode_by_session.get(session_id))
            planner_model = (
                kwargs.get("planner_model")
                or kwargs.get("plannerModel")
                or self._model_by_session.get(session_id)
                or "gpt-5.2"
            )
            code_model = (
                kwargs.get("code_model")
                or kwargs.get("codeModel")
                or "gemini-3-flash-preview"
            )
            reasoning_level = kwargs.get("reasoning_level") or kwargs.get("reasoningLevel") or "low"
            reasoning_level_code = (
                kwargs.get("reasoning_level_code") or kwargs.get("reasoningLevelCode") or "disable"
            )
            if not self.client:
                raise ExecutorError("ACP client connection not established.")
            await bridge.prompt(
                prompt=prompt,
                session_id=session_id,
                mode=mode,
                planner_model=planner_model,
                code_model=code_model,
                reasoning_level=reasoning_level,
                reasoning_level_code=reasoning_level_code,
                send_update=self.client.session_update,
                update_agent_message_text=update_agent_message_text,
            )
            return PromptResponse(stop_reason="end_turn")

        async def cancel(self, *args: Any, **kwargs: Any) -> None:
            await bridge.cancel(*args, **kwargs)

    try:
        await run_agent(BrokkAcpAgent())
    finally:
        await executor.stop()
