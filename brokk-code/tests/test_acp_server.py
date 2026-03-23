from pathlib import Path
from typing import Any

import pytest

from brokk_code.acp_server import (
    BASE_MODEL_IDS,
    DEFAULT_MODEL_SELECTION,
    DEFAULT_REASONING_LEVEL,
    REASONING_LEVEL_IDS,
    AcpStdioBridge,
    BrokkAcpBridge,
    _available_model_names,
    _build_available_models,
    _extract_session_id_for_cancel,
    _fetch_normalized_catalog_with_retries,
    _known_session_ids,
    _model_variants_for_model,
    _normalize_model_catalog,
    _parse_model_selection,
    _reasoning_options_for_model,
    _sanitize_reasoning_level_for_model,
    acp_slash_commands,
    conversation_payload_to_session_updates,
    extract_prompt_text,
    extract_resource_file_paths,
    get_slash_command,
    map_executor_event_to_session_update,
    normalize_mode,
    resolve_model_selection,
)
from brokk_code.workspace import resolve_workspace_dir


def _text_block(value: str) -> dict[str, str]:
    return {"sessionUpdate": "agent_message_chunk", "text": value}


def _thought_block(value: str) -> dict[str, str]:
    return {"sessionUpdate": "agent_thought_chunk", "text": value}


def test_normalize_mode_defaults_and_known_values() -> None:
    assert normalize_mode(None) == "LUTZ"
    assert normalize_mode("") == "LUTZ"
    assert normalize_mode("ask") == "ASK"
    assert normalize_mode("code") == "CODE"
    assert normalize_mode("lutz") == "LUTZ"
    assert normalize_mode("invalid") == "LUTZ"


def test_model_and_reasoning_constants() -> None:
    assert DEFAULT_MODEL_SELECTION in BASE_MODEL_IDS
    assert DEFAULT_REASONING_LEVEL in REASONING_LEVEL_IDS


def test_resolve_model_selection_variant_and_plain() -> None:
    assert resolve_model_selection(DEFAULT_MODEL_SELECTION) == (DEFAULT_MODEL_SELECTION, None)
    assert resolve_model_selection("gpt-5.3-codex#r=low") == ("gpt-5.3-codex", "low")
    assert resolve_model_selection("gemini-3-flash-preview") == ("gemini-3-flash-preview", None)


def test_normalize_model_catalog_and_reasoning_options() -> None:
    catalog = _normalize_model_catalog(
        {
            "models": [
                {
                    "name": "gpt-5.3-codex",
                    "location": "openai/gpt-5.3-codex",
                    "supportsReasoningEffort": True,
                    "supportsReasoningDisable": True,
                },
                {
                    "name": "gemini-3-flash-preview",
                    "location": "google/gemini-3-flash-preview",
                    "supportsReasoningEffort": False,
                    "supportsReasoningDisable": False,
                },
            ]
        }
    )
    assert [m["name"] for m in catalog] == ["gpt-5.3-codex", "gemini-3-flash-preview"]
    assert _reasoning_options_for_model("gpt-5.3-codex", catalog) == [
        "default",
        "low",
        "medium",
        "high",
        "disable",
    ]
    assert _reasoning_options_for_model("gemini-3-flash-preview", catalog) == [
        "default",
        "low",
        "medium",
        "high",
        "disable",
    ]
    assert (
        _sanitize_reasoning_level_for_model("gemini-3-flash-preview", "high", catalog) == "default"
    )


