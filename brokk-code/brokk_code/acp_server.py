import asyncio
import json
import logging
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional
from urllib.parse import unquote, urlparse

from brokk_code import __version__
from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.settings import Settings
from brokk_code.widgets.token_bar import get_token_bar_markdown

logger = logging.getLogger(__name__)

VALID_MODES = {"LUTZ", "ASK", "CODE", "PLAN"}
MODE_OPTIONS = ("LUTZ", "CODE", "ASK", "PLAN")
BASE_MODEL_IDS = ("gpt-5.3-codex", "gemini-3-flash-preview")
REASONING_LEVEL_IDS = ("low", "medium", "high", "disable", "default")
DEFAULT_MODEL_SELECTION = "gpt-5.3-codex"
DEFAULT_REASONING_LEVEL = "medium"
THOUGHT_LEVEL_CONFIG_ID = "thought_level"
DEFAULT_VARIANT_VALUE = "default"
MODEL_DISCOVERY_INITIAL_ATTEMPTS = 4
MODEL_DISCOVERY_RECOVERY_ATTEMPTS = 2
MODEL_DISCOVERY_INITIAL_BACKOFF_SECONDS = 0.2


@dataclass(frozen=True)
class ClientProfile:
    """Runtime configuration derived from ACP client capabilities and info."""

    is_zed: bool = False
    supports_terminal: bool = False


def resolve_client_profile(client_capabilities: Any, client_info: Any) -> ClientProfile:
    """Derives a ClientProfile from ACP initialize inputs."""
    client_name = ""
    if hasattr(client_info, "name"):
        client_name = str(client_info.name).lower()
    elif isinstance(client_info, dict):
        client_name = str(client_info.get("name", "")).lower()

    # Identify Zed specifically for its unique Markdown/Rich rendering capabilities.
    is_zed = "zed" in client_name

    # Determine terminal support from capabilities.
    supports_terminal = False
    if hasattr(client_capabilities, "terminal"):
        supports_terminal = bool(client_capabilities.terminal)
    elif isinstance(client_capabilities, dict):
        supports_terminal = bool(client_capabilities.get("terminal"))

    if is_zed:
        return ClientProfile(
            is_zed=True,
            supports_terminal=supports_terminal,
        )

    # Default/IntelliJ-like behavior: conservative rendering.
    return ClientProfile(
        is_zed=False,
        supports_terminal=supports_terminal,
    )


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
        return DEFAULT_MODEL_SELECTION, None
    if "#r=" not in raw:
        return raw, None
    model_id, reasoning = raw.split("#r=", 1)
    normalized_reasoning = reasoning.strip().lower()
    if normalized_reasoning not in REASONING_LEVEL_IDS:
        return model_id.strip() or DEFAULT_MODEL_SELECTION, None
    return model_id.strip() or DEFAULT_MODEL_SELECTION, normalized_reasoning


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


def get_slash_command(text: str) -> Optional[str]:
    """Returns the slash command if the text starts with one (e.g. '/context'), else None."""
    trimmed = text.strip()
    if not trimmed.startswith("/"):
        return None
    # Match first word: e.g. "/context some args" -> "/context"
    cmd = trimmed.split(maxsplit=1)[0].lower()
    if cmd == "/context":
        return cmd
    return None


def acp_slash_commands() -> list[dict[str, str]]:
    """ACP slash command descriptors advertised to clients.

    ACP command names are advertised without the leading slash; clients render
    and invoke them as `/name` in prompt text.
    """
    return [
        {
            "name": "context",
            "description": "Show current context snapshot",
        }
    ]


