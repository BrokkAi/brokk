import asyncio
import json
import logging
import sys
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

from brokk_code.executor import ExecutorError, ExecutorManager

logger = logging.getLogger(__name__)

VALID_MODES = {"LUTZ", "ASK", "SEARCH"}
BASE_MODEL_IDS = ("gpt-5.2", "gemini-3-flash-preview")
REASONING_LEVEL_IDS = ("low", "medium", "high", "disable", "default")
DEFAULT_MODEL_SELECTION = "gpt-5.2"
DEFAULT_REASONING_LEVEL = "low"


def normalize_mode(mode: Optional[str]) -> str:
    if not mode:
        return "LUTZ"
    upper = str(mode).strip().upper()
    if upper in VALID_MODES:
        return upper
    return "LUTZ"


def resolve_model_selection(model_selection: Optional[str]) -> tuple[str, Optional[str]]:
    raw = (model_selection or "").strip()
    if not raw:
        return "gpt-5.2", None
    if "#r=" not in raw:
        return raw, None
    model_id, reasoning = raw.split("#r=", 1)
    normalized_reasoning = reasoning.strip().lower()
    if normalized_reasoning not in REASONING_LEVEL_IDS:
        return model_id.strip() or "gpt-5.2", None
    return model_id.strip() or "gpt-5.2", normalized_reasoning


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
    event: dict[str, Any],
    update_agent_message_text: Callable[[str], Any],
    update_agent_thought_text: Optional[Callable[[str], Any]] = None,
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
        if update_agent_thought_text:
            return update_agent_thought_text(f"[{level}] {msg}")
        return update_agent_message_text(f"\n[{level}] {msg}\n")

    if event_type == "STATE_HINT":
        message = data.get("message")
        if isinstance(message, str) and message.strip():
            if update_agent_thought_text:
                return update_agent_thought_text(message.strip())
            return update_agent_message_text(f"\n[STATE] {message.strip()}\n")
        return None

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


def _format_chip(fragment: dict[str, Any]) -> str:
    chip_kind = str(fragment.get("chip_kind", fragment.get("chipKind", "OTHER")))
    description = str(fragment.get("shortDescription", "Unknown"))
    text = f"{chip_kind} {description}"

    tokens = fragment.get("tokens", 0)
    if isinstance(tokens, int) and tokens > 0:
        text += f" {tokens:,}t"
    if fragment.get("pinned"):
        text += " [PIN]"
    return text


def _estimate_chip_width(fragment: dict[str, Any]) -> int:
    # Matches the simple width estimation behavior used by the TUI context panel.
    return len(_format_chip(fragment)) + 4


def _chip_kind(fragment: dict[str, Any]) -> str:
    return str(fragment.get("chip_kind", fragment.get("chipKind", "OTHER"))).upper()


def _chip_kind_rank(kind: str) -> int:
    ranks = {
        "EDIT": 0,
        "SUMMARY": 1,
        "HISTORY": 2,
        "TASK_LIST": 3,
        "OTHER": 4,
        "INVALID": 5,
    }
    return ranks.get(kind, 99)


def _chip_kind_label(kind: str) -> str:
    labels = {
        "EDIT": "Editable Context",
        "SUMMARY": "Summaries",
        "HISTORY": "History",
        "TASK_LIST": "Task List",
        "OTHER": "Other Context",
        "INVALID": "Invalid Context",
    }
    return labels.get(kind, kind.title())


def _chip_kind_purpose(kind: str) -> str:
    purposes = {
        "EDIT": "Directly editable source/context",
        "SUMMARY": "Read-only summaries for reference",
        "HISTORY": "Prior conversation and run history",
        "TASK_LIST": "Structured plan/checklist context",
        "OTHER": "Additional supporting context",
        "INVALID": "Stale or invalid fragments",
    }
    return purposes.get(kind, "Context fragments")


def _is_discarded_context(block: dict[str, Any]) -> bool:
    description = str(block.get("short_description", "")).strip().lower()
    return description == "discarded context"