def test_build_available_models_includes_model_variants_with_conditional_disable() -> None:
    catalog = _normalize_model_catalog(
        {
            "models": [
                {
                    "name": "gpt-5.3-codex",
                    "supportsReasoningEffort": True,
                    "supportsReasoningDisable": True,
                },
                {
                    "name": "gemini-3-flash-preview",
                    "supportsReasoningEffort": False,
                    "supportsReasoningDisable": False,
                },
            ]
        }
    )
    assert _model_variants_for_model("gpt-5.3-codex", catalog) == [
        "low",
        "medium",
        "high",
        "disable",
    ]
    assert _model_variants_for_model("gemini-3-flash-preview", catalog) == []
    options = _build_available_models(catalog)
    assert [value for value, _ in options] == [
        "gpt-5.3-codex",
        "gpt-5.3-codex/low",
        "gpt-5.3-codex/medium",
        "gpt-5.3-codex/high",
        "gpt-5.3-codex/disable",
        "gemini-3-flash-preview",
    ]
    assert [name for _, name in options] == [
        "gpt-5.3-codex",
        "gpt-5.3-codex (low)",
        "gpt-5.3-codex (medium)",
        "gpt-5.3-codex (high)",
        "gpt-5.3-codex (disable)",
        "gemini-3-flash-preview",
    ]
    assert all("#r=" not in value for value, _ in options)


def test_parse_model_selection_routes_model_and_variant() -> None:
    catalog = _normalize_model_catalog(
        {
            "models": [
                {
                    "name": "gpt-5.3-codex",
                    "supportsReasoningEffort": True,
                    "supportsReasoningDisable": True,
                },
            ]
        }
    )
    assert _parse_model_selection("gpt-5.3-codex", catalog) == ("gpt-5.3-codex", None)
    assert _parse_model_selection("gpt-5.3-codex/high", catalog) == ("gpt-5.3-codex", "high")
    assert _parse_model_selection("gpt-5.3-codex/default", catalog) == (
        "gpt-5.3-codex/default",
        None,
    )
    assert _parse_model_selection("model/gpt-5.3-codex", catalog) == ("gpt-5.3-codex", None)


def test_available_model_names_filters_invalid_entries_and_preserves_order() -> None:
    catalog = [
        {"name": "alpha"},
        {"name": None},
        {"name": "   "},
        {"name": "beta"},
        {"name": "alpha"},
        {"location": "missing-name"},
    ]

    assert _available_model_names(catalog) == ["alpha", "beta", "alpha"]


def test_parse_model_selection_ignores_none_model_name() -> None:
    catalog = [{"name": None}, {"name": "gpt-5.3-codex"}]

    assert _parse_model_selection("None", catalog) == ("None", None)
    assert _parse_model_selection("gpt-5.3-codex", catalog) == ("gpt-5.3-codex", None)


async def test_fetch_normalized_catalog_with_retries_recovers_after_transient_failures() -> None:
    calls = {"count": 0}

    async def fetch_payload() -> dict[str, object]:
        calls["count"] += 1
        if calls["count"] < 3:
            raise RuntimeError("executor not ready")
        return {"models": [{"name": "gpt-5.3-codex"}]}

    catalog = await _fetch_normalized_catalog_with_retries(
        fetch_payload,
        attempts=4,
        initial_backoff_seconds=0,
    )

    assert catalog == [
        {
            "name": "gpt-5.3-codex",
            "location": "gpt-5.3-codex",
            "supportsReasoningEffort": False,
            "supportsReasoningDisable": False,
        }
    ]
    assert calls["count"] == 3


async def test_fetch_normalized_catalog_with_retries_returns_none_when_catalog_stays_empty() -> (
    None
):
    calls = {"count": 0}

    async def fetch_payload() -> dict[str, object]:
        calls["count"] += 1
        return {"models": []}

    catalog = await _fetch_normalized_catalog_with_retries(
        fetch_payload,
        attempts=3,
        initial_backoff_seconds=0,
    )

    assert catalog is None
    assert calls["count"] == 3


def test_extract_prompt_text_from_blocks() -> None:
    prompt = [
        {"type": "text", "text": "Hello"},
        {"type": "image", "url": "http://example.com"},
        {"type": "text", "text": "World"},
    ]
    assert extract_prompt_text(prompt) == "Hello\nWorld"


def test_extract_prompt_text_from_string() -> None:
    assert extract_prompt_text("  direct prompt  ") == "direct prompt"