def extract_resource_file_paths(prompt: Any, cwd: str) -> list[str]:
    """Extract workspace-relative file paths from EmbeddedResource and ResourceLink blocks."""
    if not prompt or isinstance(prompt, str):
        return []
    cwd_path = Path(cwd) if cwd else None
    paths: list[str] = []

    def _get_attr_or_key(obj: Any, key: str) -> Any:
        if isinstance(obj, dict):
            return obj.get(key)
        return getattr(obj, key, None)

    def _resource_uri(resource: Any) -> Optional[str]:
        uri = _get_attr_or_key(resource, "uri")
        if isinstance(uri, str):
            return uri
        nested = _get_attr_or_key(resource, "resource")
        if nested is None:
            return None
        nested_uri = _get_attr_or_key(nested, "uri")
        if isinstance(nested_uri, str):
            return nested_uri
        return None

    for block in prompt:
        block_type = _get_attr_or_key(block, "type")
        uri: Optional[str] = None
        if block_type in {"embedded_resource", "resource"}:
            resource = _get_attr_or_key(block, "resource")
            if resource is not None:
                uri = _resource_uri(resource)
        elif block_type == "resource_link":
            uri = _get_attr_or_key(block, "uri")
        if not isinstance(uri, str):
            continue
        uri = uri.strip()
        if not uri:
            continue

        parsed = urlparse(uri)
        file_path: Optional[Path] = None
        if parsed.scheme == "file":
            file_path = Path(unquote(parsed.path))
        elif parsed.scheme == "":
            rel_path = Path(unquote(uri))
            if rel_path.is_absolute():
                file_path = rel_path
            elif cwd_path is not None:
                file_path = cwd_path / rel_path
        if file_path is None:
            continue
        try:
            if cwd_path:
                paths.append(file_path.relative_to(cwd_path).as_posix())
            else:
                paths.append(file_path.as_posix())
        except (ValueError, TypeError):
            logger.debug("Could not make %s relative to %s", uri, cwd)
    return list(dict.fromkeys(paths))


def _extract_fragment_ids(resp: Any) -> list[str]:
    if not isinstance(resp, dict):
        return []
    added = resp.get("added")
    if not isinstance(added, list):
        return []
    ids: list[str] = []
    for item in added:
        if not isinstance(item, dict):
            continue
        raw_id = item.get("id", item.get("fragmentId"))
        if raw_id is not None:
            frag_id = str(raw_id).strip()
            if frag_id:
                ids.append(frag_id)
    return list(dict.fromkeys(ids))


