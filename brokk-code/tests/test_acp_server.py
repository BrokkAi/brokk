from pathlib import Path

from brokk_code.acp_server import (
    BASE_MODEL_IDS,
    DEFAULT_MODEL_SELECTION,
    DEFAULT_REASONING_LEVEL,
    REASONING_LEVEL_IDS,
    BrokkAcpBridge,
    _build_available_models,
    _extract_session_id_for_cancel,
    _model_variants_for_model,
    _normalize_model_catalog,
    _parse_model_selection,
    _reasoning_options_for_model,
    _sanitize_reasoning_level_for_model,
    build_context_chip_blocks,
    extract_prompt_text,
    map_executor_event_to_session_update,
    normalize_mode,
    resolve_model_selection,
)


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


def test_extract_prompt_text_from_blocks() -> None:
    prompt = [
        {"type": "text", "text": "Hello"},
        {"type": "image", "url": "http://example.com"},
        {"type": "text", "text": "World"},
    ]
    assert extract_prompt_text(prompt) == "Hello\nWorld"


def test_extract_prompt_text_from_string() -> None:
    assert extract_prompt_text("  direct prompt  ") == "direct prompt"


def test_map_executor_token_event() -> None:
    event = {"type": "LLM_TOKEN", "data": {"token": "abc"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "abc",
    }


def test_map_executor_error_event() -> None:
    event = {"type": "ERROR", "data": {"message": "boom"}}
    assert map_executor_event_to_session_update(event, _text_block) == {
        "sessionUpdate": "agent_message_chunk",
        "text": "\n[ERROR] boom\n",
    }


def test_map_executor_unknown_event() -> None:
    event = {"type": "STATE_HINT", "data": {"name": "workspaceUpdated"}}
    assert map_executor_event_to_session_update(event, _text_block) is None


def test_map_executor_notification_event_to_thought_when_available() -> None:
    event = {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "planning"}}
    assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
        "sessionUpdate": "agent_thought_chunk",
        "text": "[INFO] planning",
    }


def test_map_executor_state_hint_to_thought_when_available() -> None:
    event = {"type": "STATE_HINT", "data": {"message": "indexing workspace"}}
    assert map_executor_event_to_session_update(event, _text_block, _thought_block) == {
        "sessionUpdate": "agent_thought_chunk",
        "text": "indexing workspace",
    }


def test_extract_session_id_for_cancel() -> None:
    assert _extract_session_id_for_cancel((), {"session_id": "abc"}) == "abc"
    assert _extract_session_id_for_cancel(({"sessionId": "def"},), {}) == "def"
    assert _extract_session_id_for_cancel((), {"params": {"sessionId": "ghi"}}) == "ghi"
    assert _extract_session_id_for_cancel((), {}) is None


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


def test_build_context_chip_blocks() -> None:
    context_data = {
        "usedTokens": 1500,
        "maxTokens": 100000,
        "fragments": [
            {"chipKind": "EDIT", "shortDescription": "foo.py:10-20", "tokens": 400},
            {
                "chipKind": "HISTORY",
                "shortDescription": "Prior chat",
                "tokens": 250,
                "pinned": True,
            },
        ],
    }
    blocks = build_context_chip_blocks(context_data, {})
    assert blocks == []


def test_build_context_chip_blocks_empty() -> None:
    blocks = build_context_chip_blocks({"usedTokens": 10, "fragments": []}, {})
    assert blocks == []


def test_build_context_chip_blocks_uses_fragment_resource_payload() -> None:
    context_data = {
        "fragments": [
            {"id": "frag-1", "chipKind": "EDIT", "shortDescription": "script.py"},
        ]
    }
    blocks = build_context_chip_blocks(
        context_data,
        {
            "frag-1": {
                "uri": "file:///tmp/script.py",
                "mimeType": "text/x-python",
                "text": "def hello():\n    print('Hello, world!')\n",
            }
        },
    )
    assert blocks[0]["uri"] == "file:///tmp/script.py"
    assert blocks[0]["mime_type"] == "text/x-python"
    assert "def hello():" in blocks[0]["text"]