def test_get_slash_command_logic() -> None:
    # Valid commands
    assert get_slash_command("/context") == "/context"
    assert get_slash_command("  /context  ") == "/context"
    assert get_slash_command("/context list files") == "/context"

    # Invalid or non-commands
    assert get_slash_command("context") is None
    assert get_slash_command("/unknown") is None
    assert get_slash_command("Please /context") is None
    assert get_slash_command("") is None


def test_acp_slash_commands_catalog_is_protocol_compatible() -> None:
    commands = acp_slash_commands()
    assert {"name": "context", "description": "Show current context snapshot"} in commands
    assert all(not cmd["name"].startswith("/") for cmd in commands)
    for cmd in commands:
        assert get_slash_command(f"/{cmd['name']}") == f"/{cmd['name']}"


def test_extract_resource_file_paths_supports_zed_resource_shape_and_relative_links() -> None:
    prompt = [
        {
            "type": "resource",
            "resource": {"resource": {"uri": "file:///workspace/src/main.py"}},
        },
        {
            "type": "embedded_resource",
            "resource": {"uri": "file:///workspace/README.md"},
        },
        {"type": "resource_link", "uri": "src/utils.py"},
        {"type": "resource_link", "uri": "https://example.com/docs"},
        {"type": "resource", "resource": {"uri": "zed:///agent/diagnostics"}},
    ]

    assert extract_resource_file_paths(prompt, "/workspace") == [
        "src/main.py",
        "README.md",
        "src/utils.py",
    ]


def test_extract_resource_file_paths_ignores_relative_links_without_cwd() -> None:
    prompt = [{"type": "resource_link", "uri": "src/utils.py"}]
    assert extract_resource_file_paths(prompt, "") == []


def test_map_executor_token_event() -> None:
    event = {"type": "LLM_TOKEN", "data": {"token": "abc"}}
    assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "abc",
    }


def test_map_executor_reasoning_token_event_uses_thought_block() -> None:
    # Boolean True
    event = {"type": "LLM_TOKEN", "data": {"token": "thinking", "isReasoning": True}}
    assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
        "sessionUpdate": "agent_thought_chunk",
        "text": "thinking",
    }

    # Boolean False
    event = {"type": "LLM_TOKEN", "data": {"token": "not thinking", "isReasoning": False}}
    assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "not thinking",
    }

    # String Truthy
    for val in ["true", "1", "yes", " TRUE "]:
        event = {"type": "LLM_TOKEN", "data": {"token": "thinking", "isReasoning": val}}
        assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
            "sessionUpdate": "agent_thought_chunk",
            "text": "thinking",
        }, f"Failed for truthy value: {val}"

    # String Falsy (which would be truthy via naive bool())
    for val in ["false", "0", "no", "anything else"]:
        event = {"type": "LLM_TOKEN", "data": {"token": "not thinking", "isReasoning": val}}
        assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
            "sessionUpdate": "agent_message_chunk",
            "text": "not thinking",
        }, f"Failed for falsy value: {val}"

    # Non-string payloads keep sensible bool behavior.
    for val in [1, [1], {"a": 1}]:
        event = {"type": "LLM_TOKEN", "data": {"token": "thinking", "isReasoning": val}}
        assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
            "sessionUpdate": "agent_thought_chunk",
            "text": "thinking",
        }, f"Failed for truthy non-string value: {val}"

    for val in [0, None, [], {}]:
        event = {"type": "LLM_TOKEN", "data": {"token": "not thinking", "isReasoning": val}}
        assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
            "sessionUpdate": "agent_message_chunk",
            "text": "not thinking",
        }, f"Failed for falsy non-string value: {val}"


def test_map_executor_error_event() -> None:
    event = {"type": "ERROR", "data": {"message": "boom"}}
    update = map_executor_event_to_session_update(event, _text_block)
    assert update is not None
    assert "Error:" in update["text"]
    assert "boom" in update["text"]


def test_map_executor_info_notification_event_is_clean() -> None:
    event = {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "planning"}}
    update = map_executor_event_to_session_update(event, _text_block)
    assert update is not None
    assert "planning" in update["text"]
    # Verify no passthrough brackets
    assert "[INFO]" not in update["text"]