def _is_truthy(value: Any) -> bool:
    """Robustly normalize truthiness for string and boolean payloads."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes"}
    return bool(value)


def map_executor_event_to_session_update(
    event: dict[str, Any],
    update_agent_message_text: Callable[[str], Any],
    update_agent_thought_text: Optional[Callable[[str], Any]] = None,
) -> Optional[Any]:
    """Map executor events into clean ACP message or thought updates."""
    event_type = event.get("type")
    data = event.get("data", {})
    if not isinstance(data, dict):
        return None

    match event_type:
        case "LLM_TOKEN":
            token = str(data.get("token", "")).replace("â€¦", "...")
            if not token:
                return None

            raw_is_reasoning = data.get("isReasoning", False)
            if isinstance(raw_is_reasoning, str):
                is_reasoning = raw_is_reasoning.strip().lower() in {"true", "1", "yes"}
            elif isinstance(raw_is_reasoning, bool):
                is_reasoning = raw_is_reasoning
            else:
                is_reasoning = bool(raw_is_reasoning)

            if is_reasoning and update_agent_thought_text:
                return update_agent_thought_text(token)
            return update_agent_message_text(token)

        case "ERROR":
            msg = data.get("message", "Unknown error")
            return update_agent_message_text(f"\n\n**Error:** {msg}\n\n")

        case "NOTIFICATION":
            level = data.get("level", "INFO")
            msg = data.get("message", "")
            if not msg or level in ("STATE", "COST"):
                return None
            # Only surface critical or high-level notifications to ACP users.
            if level == "ERROR":
                return update_agent_message_text(f"\n\n**Error:** {msg}\n\n")
            return update_agent_message_text(f"\n\n_{msg}_\n\n")

        case _:
            # Suppress internal TOOL_CALL, TOOL_OUTPUT, and STATE_HINT events for ACP.
            # These are noisy and usually handled via LLM tokens or final output.
            return None


def conversation_payload_to_session_updates(
    conversation_data: dict[str, Any],
    update_user_message_text: Callable[[str], Any],
    update_agent_message_text: Callable[[str], Any],
    update_agent_thought_text: Optional[Callable[[str], Any]] = None,
) -> list[Any]:
    """Map executor conversation payload into ACP session updates for replay."""
    entries = conversation_data.get("entries")
    if not isinstance(entries, list):
        return []

    updates: list[Any] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue

        messages = entry.get("messages")
        if isinstance(messages, list):
            for msg in messages:
                if not isinstance(msg, dict):
                    continue

                role = str(msg.get("role", "")).strip().lower()
                text = msg.get("text")
                if not isinstance(text, str):
                    text = ""

                reasoning = msg.get("reasoning")
                if update_agent_thought_text and isinstance(reasoning, str) and reasoning.strip():
                    updates.append(update_agent_thought_text(reasoning.strip()))

                stripped_text = text.strip()
                if not stripped_text:
                    continue

                if role == "user":
                    updates.append(update_user_message_text(text))
                else:
                    updates.append(update_agent_message_text(text))
            continue

        summary = entry.get("summary")
        if isinstance(summary, str) and summary.strip():
            updates.append(update_agent_message_text(summary))

    return updates


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


def _to_iso8601_utc(value: Any) -> Optional[str]:
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    if isinstance(value, (int, float)):
        seconds = float(value)
        # Headless API timestamps are epoch-millis; fall back to seconds for small values.
        if seconds > 10_000_000_000:
            seconds = seconds / 1000.0
        return datetime.fromtimestamp(seconds, tz=UTC).isoformat().replace("+00:00", "Z")
    return None


def _session_id_from_entry(entry: Any) -> Optional[str]:
    if not isinstance(entry, dict):
        return None
    raw = entry.get("id")
    if raw is None:
        raw = entry.get("sessionId")
    if raw is None:
        raw = entry.get("session_id")
    if raw is None:
        return None
    sid = str(raw).strip()
    return sid or None


def _known_session_ids(entries: Any) -> set[str]:
    if not isinstance(entries, list):
        return set()
    ids: set[str] = set()
    for entry in entries:
        sid = _session_id_from_entry(entry)
        if sid:
            ids.add(sid)
    return ids


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

    async def start_and_create_session(self, name: str) -> str:
        """Starts the executor (if needed) and creates the first session to satisfy readiness."""
        if self._started:
            return await self.executor.create_session(name=name)

        await self.executor.start()
        session_id = await self.executor.create_session(name=name)
        ready = await self.executor.wait_ready()
        if not ready:
            raise ExecutorError("Brokk executor failed readiness check")
        self._started = True
        return session_id

    async def _ensure_session(self, acp_session_id: str) -> str:
        existing = self._acp_to_brokk_session.get(acp_session_id)
        if existing:
            await self._switch_executor_session(existing)
            return existing
        if await self._switch_executor_session(acp_session_id):
            self._acp_to_brokk_session[acp_session_id] = acp_session_id
            return acp_session_id
        session_id = await self.executor.create_session(name=f"ACP Session {acp_session_id}")
        self._acp_to_brokk_session[acp_session_id] = session_id
        return session_id

    async def _switch_executor_session(self, session_id: str) -> bool:
        switch_session = getattr(self.executor, "switch_session", None)
        if not callable(switch_session):
            return False
        try:
            await switch_session(session_id)
            return True
        except Exception:
            logger.debug("Failed to switch executor session %s", session_id, exc_info=True)
            return False

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
        cwd: str = "",
    ) -> None:
        await self.ensure_ready()
        executor_session_id = await self._ensure_session(session_id)
        await self._switch_executor_session(executor_session_id)

        prompt_text = extract_prompt_text(prompt)
        if not prompt_text:
            raise ExecutorError("Prompt must contain at least one non-empty text block.")

        command = get_slash_command(prompt_text)
        if command:
            await self._handle_command(
                command,
                session_id,
                send_update=send_update,
                update_agent_message_text=update_agent_message_text,
            )
            return

        await self._handle_model_job(
            prompt=prompt,
            prompt_text=prompt_text,
            session_id=session_id,
            executor_session_id=executor_session_id,
            mode=mode,
            planner_model=planner_model,
            code_model=code_model,
            reasoning_level=reasoning_level,
            reasoning_level_code=reasoning_level_code,
            send_update=send_update,
            update_agent_message_text=update_agent_message_text,
            cwd=cwd,
        )

    async def _handle_command(
        self,
        command: str,
        session_id: str,
        send_update: Callable[[str, Any], Awaitable[Any]],
        update_agent_message_text: Callable[[str], Any],
    ) -> None:
        if command == "/context":
            ctx = await self.executor.get_context()
            fragments = ctx.get("fragments", [])
            used_tokens = int(ctx.get("usedTokens", 0) or 0)
            max_tokens = ctx.get("maxTokens", 0)
            fragment_list = fragments if isinstance(fragments, list) else []
            base_tokens = int(max_tokens or 0)
            if base_tokens <= 0:
                base_tokens = sum(int(f.get("tokens", 0) or 0) for f in fragment_list)
            if base_tokens <= 0:
                base_tokens = 1

            def _fragment_row(fragment: Any) -> tuple[str, int, float]:
                if not isinstance(fragment, dict):
                    return ("Unknown", 0, 0.0)
                name = str(fragment.get("shortDescription") or fragment.get("id") or "Unknown")
                tokens = int(fragment.get("tokens", 0) or 0)
                pct = (tokens / base_tokens) * 100
                return (name, tokens, pct)

            rows = [_fragment_row(f) for f in fragment_list]
            rows.sort(key=lambda row: row[2], reverse=True)
            top_rows = rows[:4]
            remainder = rows[4:]
            if remainder:
                other_tokens = sum(tokens for _name, tokens, _pct in remainder)
                other_pct = sum(pct for _name, _tokens, pct in remainder)
                top_rows.append(("(other)", other_tokens, other_pct))

            lines = [
                "| Fragment | Tokens | % Context |",
                "|---|---:|---:|",
            ]
            lines.extend(f"| {name} | {tokens:,} | {pct:.2f}% |" for name, tokens, pct in top_rows)
            if not top_rows:
                lines.append("| (none) | 0 | 0.00% |")
            token_bar_md = get_token_bar_markdown(
                used_tokens=used_tokens,
                max_tokens=int(max_tokens or 0),
                fragments=fragment_list,
            )
            if token_bar_md:
                lines.append("")
                lines.append(f"**Total Tokens:** {used_tokens:,} / {int(max_tokens or 0):,}")
                lines.append("")
                lines.append(token_bar_md)

            await send_update(session_id, update_agent_message_text("\n".join(lines)))

    async def _handle_model_job(
        self,
        prompt: Any,
        prompt_text: str,
        session_id: str,
        executor_session_id: str,
        mode: str,
        planner_model: str,
        code_model: Optional[str],
        reasoning_level: Optional[str],
        reasoning_level_code: Optional[str],
        send_update: Callable[[str, Any], Awaitable[Any]],
        update_agent_message_text: Callable[[str], Any],
        cwd: str = "",
    ) -> None:
        # Add any @-mentioned files from ACP embedded/linked resource blocks to context.
        attached_fragment_ids: list[str] = []
        file_paths = extract_resource_file_paths(prompt, cwd)
        if file_paths:
            try:
                resp = await self.executor.add_context_files(file_paths)
                attached_fragment_ids.extend(_extract_fragment_ids(resp))
            except Exception:
                logger.exception("Failed attaching ACP resource file paths to context")

        try:
            job_id = await self.executor.submit_job(
                task_input=prompt_text,
                planner_model=planner_model,
                code_model=code_model,
                reasoning_level=reasoning_level,
                reasoning_level_code=reasoning_level_code,
                mode=mode,
                session_id=executor_session_id,
            )
        except Exception:
            if attached_fragment_ids:
                try:
                    await self.executor.drop_context_fragments(attached_fragment_ids)
                except Exception:
                    logger.exception(
                        "Failed to rollback context fragments " + "after submit_job failure"
                    )
            raise
        self._active_job_by_session[session_id] = job_id

        try:
            from acp import update_agent_thought_text

            async for event in self.executor.stream_events(job_id):
                update = map_executor_event_to_session_update(
                    event,
                    update_agent_message_text,
                    update_agent_thought_text=update_agent_thought_text,
                )
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
            run_agent,
            update_agent_message_text,
            update_agent_thought_text,
            update_user_message_text,
        )
        from acp.agent import connection as acp_agent_connection
        from acp.agent import router as acp_agent_router
        from acp.helpers import update_available_commands
        from acp.meta import AGENT_METHODS
        from acp.schema import (
            AgentCapabilities,
            AvailableCommand,
            Implementation,
            ListSessionsResponse,
            ModelInfo,
            PromptCapabilities,
            ResumeSessionResponse,
            SessionCapabilities,
            SessionConfigOption,
            SessionConfigSelectOption,
            SessionInfo,
            SessionListCapabilities,
            SessionMode,
            SessionModelState,
            SessionModeState,
            SessionResumeCapabilities,
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
    logger.info("Starting ACP server")

    # Note: The ExecutorManager launches the Java HeadlessExecutorMain with a dedicated
    # stdin pipe. HeadlessExecutorMain monitors System.in for EOF and will initiate a
    # controlled shutdown if the ACP Python process (the parent) exits or its stdin is
    # closed by the IDE (for example, when IntelliJ terminates/restarts the run profile).
    # This stdin-based parent-death signal prevents orphaned Java executor processes in
    # cases where Python's finally blocks may not run (e.g., abrupt IDE lifecycle events).
    settings = Settings.load()
    executor = ExecutorManager(
        workspace_dir=workspace_dir,
        jar_path=jar_path,
        executor_version=executor_version,
        executor_snapshot=executor_snapshot,
        vendor=vendor,
        exit_on_stdin_eof=True,
        brokk_api_key=settings.get_brokk_api_key(),
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

    class BrokkAcpAgent(Agent):
        def __init__(self) -> None:
            self.client: Optional[Any] = None
            self._mode_by_session: dict[str, str] = {}
            self._model_by_session: dict[str, str] = {}
            self._reasoning_by_session: dict[str, str] = {}
            self._cwd_by_session: dict[str, str] = {}
            self._model_catalog_by_session: dict[str, list[dict[str, Any]]] = {}
            self._catalog_is_fallback_by_session: dict[str, bool] = {}
            self._profile = resolve_client_profile(None, None)
            self._replay_tasks: set[asyncio.Task[Any]] = set()
            self._commands_tasks: set[asyncio.Task[Any]] = set()

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
            if self._profile.is_zed:
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
            if self._profile.is_zed:
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

        async def _replay_loaded_session(self, session_id: str) -> None:
            if not self.client:
                return
            try:
                conversation_data = await bridge.executor.get_conversation()
            except Exception:
                logger.warning(
                    "Failed to fetch conversation replay for session %s",
                    session_id,
                    exc_info=True,
                )
                return

            updates = conversation_payload_to_session_updates(
                conversation_data,
                update_user_message_text=update_user_message_text,
                update_agent_message_text=update_agent_message_text,
                update_agent_thought_text=update_agent_thought_text,
            )
            logger.info("Replaying %s ACP chat updates for session %s", len(updates), session_id)
            for update in updates:
                await self.client.session_update(session_id, update)

        def _schedule_replay_loaded_session(self, session_id: str) -> None:
            async def _run() -> None:
                # Yield so the load/resume response can be delivered first.
                await asyncio.sleep(0)
                await self._replay_loaded_session(session_id)

            task = asyncio.create_task(_run())
            self._replay_tasks.add(task)

            def _done_callback(done: asyncio.Task[Any]) -> None:
                self._replay_tasks.discard(done)
                try:
                    done.result()
                except Exception:
                    logger.warning(
                        "Session replay task failed for session %s",
                        session_id,
                        exc_info=True,
                    )

            task.add_done_callback(_done_callback)

        def _schedule_available_commands_update(self, session_id: str) -> None:
            async def _run() -> None:
                # Yield so the session response can be delivered first.
                await asyncio.sleep(0)
                if not self.client:
                    return
                commands = [
                    AvailableCommand(name=cmd["name"], description=cmd["description"])
                    for cmd in acp_slash_commands()
                ]
                await self.client.session_update(session_id, update_available_commands(commands))

            task = asyncio.create_task(_run())
            self._commands_tasks.add(task)

            def _done_callback(done: asyncio.Task[Any]) -> None:
                self._commands_tasks.discard(done)
                try:
                    done.result()
                except Exception:
                    logger.warning(
                        "Command advertisement task failed for session %s",
                        session_id,
                        exc_info=True,
                    )

            task.add_done_callback(_done_callback)

        def _ensure_session_defaults(self, session_id: str, cwd: Optional[str] = None) -> None:
            if session_id not in self._mode_by_session:
                self._mode_by_session[session_id] = "LUTZ"
            if session_id not in self._model_by_session:
                self._model_by_session[session_id] = (
                    self._default_model_id or DEFAULT_MODEL_SELECTION
                )
            if session_id not in self._reasoning_by_session:
                self._reasoning_by_session[session_id] = (
                    self._default_reasoning_level or DEFAULT_REASONING_LEVEL
                )
            if cwd is not None:
                self._cwd_by_session[session_id] = cwd
            elif session_id not in self._cwd_by_session:
                self._cwd_by_session[session_id] = str(workspace_dir)

        def on_connect(self, client: Any) -> None:
            self.client = client

        async def initialize(
            self,
            protocol_version: int,
            client_capabilities: Any = None,
            client_info: Any = None,
            **kwargs: Any,
        ) -> InitializeResponse:
            self._profile = resolve_client_profile(client_capabilities, client_info)
            logger.info("ACP Client Profile resolved: %s", self._profile)

            return InitializeResponse(
                protocol_version=protocol_version,
                agent_info=Implementation(name="brokk", version=__version__),
                agent_capabilities=AgentCapabilities(
                    load_session=True,
                    prompt_capabilities=PromptCapabilities(embedded_context=True),
                    session_capabilities=SessionCapabilities(
                        list=SessionListCapabilities(),
                        resume=SessionResumeCapabilities(),
                    ),
                ),
            )

        async def new_session(
            self,
            cwd: str,
            mcp_servers: Optional[list[Any]] = None,
            **kwargs: Any,
        ) -> NewSessionResponse:
            del mcp_servers
            requested_name = str(kwargs.get("title") or kwargs.get("name") or "ACP Session").strip()
            session_name = requested_name or "ACP Session"
            session_id = await bridge.start_and_create_session(name=session_name)
            self._ensure_session_defaults(session_id, cwd)
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
            self._schedule_available_commands_update(session_id)
            return NewSessionResponse(
                session_id=session_id,
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="CODE", name="CODE"),
                        SessionMode(id="ASK", name="ASK"),
                        SessionMode(id="PLAN", name="PLAN"),
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
            await bridge.ensure_ready()
            requested_session_id = session_id
            sessions_payload = await bridge.executor.list_sessions()
            known_session_ids = _known_session_ids(sessions_payload.get("sessions", []))
            if requested_session_id not in known_session_ids:
                return None
            await bridge.executor.switch_session(requested_session_id)
            self._ensure_session_defaults(requested_session_id, cwd)
            await self._refresh_model_catalog(requested_session_id)
            self._schedule_replay_loaded_session(requested_session_id)
            self._schedule_available_commands_update(requested_session_id)
            model_state = self._model_state_for_session(requested_session_id)
            return LoadSessionResponse(
                modes=SessionModeState(
                    available_modes=[
                        SessionMode(id="LUTZ", name="LUTZ"),
                        SessionMode(id="CODE", name="CODE"),
                        SessionMode(id="ASK", name="ASK"),
                        SessionMode(id="PLAN", name="PLAN"),
                    ],
                    current_mode_id=self._mode_by_session.get(requested_session_id, "LUTZ"),
                ),
                models=model_state,
                config_options=self._config_options_for_session(requested_session_id),
                _meta=self._variant_meta_for_session(requested_session_id),
            )

        async def resume_session(
            self,
            cwd: str,
            session_id: str,
            mcp_servers: Optional[list[Any]] = None,
            **kwargs: Any,
        ) -> ResumeSessionResponse:
            resumed = await self.load_session(
                cwd=cwd,
                session_id=session_id,
                mcp_servers=mcp_servers,
                **kwargs,
            )
            if resumed is None:
                raise ExecutorError(f"Session not found: {session_id}")
            return ResumeSessionResponse(
                modes=resumed.modes,
                models=resumed.models,
                config_options=resumed.config_options,
                _meta=resumed.field_meta,
            )

        async def list_sessions(
            self,
            cursor: Optional[str] = None,
            cwd: Optional[str] = None,
            **kwargs: Any,
        ) -> ListSessionsResponse:
            del cursor, kwargs
            await bridge.ensure_ready()
            sessions_payload = await bridge.executor.list_sessions()
            executor_sessions = sessions_payload.get("sessions", [])
            sessions = []
            for entry in executor_sessions:
                session_id = _session_id_from_entry(entry)
                if not session_id:
                    continue
                self._ensure_session_defaults(session_id)
                title = None
                if isinstance(entry, dict):
                    title = entry.get("name") or entry.get("title") or entry.get("sessionName")
                title = str(title).strip() if title is not None else None
                updated_at = None
                session_cwd = None
                if isinstance(entry, dict):
                    updated_at = _to_iso8601_utc(
                        entry.get("modified") or entry.get("updatedAt") or entry.get("updated_at")
                    )
                    session_cwd = entry.get("cwd")
                if isinstance(session_cwd, str) and session_cwd.strip():
                    self._cwd_by_session[session_id] = session_cwd
                effective_cwd = self._cwd_by_session.get(session_id, cwd or str(workspace_dir))
                if cwd and effective_cwd != cwd:
                    continue
                sessions.append(
                    SessionInfo(
                        session_id=session_id,
                        cwd=effective_cwd,
                        title=title or None,
                        updated_at=updated_at,
                    )
                )
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
            elif not self._profile.is_zed:
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
                cwd=self._cwd_by_session.get(session_id, ""),
                mode=mode,
                planner_model=planner_model_id,
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
        await run_agent(BrokkAcpAgent(), use_unstable_protocol=True)
    finally:
        await executor.stop()
