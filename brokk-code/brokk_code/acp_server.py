import asyncio
import json
import logging
import re
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

from brokk_code.executor import ExecutorError, ExecutorManager

logger = logging.getLogger(__name__)

VALID_MODES = {"LUTZ", "ASK", "CODE"}
MODE_OPTIONS = ("LUTZ", "CODE", "ASK")
BASE_MODEL_IDS = ("gpt-5.2", "gemini-3-flash-preview")
REASONING_LEVEL_IDS = ("low", "medium", "high", "disable", "default")
DEFAULT_MODEL_SELECTION = "gpt-5.2"
DEFAULT_REASONING_LEVEL = "low"
THOUGHT_LEVEL_CONFIG_ID = "thought_level"
DEFAULT_VARIANT_VALUE = "default"
MODEL_DISCOVERY_INITIAL_ATTEMPTS = 4
MODEL_DISCOVERY_RECOVERY_ATTEMPTS = 2
MODEL_DISCOVERY_INITIAL_BACKOFF_SECONDS = 0.2


# ACP persistence dataclass and helpers. Persisted in ~/.brokk/acp_settings.json
@dataclass
class AcpDefaults:
    default_model: str = DEFAULT_MODEL_SELECTION
    default_reasoning: str = DEFAULT_REASONING_LEVEL

    def to_dict(self) -> dict[str, str]:
        return {"default_model": self.default_model, "default_reasoning": self.default_reasoning}


def _acp_settings_dir() -> Path:
    return Path.home() / ".brokk"


def _acp_settings_file() -> Path:
    return _acp_settings_dir() / "acp_settings.json"


def load_acp_defaults() -> AcpDefaults:
    """Load ACP defaults from disk, falling back safely on any error.

    This function is tolerant of older or corrupted acp_settings.json files:
    - If the file is missing or unreadable the defaults are returned.
    - If persisted values are invalid (e.g., unknown reasoning id) we log and
      fall back to safe defaults. This ensures ACP startup does not raise.
    """
    path = _acp_settings_file()
    if not path.exists():
        return AcpDefaults()
    try:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f) or {}
            model = str(data.get("default_model") or DEFAULT_MODEL_SELECTION).strip()
            reasoning = str(data.get("default_reasoning") or DEFAULT_REASONING_LEVEL).strip()
            if not model:
                model = DEFAULT_MODEL_SELECTION
            if not reasoning:
                reasoning = DEFAULT_REASONING_LEVEL
            # Ensure reasoning is a recognized id; else fallback to default
            if reasoning not in REASONING_LEVEL_IDS:
                logger.info(
                    "Persisted ACP reasoning %s invalid; falling back to %s",
                    reasoning,
                    DEFAULT_REASONING_LEVEL,
                )
                reasoning = DEFAULT_REASONING_LEVEL
            return AcpDefaults(default_model=model, default_reasoning=reasoning)
    except Exception as e:
        logger.warning("Failed to load ACP defaults from %s: %s", path, e)
        return AcpDefaults()


def save_acp_defaults(defaults: AcpDefaults) -> None:
    try:
        path = _acp_settings_file()
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(".tmp")
        with temp.open("w", encoding="utf-8") as f:
            json.dump(defaults.to_dict(), f, indent=2)
        temp.replace(path)
    except Exception as e:
        logger.error("Failed to save ACP defaults to %s: %s", _acp_settings_file(), e)


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


def _fallback_model_catalog() -> list[dict[str, Any]]:
    return [
        {
            "name": model_id,
            "location": model_id,
            "supportsReasoningEffort": True,
            "supportsReasoningDisable": True,
        }
        for model_id in BASE_MODEL_IDS
    ]


async def _fetch_normalized_catalog_with_retries(
    fetch_models_payload: Callable[[], Awaitable[dict[str, Any]]],
    attempts: int = MODEL_DISCOVERY_INITIAL_ATTEMPTS,
    initial_backoff_seconds: float = MODEL_DISCOVERY_INITIAL_BACKOFF_SECONDS,
) -> Optional[list[dict[str, Any]]]:
    for attempt in range(1, attempts + 1):
        try:
            payload = await fetch_models_payload()
            normalized = _normalize_model_catalog(payload)
            if normalized:
                return normalized
            logger.info(
                "Model discovery returned no valid models on attempt %s/%s",
                attempt,
                attempts,
            )
        except Exception:
            logger.info(
                "Model discovery attempt %s/%s failed",
                attempt,
                attempts,
                exc_info=True,
            )
        if attempt < attempts:
            await asyncio.sleep(initial_backoff_seconds * (2 ** (attempt - 1)))
    return None