def test_map_executor_state_and_cost_events_are_suppressed() -> None:
    state_event = {"type": "STATE_HINT", "data": {"message": "indexing"}}
    assert map_executor_event_to_session_update(state_event, _text_block) is None

    cost_event = {"type": "NOTIFICATION", "data": {"level": "COST", "message": "$0.01"}}
    assert map_executor_event_to_session_update(cost_event, _text_block) is None


def test_map_executor_status_token_mojibake_is_minimally_normalized() -> None:
    token = "reviewingâ€¦"
    event = {"type": "LLM_TOKEN", "data": {"token": token}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "reviewing...",
    }


def test_map_executor_tool_events_are_suppressed() -> None:
    call_event = {"type": "TOOL_CALL", "data": {"name": "read"}}
    assert map_executor_event_to_session_update(call_event, _text_block) is None

    out_event = {"type": "TOOL_OUTPUT", "data": {"result": "ok"}}
    assert map_executor_event_to_session_update(out_event, _text_block) is None


def test_conversation_payload_to_session_updates_replays_user_assistant_and_reasoning() -> None:
    conversation_data = {
        "entries": [
            {
                "messages": [
                    {"role": "user", "text": "Hello"},
                    {"role": "assistant", "reasoning": "Thinking...", "text": "Hi there"},
                    {"role": "tool", "text": "Tool output"},
                    {"role": "assistant", "text": "   "},
                ]
            },
            {"summary": "Condensed summary"},
        ]
    }

    def _user_update(text: str) -> dict[str, str]:
        return {"sessionUpdate": "user_message_chunk", "text": text}

    def _agent_update(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    def _thought_update(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_thought_chunk", "text": text}

    updates = conversation_payload_to_session_updates(
        conversation_data,
        update_user_message_text=_user_update,
        update_agent_message_text=_agent_update,
        update_agent_thought_text=_thought_update,
    )

    assert updates == [
        {"sessionUpdate": "user_message_chunk", "text": "Hello"},
        {"sessionUpdate": "agent_thought_chunk", "text": "Thinking..."},
        {"sessionUpdate": "agent_message_chunk", "text": "Hi there"},
        {"sessionUpdate": "agent_message_chunk", "text": "Tool output"},
        {"sessionUpdate": "agent_message_chunk", "text": "Condensed summary"},
    ]


def test_conversation_payload_to_session_updates_handles_missing_entries() -> None:
    updates = conversation_payload_to_session_updates(
        {"entries": "bad"},
        update_user_message_text=_text_block,
        update_agent_message_text=_thought_block,
    )
    assert updates == []


def test_extract_session_id_for_cancel() -> None:
    assert _extract_session_id_for_cancel((), {"session_id": "abc"}) == "abc"
    assert _extract_session_id_for_cancel(({"sessionId": "def"},), {}) == "def"
    assert _extract_session_id_for_cancel((), {"params": {"sessionId": "ghi"}}) == "ghi"
    assert _extract_session_id_for_cancel((), {}) is None


def test_known_session_ids_collects_ids_without_leaking_names() -> None:
    entries = [
        {"id": "session-a"},
        {"sessionId": "session-b"},
        {"session_id": "session-c"},
        {"id": ""},
        {},
    ]
    assert _known_session_ids(entries) == {"session-a", "session-b", "session-c"}


def test_known_session_ids_handles_bad_payload() -> None:
    assert _known_session_ids(None) == set()
    assert _known_session_ids("bad") == set()


class MockAcpStdioExecutor:
    """Mock AcpStdioExecutor for testing JSON-RPC method calls."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.next_result: dict[str, Any] = {}

    async def _send_request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        self.calls.append((method, params))
        return self.next_result


@pytest.mark.asyncio
async def test_acp_stdio_executor_get_models() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    executor._send_request = MockAcpStdioExecutor()._send_request.__get__(executor)  # type: ignore[method-assign]

    mock = MockAcpStdioExecutor()
    mock.next_result = {"models": {"gpt-4": "openai"}}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.get_models()

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("models/list", {})
    assert result == {"models": {"gpt-4": "openai"}}


@pytest.mark.asyncio
async def test_acp_stdio_executor_get_context() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"fragments": [{"id": "frag-1", "type": "PROJECT_PATH"}]}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.get_context()

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("context/get", {})
    assert result == {"fragments": [{"id": "frag-1", "type": "PROJECT_PATH"}]}


@pytest.mark.asyncio
async def test_acp_stdio_executor_add_context_files() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"addedFragmentIds": ["frag-1", "frag-2"]}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.add_context_files(["src/main.py", "README.md"])

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("context/add-files", {"relativePaths": ["src/main.py", "README.md"]})
    assert result == {"addedFragmentIds": ["frag-1", "frag-2"]}


@pytest.mark.asyncio
async def test_acp_stdio_executor_drop_context_fragments() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"droppedFragmentIds": ["frag-1"]}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.drop_context_fragments(["frag-1", "frag-2"])

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("context/drop", {"fragmentIds": ["frag-1", "frag-2"]})
    assert result == {"droppedFragmentIds": ["frag-1"]}


@pytest.mark.asyncio
async def test_acp_stdio_executor_list_sessions() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"sessions": [{"id": "uuid-1", "name": "Session 1"}]}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.list_sessions()

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("sessions/list", {})
    assert result == {"sessions": [{"id": "uuid-1", "name": "Session 1"}]}


async def test_ensure_ready_bootstraps_session_before_wait_ready() -> None:
    calls: list[str] = []

    class StubExecutor:
        def __init__(self) -> None:
            self.workspace_dir = Path("/initial")

        async def start(self) -> None:
            calls.append("start")

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
    await bridge.ensure_ready("/tmp/project")

    assert calls == ["start"]
    assert bridge.executor.workspace_dir == resolve_workspace_dir(Path("/tmp/project"))


async def test_start_and_create_session_avoids_bootstrap_on_first_call() -> None:
    calls: list[str] = []

    class StubExecutor:
        def __init__(self) -> None:
            self.workspace_dir = Path("/initial")

        async def start(self) -> None:
            calls.append("start")

        async def create_session(self, name: str = "ignored") -> str:
            calls.append(f"create_session:{name}")
            return "session-real"

        async def wait_ready(self) -> bool:
            calls.append("wait_ready")
            return True

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
    session_id = await bridge.start_and_create_session(name="Requested Session")

    assert session_id == "session-real"
    assert calls == ["start", "create_session:Requested Session", "wait_ready"]


async def test_ensure_ready_noops_when_workspace_is_unchanged(tmp_path: Path) -> None:
    calls: list[str] = []

    class StubExecutor:
        def __init__(self, workspace_dir: Path) -> None:
            self.workspace_dir = workspace_dir

        async def start(self) -> None:
            calls.append("start")

    bridge = BrokkAcpBridge(StubExecutor(tmp_path))  # type: ignore[arg-type]

    await bridge.ensure_ready(str(tmp_path))
    await bridge.ensure_ready(str(tmp_path))

    assert calls == ["start"]


async def test_ensure_ready_restarts_executor_when_workspace_changes(tmp_path: Path) -> None:
    calls: list[str] = []
    first = tmp_path / "first"
    second = tmp_path / "second"
    first.mkdir()
    second.mkdir()

    class StubExecutor:
        def __init__(self, workspace_dir: Path) -> None:
            self.workspace_dir = workspace_dir
            self.session_id = "session-1"
            self.base_url = "http://127.0.0.1:9999"

        async def start(self) -> None:
            calls.append(f"start:{self.workspace_dir}")

        async def stop(self) -> None:
            calls.append("stop")

        async def cancel_job(self, job_id: str) -> None:
            calls.append(f"cancel_job:{job_id}")

    bridge = BrokkAcpBridge(StubExecutor(first))  # type: ignore[arg-type]
    bridge._active_job_by_session["acp-1"] = "job-1"

    await bridge.ensure_ready(str(first))
    await bridge.ensure_ready(str(second))

    assert calls == [
        f"start:{first.resolve()}",
        "cancel_job:job-1",
        "stop",
        f"start:{second.resolve()}",
    ]
    assert bridge.executor.workspace_dir == second.resolve()
    assert bridge.executor.session_id is None
    assert bridge.executor.base_url is None


async def test_prompt_standard_flow_calls_submit_job_and_streams_tokens(tmp_path: Path) -> None:
    updates: list[tuple[str, dict[str, str]]] = []
    job_submitted = False

    class StubExecutor:
        def __init__(self, workspace_dir: Path):
            self.workspace_dir = workspace_dir

        async def start(self) -> None:
            pass

        async def create_session(self, name: str = "ignored") -> str:
            return "session-1"

        async def wait_ready(self) -> bool:
            return True

        async def switch_session(self, sid: str) -> bool:
            return True

        async def submit_job(
            self,
            task_input: str,
            planner_model: str,
            code_model: str | None = None,
            reasoning_level: str | None = None,
            reasoning_level_code: str | None = None,
            mode: str = "LUTZ",
            session_id: str | None = None,
            **kwargs: Any,
        ) -> str:
            nonlocal job_submitted
            job_submitted = True
            assert task_input == "hello"
            assert session_id == "acp-session-1"
            return "job-1"

        async def stream_events(self, job_id: str):
            yield {"type": "LLM_TOKEN", "data": {"token": "abc"}}

    async def send_update(session_id: str, update: dict[str, str]) -> None:
        updates.append((session_id, update))

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    bridge = BrokkAcpBridge(StubExecutor(tmp_path))  # type: ignore[arg-type]
    await bridge.prompt(
        prompt=[{"type": "text", "text": "hello"}],
        session_id="acp-session-1",
        mode="LUTZ",
        planner_model="gpt-5.3-codex",
        code_model="gemini-3-flash-preview",
        reasoning_level="low",
        reasoning_level_code="disable",
        send_update=send_update,
        update_agent_message_text=update_agent_message_text,
    )

    assert job_submitted
    # Only one update for the token "abc"
    assert len(updates) == 1
    assert updates[0][1]["text"] == "abc"


async def test_prompt_context_command_renders_snapshot_without_job(tmp_path: Path) -> None:
    updates: list[tuple[str, dict[str, Any]]] = []
    job_submitted = False

    class StubExecutor:
        async def start(self) -> None:
            pass

        async def create_session(self, name: str) -> str:
            return "session-1"

        async def wait_ready(self) -> bool:
            return True

        async def switch_session(self, sid: str) -> bool:
            return True

        async def get_context(self) -> dict[str, Any]:
            return {
                "fragments": [
                    {"shortDescription": "file.py", "tokens": 1500, "pinned": True},
                    {"shortDescription": "other.txt", "tokens": 500, "readonly": True},
                    {"shortDescription": "a.md", "tokens": 400},
                    {"shortDescription": "b.md", "tokens": 300},
                    {"shortDescription": "c.md", "tokens": 200},
                    {"shortDescription": "d.md", "tokens": 100},
                ],
                "usedTokens": 1234,
                "maxTokens": 200000,
                "branch": "main",
                "totalCost": 0.0567,
            }

        async def submit_job(self, **kwargs: Any) -> str:
            nonlocal job_submitted
            job_submitted = True
            return "job-1"

    async def send_update(session_id: str, update: dict[str, Any]) -> None:
        updates.append((session_id, update))

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
    # In ACP mode, do NOT emit context snapshots after prompt completion via automatic means.
    # The /context command explicitly generates one.
    await bridge.prompt(
        prompt="/context",
        session_id="acp-1",
        mode="LUTZ",
        planner_model="gpt-5.3-codex",
        code_model=None,
        reasoning_level=None,
        reasoning_level_code=None,
        send_update=send_update,
        update_agent_message_text=update_agent_message_text,
    )

    assert not job_submitted
    assert len(updates) == 1
    assert updates[0][1]["sessionUpdate"] == "agent_message_chunk"
    table = updates[0][1]["text"]
    assert "| Fragment | Tokens | % Context |" in table
    assert "|---|---:|---:|" in table
    assert "| file.py | 1,500 | 0.75% |" in table
    assert "| other.txt | 500 | 0.25% |" in table
    assert "| a.md | 400 | 0.20% |" in table
    assert "| b.md | 300 | 0.15% |" in table
    assert "| (other) | 300 | 0.15% |" in table
    assert "| c.md |" not in table
    assert "| d.md |" not in table
    assert table.index("| file.py |") < table.index("| other.txt |")
    assert "**Total Tokens:** 1,234 / 200,000" in table
    assert "![Token usage](data:image/png;base64," in table


# ── AcpStdioBridge tests ──────────────────────────────────────────────


class StubStdioExecutor:
    """Minimal stub for AcpStdioExecutor used in AcpStdioBridge tests."""

    def __init__(self) -> None:
        self.workspace_dir = Path("/stub/workspace")
        self.session_id: str | None = None
        self.calls: list[str] = []

    async def start(self) -> None:
        self.calls.append("start")

    async def initialize(self) -> dict[str, Any]:
        self.calls.append("initialize")
        return {}

    async def new_session(self, working_directory: str) -> str:
        self.calls.append(f"new_session:{working_directory}")
        return "stub-session-id"

    async def list_sessions(self) -> dict[str, Any]:
        self.calls.append("list_sessions")
        return {"sessions": []}

    async def switch_session(self, session_id: str) -> dict[str, Any]:
        self.calls.append(f"switch_session:{session_id}")
        return {"status": "ok", "sessionId": session_id}

    async def get_conversation(self) -> dict[str, Any]:
        self.calls.append("get_conversation")
        return {"entries": []}


@pytest.mark.asyncio
async def test_stdio_bridge_ensure_ready_starts_once() -> None:
    executor = StubStdioExecutor()
    bridge = AcpStdioBridge(executor)  # type: ignore[arg-type]

    await bridge.ensure_ready("/project/a")
    await bridge.ensure_ready("/project/b")

    assert bridge._started is True
    assert executor.calls == [
        "start",
        "initialize",
        "new_session:/project/a",
    ]


@pytest.mark.asyncio
async def test_stdio_bridge_ensure_ready_concurrent_calls_start_once() -> None:
    import asyncio

    executor = StubStdioExecutor()
    bridge = AcpStdioBridge(executor)  # type: ignore[arg-type]

    await asyncio.gather(
        bridge.ensure_ready("/project/a"),
        bridge.ensure_ready("/project/b"),
        bridge.ensure_ready("/project/c"),
    )

    assert bridge._started is True
    start_count = executor.calls.count("start")
    assert start_count == 1, f"Expected start called once, got {start_count}: {executor.calls}"


@pytest.mark.asyncio
async def test_stdio_bridge_not_started_initially() -> None:
    executor = StubStdioExecutor()
    bridge = AcpStdioBridge(executor)  # type: ignore[arg-type]

    assert bridge._started is False


@pytest.mark.asyncio
async def test_stdio_executor_switch_session_sends_correct_method() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"status": "ok", "sessionId": "uuid-1"}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.switch_session("uuid-1")

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("session/switch", {"sessionId": "uuid-1"})
    assert result == {"status": "ok", "sessionId": "uuid-1"}
    assert executor.session_id == "uuid-1"


@pytest.mark.asyncio
async def test_stdio_executor_get_conversation_sends_correct_method() -> None:
    from brokk_code.executor import AcpStdioExecutor

    executor = AcpStdioExecutor()
    mock = MockAcpStdioExecutor()
    mock.next_result = {"entries": [{"sequence": 1, "isCompressed": False}]}

    async def mock_send(method: str, params: dict[str, Any]) -> dict[str, Any]:
        mock.calls.append((method, params))
        return mock.next_result

    executor._send_request = mock_send  # type: ignore[method-assign]

    result = await executor.get_conversation()

    assert len(mock.calls) == 1
    assert mock.calls[0] == ("context/get-conversation", {})
    assert result == {"entries": [{"sequence": 1, "isCompressed": False}]}