def test_build_context_chip_blocks_orders_by_chip_kind() -> None:
    context_data = {
        "fragments": [
            {"id": "h", "chipKind": "HISTORY"},
            {"id": "o", "chipKind": "OTHER"},
            {"id": "e", "chipKind": "EDIT"},
            {"id": "s", "chipKind": "SUMMARY"},
        ]
    }
    blocks = build_context_chip_blocks(
        context_data,
        {
            "h": {"uri": "file:///tmp/h.txt", "mimeType": "text/plain", "text": "h"},
            "o": {"uri": "file:///tmp/o.txt", "mimeType": "text/plain", "text": "o"},
            "e": {"uri": "file:///tmp/e.txt", "mimeType": "text/plain", "text": "e"},
            "s": {"uri": "file:///tmp/s.txt", "mimeType": "text/plain", "text": "s"},
        },
    )
    assert [block["uri"] for block in blocks] == [
        "file:///tmp/e.txt",
        "file:///tmp/s.txt",
        "file:///tmp/h.txt",
        "file:///tmp/o.txt",
    ]


async def test_prompt_emits_context_snapshot_after_stream() -> None:
    updates: list[tuple[str, dict[str, str]]] = []

    class StubExecutor:
        workspace_dir = Path(".").resolve()

        async def start(self) -> None:
            pass

        async def create_session(self, name: str = "ignored") -> str:
            return "session-1"

        async def wait_ready(self) -> bool:
            return True

        async def submit_job(
            self,
            task_input: str,
            planner_model: str,
            code_model: str | None = None,
            reasoning_level: str | None = None,
            reasoning_level_code: str | None = None,
            mode: str = "LUTZ",
        ) -> str:
            return "job-1"

        async def stream_events(self, job_id: str):
            yield {"type": "LLM_TOKEN", "data": {"token": "abc"}}

        async def get_context(self) -> dict[str, object]:
            return {
                "usedTokens": 250,
                "maxTokens": 1000,
                "fragments": [
                    {
                        "id": "frag-1",
                        "chipKind": "EDIT",
                        "shortDescription": "main.py",
                        "tokens": 50,
                    }
                ],
            }

        async def get_context_fragment(self, fragment_id: str) -> dict[str, object]:
            assert fragment_id == "frag-1"
            return {
                "id": "frag-1",
                "uri": "file:///repo/main.py",
                "mimeType": "text/x-python",
                "text": "print('hi')\n",
            }

    async def send_update(session_id: str, update: dict[str, str]) -> None:
        updates.append((session_id, update))

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    def update_agent_thought_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_thought_chunk", "text": text}

    def build_context_snapshot_update(uri: str, mime_type: str, text: str) -> dict[str, str]:
        return {
            "sessionUpdate": "agent_message_chunk",
            "uri": uri,
            "mimeType": mime_type,
            "text": text,
            "kind": "embedded_resource",
        }

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
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
        update_agent_thought_text=update_agent_thought_text,
        build_context_snapshot_update=build_context_snapshot_update,
    )

    assert len(updates) == 6
    assert updates[0][1]["text"] == "abc"
    assert "Context Snapshot" in updates[1][1]["text"]
    assert "Editable Context" in updates[2][1]["text"]
    assert updates[3][1]["text"] == "- "
    assert updates[4][1]["kind"] == "embedded_resource"
    assert updates[4][1]["uri"] == "file:///repo/main.py"
    assert updates[4][1]["mimeType"] == "text/x-python"
    assert updates[4][1]["text"] == "print('hi')\n"
    assert updates[5][1]["text"] == " | 50\n"