def _normalize_model_catalog(payload: dict[str, Any]) -> list[dict[str, Any]]:
    models = payload.get("models", [])
    if not isinstance(models, list):
        return []

    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for model in models:
        if not isinstance(model, dict):
            continue
        name = model.get("name")
        if not isinstance(name, str):
            continue
        stripped_name = name.strip()
        if not stripped_name or stripped_name in seen:
            continue
        seen.add(stripped_name)
        normalized.append(
            {
                "name": stripped_name,
                "location": str(model.get("location", stripped_name)),
                "supportsReasoningEffort": bool(model.get("supportsReasoningEffort", False)),
                "supportsReasoningDisable": bool(model.get("supportsReasoningDisable", False)),
            }
        )
    return normalized


def _reasoning_options_for_model(model_name: str, catalog: list[dict[str, Any]]) -> list[str]:
    _ = model_name
    _ = catalog
    # Keep a stable, explicit reasoning set in the combined model dropdown.
    # ACP clients can then always surface `default` and `disable`.
    return ["default", "low", "medium", "high", "disable"]


def _sanitize_reasoning_level_for_model(
    model_name: str, reasoning_level: str, catalog: list[dict[str, Any]]
) -> str:
    normalized = reasoning_level if reasoning_level in REASONING_LEVEL_IDS else "default"
    entry = next((m for m in catalog if m.get("name") == model_name), None)
    if not isinstance(entry, dict):
        return normalized

    supports_effort = bool(entry.get("supportsReasoningEffort"))
    supports_disable = bool(entry.get("supportsReasoningDisable"))
    if not supports_effort and normalized != "default":
        return "default"
    if normalized == "disable" and not supports_disable:
        return "default"
    return normalized


def _model_options(
    catalog: list[dict[str, Any]],
) -> list[tuple[str, str]]:
    options: list[tuple[str, str]] = []
    for model in catalog:
        if not isinstance(model, dict):
            continue
        raw_name = model.get("name")
        if not isinstance(raw_name, str):
            continue
        model_name = raw_name.strip()
        if not model_name:
            continue
        options.append((model_name, model_name))
    return options


def _available_model_names(catalog: list[dict[str, Any]]) -> list[str]:
    return [model_name for model_name, _ in _model_options(catalog)]


def _model_variants_for_model(model_name: str, catalog: list[dict[str, Any]]) -> list[str]:
    entry = next((m for m in catalog if m.get("name") == model_name), None)
    if not isinstance(entry, dict):
        return []
    supports_effort = bool(entry.get("supportsReasoningEffort"))
    supports_disable = bool(entry.get("supportsReasoningDisable"))
    if not supports_effort:
        return []
    variants = ["low", "medium", "high"]
    if supports_disable:
        variants.append("disable")
    return variants


def _build_available_models(catalog: list[dict[str, Any]]) -> list[tuple[str, str]]:
    available: list[tuple[str, str]] = []
    for model_id, _ in _model_options(catalog):
        available.append((model_id, model_id))
        variants = _model_variants_for_model(model_id, catalog)
        available.extend(
            (f"{model_id}/{variant}", f"{model_id} ({variant})") for variant in variants
        )
    return available


def _format_model_id_with_variant(
    model_id: str, variant: Optional[str], available_variants: list[str]
) -> str:
    if not variant or variant == DEFAULT_VARIANT_VALUE:
        return model_id
    if variant not in available_variants:
        return model_id
    return f"{model_id}/{variant}"


def _parse_model_selection(
    model_selection: str, catalog: list[dict[str, Any]]
) -> tuple[Optional[str], Optional[str]]:
    raw = (model_selection or "").strip()
    if not raw:
        return (None, None)
    if raw.startswith("model/"):
        raw = raw[len("model/") :].strip()
    if raw.startswith("reasoning/"):
        level = raw[len("reasoning/") :].strip().lower()
        return (None, level if level in REASONING_LEVEL_IDS else None)

    available_models = set(_available_model_names(catalog))
    if raw in available_models:
        return (raw, None)

    if "/" in raw:
        segments = raw.split("/")
        candidate_variant = segments[-1].strip().lower()
        base_model = "/".join(segments[:-1]).strip()
        if base_model in available_models:
            available_variants = _model_variants_for_model(base_model, catalog)
            if candidate_variant in available_variants:
                return (base_model, candidate_variant)

    return (raw, None)


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


