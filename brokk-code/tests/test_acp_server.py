from pathlib import Path
from typing import Any

from brokk_code.acp_server import (
    BASE_MODEL_IDS,
    DEFAULT_MODEL_SELECTION,
    DEFAULT_REASONING_LEVEL,
    REASONING_LEVEL_IDS,
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


def _text_block(value: str) -> dict[str, str]:
    return {"sessionUpdate": "agent_message_chunk", "text": value}


def _thought_block(value: str) -> dict[str, str]:
    return {"sessionUpdate": "agent_thought_chunk", "text": value}


def _start_tool_call(**kwargs: Any) -> dict[str, Any]:
    return {"sessionUpdate": "tool_call", **kwargs}


def _update_tool_call(**kwargs: Any) -> dict[str, Any]:
    return {"sessionUpdate": "tool_call_update", **kwargs}


def _tool_content(block: Any) -> dict[str, Any]:
    return {"type": "content", "content": block}


def _text_block_helper(text: str) -> dict[str, str]:
    return {"type": "text", "text": text}


def _image_block_helper(data: str, mime_type: str, *, uri: str | None = None) -> dict[str, str]:
    payload: dict[str, str] = {"type": "image", "data": data, "mime_type": mime_type}
    if uri:
        payload["uri"] = uri
    return payload


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
    assert resolve_model_selection("gpt-5.2#r=low") == ("gpt-5.2", "low")
    assert resolve_model_selection("gemini-3-flash-preview") == ("gemini-3-flash-preview", None)


def test_normalize_model_catalog_and_reasoning_options() -> None:
    catalog = _normalize_model_catalog(
        {
            "models": [
                {
                    "name": "gpt-5.2",
                    "location": "openai/gpt-5.2",
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
    assert [m["name"] for m in catalog] == ["gpt-5.2", "gemini-3-flash-preview"]
    assert _reasoning_options_for_model("gpt-5.2", catalog) == [
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
                    "name": "gpt-5.2",
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
    assert _model_variants_for_model("gpt-5.2", catalog) == ["low", "medium", "high", "disable"]
    assert _model_variants_for_model("gemini-3-flash-preview", catalog) == []
    options = _build_available_models(catalog)
    assert [value for value, _ in options] == [
        "gpt-5.2",
        "gpt-5.2/low",
        "gpt-5.2/medium",
        "gpt-5.2/high",
        "gpt-5.2/disable",
        "gemini-3-flash-preview",
    ]
    assert [name for _, name in options] == [
        "gpt-5.2",
        "gpt-5.2 (low)",
        "gpt-5.2 (medium)",
        "gpt-5.2 (high)",
        "gpt-5.2 (disable)",
        "gemini-3-flash-preview",
    ]
    assert all("#r=" not in value for value, _ in options)


def test_parse_model_selection_routes_model_and_variant() -> None:
    catalog = _normalize_model_catalog(
        {
            "models": [
                {
                    "name": "gpt-5.2",
                    "supportsReasoningEffort": True,
                    "supportsReasoningDisable": True,
                },
            ]
        }
    )
    assert _parse_model_selection("gpt-5.2", catalog) == ("gpt-5.2", None)
    assert _parse_model_selection("gpt-5.2/high", catalog) == ("gpt-5.2", "high")
    assert _parse_model_selection("gpt-5.2/default", catalog) == ("gpt-5.2/default", None)
    assert _parse_model_selection("model/gpt-5.2", catalog) == ("gpt-5.2", None)


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
    catalog = [{"name": None}, {"name": "gpt-5.2"}]

    assert _parse_model_selection("None", catalog) == ("None", None)
    assert _parse_model_selection("gpt-5.2", catalog) == ("gpt-5.2", None)


async def test_fetch_normalized_catalog_with_retries_recovers_after_transient_failures() -> None:
    calls = {"count": 0}

    async def fetch_payload() -> dict[str, object]:
        calls["count"] += 1
        if calls["count"] < 3:
            raise RuntimeError("executor not ready")
        return {"models": [{"name": "gpt-5.2"}]}

    catalog = await _fetch_normalized_catalog_with_retries(
        fetch_payload,
        attempts=4,
        initial_backoff_seconds=0,
    )

    assert catalog == [
        {
            "name": "gpt-5.2",
            "location": "gpt-5.2",
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
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "abc",
    }


def test_map_executor_reasoning_token_event_is_passthrough() -> None:
    # Under passthrough, reasoning flag is ignored by the mapper; it just emits the token.
    event = {"type": "LLM_TOKEN", "data": {"token": "thinking", "isReasoning": True}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "thinking",
    }


def test_map_executor_error_event() -> None:
    event = {"type": "ERROR", "data": {"message": "boom"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n[ERROR] boom\n",
    }


def test_map_executor_info_notification_event_is_passthrough() -> None:
    event = {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "planning"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n\n[INFO] planning\n",
    }


def test_map_executor_state_hint_surfaces_as_passthrough() -> None:
    event = {"type": "STATE_HINT", "data": {"message": "indexing workspace"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n[STATE] indexing workspace\n",
    }


def test_map_executor_cost_notification_event_is_passthrough() -> None:
    event = {"type": "NOTIFICATION", "data": {"level": "COST", "message": "$0.0012 for gpt"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n\n[COST] $0.0012 for gpt\n",
    }


def test_map_executor_status_token_mojibake_is_minimally_normalized() -> None:
    token = "\n**Brokk** performing initial workspace reviewâ€¦"
    event = {"type": "LLM_TOKEN", "data": {"token": token}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n**Brokk** performing initial workspace review...",
    }


def test_map_executor_tool_call_is_passthrough() -> None:
    event = {
        "type": "TOOL_CALL",
        "data": {"name": "read_file", "arguments": '{"path": "foo.py"}', "id": "call-1"},
    }
    update = map_executor_event_to_session_update(event, _text_block)
    assert update == {
        "sessionUpdate": "agent_message_chunk",
        "text": '\n[TOOL CALL] read_file({"path": "foo.py"})\n',
    }


def test_map_executor_tool_output_is_passthrough() -> None:
    event = {
        "type": "TOOL_OUTPUT",
        "data": {"status": "SUCCESS", "id": "call-1", "result": "done"},
    }
    update = map_executor_event_to_session_update(event, _text_block)
    assert update == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n[TOOL OUTPUT] SUCCESS: done\n",
    }


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


async def test_ensure_ready_bootstraps_session_before_wait_ready() -> None:
    calls: list[str] = []

    class StubExecutor:
        async def start(self) -> None:
            calls.append("start")

        async def create_session(self, name: str = "ignored") -> str:
            calls.append(f"create_session:{name}")
            return "session-1"

        async def wait_ready(self) -> bool:
            calls.append("wait_ready")
            return True

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
    await bridge.ensure_ready()

    assert calls == ["start", "create_session:ACP Bootstrap Session", "wait_ready"]


async def test_start_and_create_session_avoids_bootstrap_on_first_call() -> None:
    calls: list[str] = []

    class StubExecutor:
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

    def update_agent_message(content: Any) -> dict[str, Any]:
        return {"sessionUpdate": "agent_message_chunk", "content": content}

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    bridge = BrokkAcpBridge(StubExecutor(tmp_path))  # type: ignore[arg-type]
    await bridge.prompt(
        prompt=[{"type": "text", "text": "hello"}],
        session_id="acp-session-1",
        mode="LUTZ",
        planner_model="gpt-5.2",
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

    def update_agent_message(content: Any) -> dict[str, Any]:
        return {"sessionUpdate": "agent_message_chunk", "content": content}

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
    await bridge.prompt(
        prompt="/context",
        session_id="acp-1",
        mode="LUTZ",
        planner_model="gpt-5.2",
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