async def test_prompt_emits_discarded_context_as_json_markdown_text() -> None:
    updates: list[tuple[str, dict[str, str]]] = []

    class StubExecutor:
        workspace_dir = Path(".").resolve()

        async def start(self) -> None:
            pass

        async def create_session(self, name: str = "ignored") -> str:
            return "session-1"

        async def wait_ready(self) -> bool:
            return True

        async def submit_job(
            self,
            task_input: str,
            planner_model: str,
            code_model: str | None = None,
            reasoning_level: str | None = None,
            reasoning_level_code: str | None = None,
            mode: str = "LUTZ",
        ) -> str:
            return "job-1"

        async def stream_events(self, job_id: str):
            yield {"type": "LLM_TOKEN", "data": {"token": "abc"}}

        async def get_context(self) -> dict[str, object]:
            return {
                "usedTokens": 250,
                "maxTokens": 1000,
                "fragments": [
                    {
                        "id": "frag-dc",
                        "chipKind": "OTHER",
                        "shortDescription": "Discarded Context",
                        "tokens": 10,
                    }
                ],
            }

        async def get_context_fragment(self, fragment_id: str) -> dict[str, object]:
            assert fragment_id == "frag-dc"
            return {
                "id": "frag-dc",
                "uri": "brokk://context/fragment/frag-dc",
                "mimeType": "text/plain",
                "text": "dropped items here",
            }

    async def send_update(session_id: str, update: dict[str, str]) -> None:
        updates.append((session_id, update))

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    def update_agent_thought_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_thought_chunk", "text": text}

    def build_context_snapshot_update(uri: str, mime_type: str, text: str) -> dict[str, str]:
        return {
            "sessionUpdate": "agent_message_chunk",
            "uri": uri,
            "mimeType": mime_type,
            "text": text,
            "kind": "embedded_resource",
        }

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
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
        update_agent_thought_text=update_agent_thought_text,
        build_context_snapshot_update=build_context_snapshot_update,
    )

    json_blocks = [u[1]["text"] for u in updates if "```json" in u[1].get("text", "")]
    assert len(json_blocks) == 1
    assert '"title": "Discarded Context"' in json_blocks[0]
    assert '"content": "dropped items here"' in json_blocks[0]


async def test_prompt_emits_summary_as_list_item_with_resource_and_tokens() -> None:
    updates: list[tuple[str, dict[str, str]]] = []

    class StubExecutor:
        workspace_dir = Path(".").resolve()

        async def start(self) -> None:
            pass

        async def create_session(self, name: str = "ignored") -> str:
            return "session-1"

        async def wait_ready(self) -> bool:
            return True

        async def submit_job(
            self,
            task_input: str,
            planner_model: str,
            code_model: str | None = None,
            reasoning_level: str | None = None,
            reasoning_level_code: str | None = None,
            mode: str = "LUTZ",
        ) -> str:
            return "job-1"

        async def stream_events(self, job_id: str):
            yield {"type": "LLM_TOKEN", "data": {"token": "abc"}}

        async def get_context(self) -> dict[str, object]:
            return {
                "usedTokens": 250,
                "maxTokens": 1000,
                "fragments": [
                    {
                        "id": "frag-s",
                        "chipKind": "SUMMARY",
                        "shortDescription": "Summary of worker.go",
                        "tokens": 12,
                    }
                ],
            }

        async def get_context_fragment(self, fragment_id: str) -> dict[str, object]:
            assert fragment_id == "frag-s"
            return {
                "id": "frag-s",
                "uri": "brokk://context/fragment/frag-s",
                "mimeType": "text/plain",
                "text": "summary text",
            }

    async def send_update(session_id: str, update: dict[str, str]) -> None:
        updates.append((session_id, update))

    def update_agent_message_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_message_chunk", "text": text}

    def update_agent_thought_text(text: str) -> dict[str, str]:
        return {"sessionUpdate": "agent_thought_chunk", "text": text}

    def build_context_snapshot_update(uri: str, mime_type: str, text: str) -> dict[str, str]:
        return {
            "sessionUpdate": "agent_message_chunk",
            "uri": uri,
            "mimeType": mime_type,
            "text": text,
            "kind": "embedded_resource",
        }

    bridge = BrokkAcpBridge(StubExecutor())  # type: ignore[arg-type]
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
        update_agent_thought_text=update_agent_thought_text,
        build_context_snapshot_update=build_context_snapshot_update,
    )

    assert len(updates) == 6
    assert updates[0][1]["text"] == "abc"
    assert "Context Snapshot" in updates[1][1]["text"]
    assert "Summaries" in updates[2][1]["text"]
    assert updates[3][1]["text"] == "- "
    assert updates[4][1]["kind"] == "embedded_resource"
    assert updates[4][1]["text"] == "summary text"
    assert updates[5][1]["text"] == " | 12\n"