def _display_uri(uri: str, fragment: dict[str, Any]) -> str:
    if not uri.startswith("brokk://context/fragment/"):
        return uri

    short_description = str(fragment.get("shortDescription", "")).strip()
    if not short_description:
        return uri

    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", short_description).strip("-").lower()
    if not slug:
        return uri
    return f"brokk://context/{slug}"


def _is_brokk_context_uri(uri: str) -> bool:
    return uri.startswith("brokk://context/")


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
                                "uri": _display_uri(uri, fragment),
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
        use_short_description_context: bool = False,
        **kwargs: Any,
    ) -> None:
        await self.ensure_ready()
        executor_session_id = await self._ensure_session(session_id)

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
            session_id=executor_session_id,
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
                    block_uri = str(block["uri"])
                    is_brokk_context = _is_brokk_context_uri(block_uri)
                    if kind != current_kind:
                        current_kind = kind
                        await send_update(
                            session_id,
                            update_agent_message_text(f"\n#### {_chip_kind_label(kind)}\n"),
                        )
                    is_resource_list_kind = kind in {"EDIT", "SUMMARY"}
                    snapshot_text = (
                        str(block["short_description"])
                        if use_short_description_context or is_brokk_context
                        else str(block["text"])
                    )
                    if is_resource_list_kind and not _is_discarded_context(block):
                        await send_update(session_id, update_agent_message_text("- "))
                        if is_brokk_context:
                            await send_update(session_id, update_agent_message_text(snapshot_text))
                        else:
                            await send_update(
                                session_id,
                                build_context_snapshot_update(
                                    block_uri,
                                    str(block["mime_type"]),
                                    snapshot_text,
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
                        else (
                            update_agent_message_text(snapshot_text)
                            if is_brokk_context
                            else build_context_snapshot_update(
                                block_uri,
                                str(block["mime_type"]),
                                snapshot_text,
                            )
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
    ide: str = "intellij",
    vendor: Optional[str] = None,
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
    logger.info("Starting ACP server with IDE profile: %s", ide)

    executor = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
    )
    bridge = BrokkAcpBridge(executor)

    def _patch_acp_router_for_session_config_option() -> None:
        if getattr(acp_agent_router, "_brokk_session_config_patch", False):
            return
        original_build_agent_router = acp_agent_router.build_agent_router

        def patched_build_agent_router(agent: Any, use_unstable_protocol: bool = False) -> Any:
            router = original_build_agent_router(agent, use_unstable_protocol=use_unstable_protocol)
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

    ide_profile = ide.strip().lower() if isinstance(ide, str) else "intellij"

    class BrokkAcpAgent(Agent):
        def __init__(self) -> None:
            self.client: Optional[Any] = None
            self._mode_by_session: dict[str, str] = {}
            self._model_by_session: dict[str, str] = {}
            self._reasoning_by_session: dict[str, str] = {}
            self._cwd_by_session: dict[str, str] = {}
            self._model_catalog_by_session: dict[str, list[dict[str, Any]]] = {}
            self._catalog_is_fallback_by_session: dict[str, bool] = {}
            self._is_zed = ide_profile == "zed"

            # Load ACP defaults once on agent init
            acp_defaults = load_acp_defaults()
            self._default_model_id: str = acp_defaults.default_model or DEFAULT_MODEL_SELECTION
            self._default_reasoning_level: str = (
                acp_defaults.default_reasoning or DEFAULT_REASONING_LEVEL
            )

        async def _refresh_model_catalog(
            self,
            session_id: str,
            attempts: int = MODEL_DISCOVERY_INITIAL_ATTEMPTS,
        ) -> None:
            async def fetch_payload() -> dict[str, Any]:
                await bridge.ensure_ready()
                return await bridge.executor.get_models()

            normalized = await _fetch_normalized_catalog_with_retries(
                fetch_payload,
                attempts=attempts,
            )
            if normalized:
                self._model_catalog_by_session[session_id] = normalized
                self._catalog_is_fallback_by_session[session_id] = False
                return

            existing = self._model_catalog_by_session.get(session_id)
            if existing and not self._catalog_is_fallback_by_session.get(session_id, True):
                logger.info(
                    "Model discovery unavailable; keeping previously discovered model catalog "
                    "for session %s",
                    session_id,
                )
                return

            logger.info("Model discovery unavailable; using fallback catalog")
            self._model_catalog_by_session[session_id] = _fallback_model_catalog()
            self._catalog_is_fallback_by_session[session_id] = True

        async def _refresh_model_catalog_if_fallback(self, session_id: str) -> None:
            if not self._catalog_is_fallback_by_session.get(session_id, False):
                return
            await self._refresh_model_catalog(
                session_id,
                attempts=MODEL_DISCOVERY_RECOVERY_ATTEMPTS,
            )

        def _catalog_for_session(self, session_id: str) -> list[dict[str, Any]]:
            return self._model_catalog_by_session.get(session_id, _fallback_model_catalog())

        def _current_model_selection(self, session_id: str) -> str:
            catalog = self._catalog_for_session(session_id)
            model_names = _available_model_names(catalog)
            current_model = self._model_by_session.get(session_id, DEFAULT_MODEL_SELECTION)
            if current_model not in model_names and model_names:
                current_model = model_names[0]
                self._model_by_session[session_id] = current_model
            current_reasoning = self._reasoning_by_session.get(session_id, DEFAULT_REASONING_LEVEL)
            reasoning_options = _reasoning_options_for_model(current_model, catalog)
            if current_reasoning not in reasoning_options:
                current_reasoning = "default"
                self._reasoning_by_session[session_id] = current_reasoning
            model_options = _model_options(catalog)
            option_values = {value for value, _ in model_options}
            if current_model not in option_values and model_options:
                current_model = model_options[0][0]
                self._model_by_session[session_id] = current_model
            return current_model

        def _model_state_for_session(self, session_id: str) -> SessionModelState:
            catalog = self._catalog_for_session(session_id)
            current_model = self._current_model_selection(session_id)
            if self._is_zed:
                variants = _model_options(catalog)
                if not variants:
                    variants = [(DEFAULT_MODEL_SELECTION, DEFAULT_MODEL_SELECTION)]
                current_model_id = current_model
            else:
                variants = _build_available_models(catalog)
                if not variants:
                    variants = [(DEFAULT_MODEL_SELECTION, DEFAULT_MODEL_SELECTION)]
                current_reasoning = self._reasoning_by_session.get(
                    session_id, DEFAULT_REASONING_LEVEL
                )
                current_model_id = _format_model_id_with_variant(
                    current_model,
                    current_reasoning,
                    _model_variants_for_model(current_model, catalog),
                )
            return SessionModelState(
                available_models=[
                    ModelInfo(model_id=value, name=label) for value, label in variants
                ],
                current_model_id=current_model_id,
            )

        def _config_options_for_session(self, session_id: str) -> list[Any]:
            current_mode = self._mode_by_session.get(session_id, "LUTZ")
            options = [
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
                            for mode in MODE_OPTIONS
                        ],
                    }
                ),
            ]
            if self._is_zed:
                current_model = self._model_by_session.get(session_id, DEFAULT_MODEL_SELECTION)
                current_reasoning = self._reasoning_by_session.get(
                    session_id, DEFAULT_REASONING_LEVEL
                )
                model_options = _model_options(self._catalog_for_session(session_id))
                options.append(
                    SessionConfigOption.model_validate(
                        {
                            "type": "select",
                            "id": "model",
                            "name": "Model",
                            "description": "Choose model",
                            "category": "model",
                            "currentValue": current_model,
                            "options": [
                                SessionConfigSelectOption(value=model_id, name=model_name)
                                for model_id, model_name in model_options
                            ],
                        }
                    )
                )
                reasoning_options = _reasoning_options_for_model(
                    current_model, self._catalog_for_session(session_id)
                )
                options.append(
                    SessionConfigOption.model_validate(
                        {
                            "type": "select",
                            "id": "reasoning",
                            "name": "Reasoning",
                            "description": "Choose reasoning level",
                            "category": "model",
                            "currentValue": (
                                current_reasoning
                                if current_reasoning in reasoning_options
                                else "default"
                            ),
                            "options": [
                                SessionConfigSelectOption(value=level, name=level)
                                for level in reasoning_options
                            ],
                        }
                    )
                )
            return options

        def _variant_meta_for_session(self, session_id: str) -> dict[str, Any]:
            model_id = self._model_by_session.get(session_id, DEFAULT_MODEL_SELECTION)
            variant = self._reasoning_by_session.get(session_id, DEFAULT_REASONING_LEVEL)
            if variant == DEFAULT_VARIANT_VALUE:
                variant = None
            available_variants = _model_variants_for_model(
                model_id, self._catalog_for_session(session_id)
            )
            return {
                "brokk": {
                    "modelId": model_id,
                    "variant": variant,
                    "availableVariants": available_variants,
                }
            }

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
            # Seed session model/reasoning from persisted defaults; validation happens after
            self._model_by_session[session_id] = self._default_model_id or DEFAULT_MODEL_SELECTION
            self._reasoning_by_session[session_id] = (
                self._default_reasoning_level or DEFAULT_REASONING_LEVEL
            )
            self._cwd_by_session[session_id] = cwd
            await self._refresh_model_catalog(session_id)

            # After refreshing catalog, ensure persisted defaults are valid for this catalog.
            catalog = self._catalog_for_session(session_id)
            available_models = _available_model_names(catalog)
            available_set = set(available_models)
            # Validate model default
            if self._model_by_session[session_id] not in available_set and available_models:
                # fallback to first available model and persist that choice
                fallback = available_models[0]
                logger.info(
                    "Persisted ACP default model %s not available; falling back to %s",
                    self._model_by_session[session_id],
                    fallback,
                )
                self._model_by_session[session_id] = fallback
                self._default_model_id = fallback
                save_acp_defaults(
                    AcpDefaults(
                        default_model=self._default_model_id,
                        default_reasoning=self._default_reasoning_level,
                    )
                )

            # Validate reasoning default against model capabilities
            sanitized = _sanitize_reasoning_level_for_model(
                self._model_by_session[session_id],
                self._reasoning_by_session[session_id],
                catalog,
            )
            if sanitized != self._reasoning_by_session[session_id]:
                logger.info(
                    "Persisted ACP reasoning %s invalid for model %s; falling back to %s",
                    self._reasoning_by_session[session_id],
                    self._model_by_session[session_id],
                    sanitized,
                )
                self._reasoning_by_session[session_id] = sanitized
                self._default_reasoning_level = sanitized
                save_acp_defaults(
                    AcpDefaults(
                        default_model=self._default_model_id,
                        default_reasoning=self._default_reasoning_level,
                    )
                )

            model_state = self._model_state_for_session(session_id)
            return NewSessionResponse(
                session_id=session_id,
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="CODE", name="CODE"),
                        SessionMode(id="ASK", name="ASK"),
                    ],
                    current_mode_id="LUTZ",
                ),
                models=model_state,
                config_options=self._config_options_for_session(session_id),
                _meta=self._variant_meta_for_session(session_id),
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
            await self._refresh_model_catalog(session_id)
            model_state = self._model_state_for_session(session_id)
            return LoadSessionResponse(
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="CODE", name="CODE"),
                        SessionMode(id="ASK", name="ASK"),
                    ],
                    current_mode_id=self._mode_by_session.get(session_id, "LUTZ"),
                ),
                models=model_state,
                config_options=self._config_options_for_session(session_id),
                _meta=self._variant_meta_for_session(session_id),
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
            await self._refresh_model_catalog_if_fallback(session_id)
            catalog = self._catalog_for_session(session_id)
            selected_model, selected_reasoning = _parse_model_selection(model_id, catalog)
            if selected_model is None and selected_reasoning is None:
                selected_model, selected_reasoning = resolve_model_selection(model_id)
            available = _available_model_names(catalog)
            available_set = set(available)
            fallback_model = available[0] if available else DEFAULT_MODEL_SELECTION
            chosen_model = selected_model if selected_model in available_set else fallback_model
            self._model_by_session[session_id] = chosen_model

            # Update persisted default model to the newly selected valid model
            try:
                self._default_model_id = chosen_model
                save_acp_defaults(
                    AcpDefaults(
                        default_model=self._default_model_id,
                        default_reasoning=self._default_reasoning_level,
                    )
                )
            except Exception:
                logger.exception("Failed to persist ACP default model change")

            if selected_reasoning:
                self._reasoning_by_session[session_id] = selected_reasoning
            elif not self._is_zed:
                self._reasoning_by_session[session_id] = DEFAULT_VARIANT_VALUE
            return SetSessionModelResponse(_meta=self._variant_meta_for_session(session_id))

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
                await self._refresh_model_catalog_if_fallback(session_id)
                selected_model, selected_reasoning = resolve_model_selection(value)
                # Validate against catalog
                catalog = self._catalog_for_session(session_id)
                available = _available_model_names(catalog)
                available_set = set(available)
                fallback_model = available[0] if available else DEFAULT_MODEL_SELECTION
                chosen_model = selected_model if selected_model in available_set else fallback_model
                self._model_by_session[session_id] = chosen_model
                # Persist model change
                try:
                    self._default_model_id = chosen_model
                    save_acp_defaults(
                        AcpDefaults(
                            default_model=self._default_model_id,
                            default_reasoning=self._default_reasoning_level,
                        )
                    )
                except Exception:
                    logger.exception("Failed to persist ACP default model change")
                if selected_reasoning:
                    self._reasoning_by_session[session_id] = selected_reasoning
            elif (
                config_id in {THOUGHT_LEVEL_CONFIG_ID, "reasoning_effort", "reasoning"}
                and value in REASONING_LEVEL_IDS
            ):
                # Sanitize for model capabilities
                catalog = self._catalog_for_session(session_id)
                model = self._model_by_session.get(session_id, self._default_model_id)
                sanitized = _sanitize_reasoning_level_for_model(model, value, catalog)
                self._reasoning_by_session[session_id] = sanitized
                # Persist reasoning change
                try:
                    self._default_reasoning_level = sanitized
                    save_acp_defaults(
                        AcpDefaults(
                            default_model=self._default_model_id,
                            default_reasoning=self._default_reasoning_level,
                        )
                    )
                except Exception:
                    logger.exception("Failed to persist ACP default reasoning change")
            options = self._config_options_for_session(session_id)
            return SetSessionConfigOptionResponse(config_options=options)

        async def prompt(self, prompt: Any, session_id: str, **kwargs: Any) -> Any:
            await self._refresh_model_catalog_if_fallback(session_id)
            mode = normalize_mode(kwargs.get("mode") or self._mode_by_session.get(session_id))
            planner_model = (
                kwargs.get("planner_model")
                or kwargs.get("plannerModel")
                or self._model_by_session.get(session_id)
                or DEFAULT_MODEL_SELECTION
            )
            catalog = self._catalog_for_session(session_id)
            parsed_model, parsed_reasoning = _parse_model_selection(str(planner_model), catalog)
            if parsed_model:
                planner_model = parsed_model
            planner_model_id, selected_reasoning_level = resolve_model_selection(planner_model)
            available_models = _available_model_names(catalog)
            available_set = set(available_models)
            if planner_model_id not in available_set:
                planner_model_id = (
                    available_models[0] if available_models else DEFAULT_MODEL_SELECTION
                )
            code_model = (
                kwargs.get("code_model") or kwargs.get("codeModel") or "gemini-3-flash-preview"
            )
            reasoning_level = (
                kwargs.get("reasoning_level")
                or kwargs.get("reasoningLevel")
                or parsed_reasoning
                or selected_reasoning_level
                or self._reasoning_by_session.get(session_id)
                or "low"
            )
            reasoning_level = _sanitize_reasoning_level_for_model(
                planner_model_id, str(reasoning_level), catalog
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
                build_context_snapshot_update=(
                    (lambda uri, mime_type, text: update_agent_message_text(text))
                    if ide_profile == "intellij"
                    else (
                        lambda uri, mime_type, text: update_agent_message(
                            resource_block(
                                embedded_text_resource(
                                    uri=uri,
                                    text=text,
                                    mime_type=mime_type,
                                )
                            )
                        )
                    )
                ),
                use_short_description_context=(ide_profile == "intellij"),
            )
            return PromptResponse(stop_reason="end_turn")

        async def cancel(self, *args: Any, **kwargs: Any) -> None:
            await bridge.cancel(*args, **kwargs)

    try:
        await run_agent(BrokkAcpAgent(), use_unstable_protocol=True)
    finally:
        await executor.stop()
