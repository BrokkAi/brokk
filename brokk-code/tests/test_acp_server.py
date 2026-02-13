from brokk_code.acp_server import (
    BrokkAcpBridge,
    _extract_session_id_for_cancel,
    extract_prompt_text,
    map_executor_event_to_session_update,
    normalize_mode,
)


def _text_block(value: str) -> dict[str, str]:
    return {"sessionUpdate": "agent_message_chunk", "text": value}


def test_normalize_mode_defaults_and_known_values() -> None:
    assert normalize_mode(None) == "LUTZ"
    assert normalize_mode("") == "LUTZ"
    assert normalize_mode("ask") == "ASK"
    assert normalize_mode("search") == "SEARCH"
    assert normalize_mode("lutz") == "LUTZ"
    assert normalize_mode("invalid") == "LUTZ"


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