def _discarded_context_markdown(block: dict[str, Any]) -> str:
    payload = {
        "title": block.get("short_description", "Discarded Context"),
        "chipKind": block.get("chip_kind", "OTHER"),
        "content": block.get("text", ""),
    }
    return "```json\n" + json.dumps(payload, indent=2) + "\n```\n"


def build_context_chip_blocks(
    context_data: dict[str, Any], fragment_resources: dict[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    fragments = context_data.get("fragments", [])
    blocks_with_rank: list[tuple[int, int, dict[str, Any]]] = []
    if not isinstance(fragments, list) or not fragments:
        return []

    for i, fragment in enumerate(fragments):
        fragment_id = fragment.get("id")
        kind = _chip_kind(fragment)
        if isinstance(fragment_id, str) and fragment_id:
            payload = fragment_resources.get(fragment_id)
            if isinstance(payload, dict):
                uri = payload.get("uri")
                mime_type = payload.get("mimeType")
                text = payload.get("text")
                if isinstance(uri, str) and isinstance(mime_type, str) and isinstance(text, str):
                    blocks_with_rank.append(
                        (
                            _chip_kind_rank(kind),
                            i,
                            {
                                "uri": uri,
                                "mime_type": mime_type,
                                "text": text,
                                "chip_kind": kind,
                                "short_description": str(
                                    fragment.get("shortDescription", "Unknown")
                                ),
                                "tokens": int(fragment.get("tokens", 0) or 0),
                            },
                        )
                    )

    blocks_with_rank.sort(key=lambda item: (item[0], item[1]))
    return [item[2] for item in blocks_with_rank]


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
        update_agent_thought_text: Optional[Callable[[str], Any]],
        build_context_snapshot_update: Callable[[str, str, str], Any],
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
                update = map_executor_event_to_session_update(
                    event,
                    update_agent_message_text,
                    update_agent_thought_text,
                )
                if update:
                    await send_update(session_id, update)
            try:
                context_data = await self.executor.get_context()
                fragment_resources: dict[str, dict[str, Any]] = {}
                fragments = context_data.get("fragments", [])
                if isinstance(fragments, list):
                    fragment_ids = [
                        fragment.get("id")
                        for fragment in fragments
                        if isinstance(fragment, dict) and isinstance(fragment.get("id"), str)
                    ]
                    if fragment_ids:
                        results = await asyncio.gather(
                            *[
                                self.executor.get_context_fragment(fragment_id)
                                for fragment_id in fragment_ids
                            ],
                            return_exceptions=True,
                        )
                        for fragment_id, result in zip(fragment_ids, results):
                            if isinstance(result, dict):
                                fragment_resources[fragment_id] = result
                blocks = build_context_chip_blocks(context_data, fragment_resources)
                if blocks:
                    used_tokens = int(context_data.get("usedTokens", 0) or 0)
                    max_tokens = int(context_data.get("maxTokens", 0) or 0)
                    await send_update(
                        session_id,
                        update_agent_message_text(
                            "\n\n### Context Snapshot\n"
                            f"{len(blocks)} resources | {used_tokens:,}/{max_tokens:,} tokens\n"
                        ),
                    )
                current_kind: Optional[str] = None
                for block in blocks:
                    kind = str(block["chip_kind"])
                    if kind != current_kind:
                        current_kind = kind
                        await send_update(
                            session_id,
                            update_agent_message_text(f"\n#### {_chip_kind_label(kind)}\n"),
                        )
                    is_resource_list_kind = kind in {"EDIT", "SUMMARY"}
                    if is_resource_list_kind and not _is_discarded_context(block):
                        await send_update(session_id, update_agent_message_text("- "))
                        await send_update(
                            session_id,
                            build_context_snapshot_update(
                                str(block["uri"]),
                                str(block["mime_type"]),
                                str(block["text"]),
                            ),
                        )
                        await send_update(
                            session_id,
                            update_agent_message_text(f" | {int(block['tokens'])}\n"),
                        )
                        continue
                    await send_update(
                        session_id,
                        update_agent_message_text(_discarded_context_markdown(block))
                        if _is_discarded_context(block)
                        else build_context_snapshot_update(
                            str(block["uri"]),
                            str(block["mime_type"]),
                            str(block["text"]),
                        ),
                    )
                    await send_update(session_id, update_agent_message_text("\n"))
            except Exception as e:
                await send_update(
                    session_id,
                    update_agent_message_text(f"[INFO] Context snapshot unavailable: {e}"),
                )
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
            embedded_text_resource,
            resource_block,
            run_agent,
            update_agent_message,
            update_agent_message_text,
            update_agent_thought_text,
        )
        from acp.agent import connection as acp_agent_connection
        from acp.agent import router as acp_agent_router
        from acp.meta import AGENT_METHODS
        from acp.schema import (
            AgentCapabilities,
            Implementation,
            ListSessionsResponse,
            ModelInfo,
            SessionConfigOption,
            SessionConfigSelectOption,
            SessionInfo,
            SessionMode,
            SessionModelState,
            SessionModeState,
            SetSessionConfigOptionRequest,
            SetSessionConfigOptionResponse,
        )
        from acp.utils import normalize_result
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

    def _patch_acp_router_for_session_config_option() -> None:
        if getattr(acp_agent_router, "_brokk_session_config_patch", False):
            return
        original_build_agent_router = acp_agent_router.build_agent_router

        def patched_build_agent_router(agent: Any, use_unstable_protocol: bool = False) -> Any:
            router = original_build_agent_router(
                agent, use_unstable_protocol=use_unstable_protocol
            )
            router.route_request(
                AGENT_METHODS["session_set_config_option"],
                SetSessionConfigOptionRequest,
                agent,
                "set_session_config_option",
                adapt_result=normalize_result,
                unstable=True,
            )
            return router

        acp_agent_router.build_agent_router = patched_build_agent_router
        # AgentSideConnection captured a module-level symbol; patch it too.
        acp_agent_connection.build_agent_router = patched_build_agent_router
        acp_agent_router._brokk_session_config_patch = True

    _patch_acp_router_for_session_config_option()

    class BrokkAcpAgent(Agent):
        def __init__(self) -> None:
            self.client: Optional[Any] = None
            self._mode_by_session: dict[str, str] = {}
            self._model_by_session: dict[str, str] = {}
            self._reasoning_by_session: dict[str, str] = {}
            self._cwd_by_session: dict[str, str] = {}

        def _config_options_for_session(self, session_id: str) -> list[Any]:
            current_mode = self._mode_by_session.get(session_id, "LUTZ")
            current_model = self._model_by_session.get(
                session_id, DEFAULT_MODEL_SELECTION
            )
            current_reasoning = self._reasoning_by_session.get(
                session_id, DEFAULT_REASONING_LEVEL
            )
            return [
                SessionConfigOption.model_validate(
                    {
                        "type": "select",
                        "id": "mode",
                        "name": "Mode",
                        "description": "Choose Brokk operating mode",
                        "category": "session",
                        "currentValue": current_mode,
                        "options": [
                            SessionConfigSelectOption(value=mode, name=mode)
                            for mode in sorted(VALID_MODES)
                        ],
                    }
                ),
                SessionConfigOption.model_validate(
                    {
                        "type": "select",
                        "id": "model",
                        "name": "Model",
                        "description": "Choose which model Brokk should use",
                        "category": "model",
                        "currentValue": current_model,
                        "options": [
                            SessionConfigSelectOption(value=model_id, name=model_id)
                            for model_id in BASE_MODEL_IDS
                        ],
                    }
                ),
                SessionConfigOption.model_validate(
                    {
                        "type": "select",
                        "id": "reasoning_effort",
                        "name": "Reasoning Effort",
                        "description": "Choose how much reasoning effort the model should use",
                        "category": "model",
                        "currentValue": current_reasoning,
                        "options": [
                            SessionConfigSelectOption(value=level, name=level.title())
                            for level in REASONING_LEVEL_IDS
                        ],
                    }
                )
            ]

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
            self._model_by_session[session_id] = DEFAULT_MODEL_SELECTION
            self._reasoning_by_session[session_id] = DEFAULT_REASONING_LEVEL
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
                        ModelInfo(model_id=model_id, name=model_id)
                        for model_id in BASE_MODEL_IDS
                    ],
                    current_model_id=DEFAULT_MODEL_SELECTION,
                ),
                config_options=self._config_options_for_session(session_id),
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
                        ModelInfo(model_id=model_id, name=model_id)
                        for model_id in BASE_MODEL_IDS
                    ],
                    current_model_id=self._model_by_session.get(
                        session_id, DEFAULT_MODEL_SELECTION
                    ),
                ),
                config_options=self._config_options_for_session(session_id),
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
            selected_model, selected_reasoning = resolve_model_selection(model_id)
            self._model_by_session[session_id] = selected_model or DEFAULT_MODEL_SELECTION
            if selected_reasoning:
                self._reasoning_by_session[session_id] = selected_reasoning
            return SetSessionModelResponse()

        async def set_session_config_option(
            self,
            config_id: str,
            session_id: str,
            value: str,
            **kwargs: Any,
        ) -> SetSessionConfigOptionResponse:
            del kwargs
            if config_id == "mode" and value:
                self._mode_by_session[session_id] = normalize_mode(value)
            elif config_id == "model" and value:
                self._model_by_session[session_id] = value
            elif config_id == "reasoning_effort" and value in REASONING_LEVEL_IDS:
                self._reasoning_by_session[session_id] = value
            return SetSessionConfigOptionResponse(
                config_options=self._config_options_for_session(session_id)
            )

        async def prompt(self, prompt: Any, session_id: str, **kwargs: Any) -> Any:
            mode = normalize_mode(kwargs.get("mode") or self._mode_by_session.get(session_id))
            planner_model = (
                kwargs.get("planner_model")
                or kwargs.get("plannerModel")
                or self._model_by_session.get(session_id)
                or DEFAULT_MODEL_SELECTION
            )
            planner_model_id, selected_reasoning_level = resolve_model_selection(planner_model)
            code_model = (
                kwargs.get("code_model")
                or kwargs.get("codeModel")
                or "gemini-3-flash-preview"
            )
            reasoning_level = (
                kwargs.get("reasoning_level")
                or kwargs.get("reasoningLevel")
                or selected_reasoning_level
                or self._reasoning_by_session.get(session_id)
                or "low"
            )
            reasoning_level_code = (
                kwargs.get("reasoning_level_code") or kwargs.get("reasoningLevelCode") or "disable"
            )
            if not self.client:
                raise ExecutorError("ACP client connection not established.")
            await bridge.prompt(
                prompt=prompt,
                session_id=session_id,
                mode=mode,
                planner_model=planner_model_id,
                code_model=code_model,
                reasoning_level=reasoning_level,
                reasoning_level_code=reasoning_level_code,
                send_update=self.client.session_update,
                update_agent_message_text=update_agent_message_text,
                update_agent_thought_text=update_agent_thought_text,
                build_context_snapshot_update=lambda uri, mime_type, text: update_agent_message(
                    resource_block(
                        embedded_text_resource(
                            uri=uri,
                            text=text,
                            mime_type=mime_type,
                        )
                    )
                ),
            )
            return PromptResponse(stop_reason="end_turn")

        async def cancel(self, *args: Any, **kwargs: Any) -> None:
            await bridge.cancel(*args, **kwargs)

    try:
        await run_agent(BrokkAcpAgent(), use_unstable_protocol=True)
    finally:
        await executor.stop()
