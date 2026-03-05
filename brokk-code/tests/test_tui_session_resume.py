import asyncio
from unittest.mock import AsyncMock, patch

import pytest

from brokk_code.app import BrokkApp
from brokk_code.session_persistence import get_session_zip_path, save_last_session_id
from tests.test_tui_resubmit import StubExecutor


class SessionStubExecutor(StubExecutor):
    def __init__(self):
        # We don't call super().__init__ with Path('.') because BrokkApp
        # will now correctly set the workspace_dir on the injected executor.
        self.workspace_dir = None
        self.import_calls = []
        self.create_calls = []
        self.conversation = {"entries": []}

    async def create_session(self, name: str = "TUI Session") -> str:
        self.create_calls.append(name)
        self.session_id = "new-session"
        return self.session_id

    async def import_session_zip(self, zip_bytes: bytes, session_id: str = None) -> str:
        self.import_calls.append((zip_bytes, session_id))
        self.session_id = session_id or "imported-session"
        return self.session_id

    async def get_conversation(self):
        return self.conversation


@pytest.mark.asyncio
async def test_startup_creates_new_session_by_default(tmp_path):
    workspace = tmp_path
    stub = SessionStubExecutor()
    # Default behavior is no resume
    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=False)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.create_calls) == 1
    assert len(stub.import_calls) == 0


@pytest.mark.asyncio
async def test_startup_imports_when_last_session_exists(tmp_path):
    workspace = tmp_path
    last_id = "last-123"
    save_last_session_id(workspace, last_id)

    zip_path = get_session_zip_path(workspace, last_id)
    zip_path.write_bytes(b"zip-data")

    stub = SessionStubExecutor()
    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=True)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == last_id
    assert stub.import_calls[0][0] == b"zip-data"
    assert len(stub.create_calls) == 0


@pytest.mark.asyncio
async def test_startup_prefers_cli_session(tmp_path):
    workspace = tmp_path
    cli_id = "cli-456"
    last_id = "last-123"
    save_last_session_id(workspace, last_id)

    # Zip exists for both
    get_session_zip_path(workspace, cli_id).write_bytes(b"cli-zip")
    get_session_zip_path(workspace, last_id).write_bytes(b"last-zip")

    stub = SessionStubExecutor()
    app = BrokkApp(executor=stub, workspace_dir=workspace, session_id=cli_id)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == cli_id
    assert stub.import_calls[0][0] == b"cli-zip"


@pytest.mark.asyncio
async def test_resume_command_launches_with_correct_id(tmp_path):
    """
    Verifies that the 'resume <id>' command correctly initializes BrokkApp
    with the specified session_id.
    """
    from brokk_code.__main__ import _build_parser

    workspace = tmp_path
    session_id = "manual-resume-id"

    # Simulate: brokk-code resume manual-resume-id --workspace <tmp_path>
    parser = _build_parser()
    args = parser.parse_args(["resume", session_id, "--workspace", str(workspace)])

    assert args.command == "resume"
    assert args.session_id == session_id

    stub = SessionStubExecutor()
    # We simulate what main() does
    app = BrokkApp(
        executor=stub,
        workspace_dir=workspace,
        session_id=args.session_id,
        resume_session=False,
    )

    assert app.requested_session_id == session_id
    assert app.resume_session is False

    with patch("brokk_code.app.ChatPanel"):
        # We need to set a valid zip for the stub to "resume" it
        get_session_zip_path(workspace, session_id).write_bytes(b"resumed-zip")
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == session_id


def test_main_prints_resume_hint_on_exit(tmp_path, capsys):
    """
    SPECIFICATION: Resume Hint Behavior
    The 'brokk resume <id>' hint MUST be printed to stdout if and only if:
    1. The application exits normally (main returns without unhandled exception).
    2. A last session ID is found in the workspace metadata.
    3. The session's ZIP file contains qualifying history tasks (has_tasks is true).
    """
    import json
    import zipfile

    from brokk_code.__main__ import main

    workspace = tmp_path
    session_id = "hint-session-789"
    save_last_session_id(workspace, session_id)

    # Create a zip with history tasks in contexts.jsonl so the hint prints
    zip_path = get_session_zip_path(workspace, session_id)
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr(
            "contexts.jsonl", json.dumps({"tasks": [{"sequence": 1, "taskType": "LUTZ"}]}) + "\n"
        )

    # Patch BrokkApp.run so it doesn't actually start the TUI
    # and sys.argv so main() uses our temp workspace.
    with (
        patch("brokk_code.app.BrokkApp.run", return_value=None),
        patch(
            "sys.argv",
            ["brokk", "--workspace", str(workspace)],
        ),
    ):
        main()

    captured = capsys.readouterr()
    expected_hint = f"brokk resume {session_id}"
    assert expected_hint in captured.out


def test_main_omits_resume_hint_when_no_tasks(tmp_path, capsys):
    """
    SPECIFICATION: Resume Hint Behavior (Empty Session)
    Verifies that main() does NOT print the resume hint if the session exists
    but has no qualifying history tasks in contexts.jsonl.
    """
    import json
    import zipfile

    from brokk_code.__main__ import main

    workspace = tmp_path
    session_id = "no-tasks-session"
    save_last_session_id(workspace, session_id)

    # Create a zip with contexts.jsonl but NO qualifying tasks (missing meta or sequence)
    zip_path = get_session_zip_path(workspace, session_id)
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("contexts.jsonl", json.dumps({"tasks": [{"sequence": 1}]}) + "\n")

    with (
        patch("brokk_code.app.BrokkApp.run", return_value=None),
        patch(
            "sys.argv",
            ["brokk", "--workspace", str(workspace)],
        ),
    ):
        main()

    captured = capsys.readouterr()
    assert "brokk resume" not in captured.out


def test_main_omits_resume_hint_on_exception(tmp_path, capsys):
    """
    SPECIFICATION: Resume Hint Behavior (Abnormal Exit)
    Verifies that the resume hint is NOT printed if app.run() raises an exception.
    The hint logic follows the finally block and relies on normal flow completion.
    """
    import json
    import zipfile
    from brokk_code.__main__ import main

    workspace = tmp_path
    session_id = "exception-session"
    save_last_session_id(workspace, session_id)

    # Create a qualifying zip
    zip_path = get_session_zip_path(workspace, session_id)
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr(
            "contexts.jsonl", json.dumps({"tasks": [{"sequence": 1, "taskType": "LUTZ"}]}) + "\n"
        )

    # Patch BrokkApp.run to raise an exception
    with (
        patch("brokk_code.app.BrokkApp.run", side_effect=RuntimeError("Crash")),
        patch("sys.argv", ["brokk", "--workspace", str(workspace)]),
    ):
        with pytest.raises(RuntimeError, match="Crash"):
            main()

    captured = capsys.readouterr()
    assert "brokk resume" not in captured.out


def test_replay_conversation_entries_renders_messages(tmp_path):
    workspace = tmp_path
    stub = SessionStubExecutor()
    app = BrokkApp(executor=stub, workspace_dir=workspace)

    class FakeChat:
        def __init__(self):
            self.calls = []
            self._message_history = []

        def _render_message_entry(self, kind, content, **kwargs):
            self.calls.append(("render", kind, content, kwargs))

        def add_user_message(self, text):
            self.calls.append(("user", text))

        def add_markdown(self, text):
            self.calls.append(("ai", text))

        def add_tool_result(self, text):
            self.calls.append(("tool", text))

        def add_system_message(self, text, level="INFO"):
            self.calls.append(("system", level, text))

        def append_message(self, author, text):
            self.calls.append(("legacy", author, text))

    fake_chat = FakeChat()
    app._maybe_chat = lambda: fake_chat  # type: ignore[method-assign]

    replayed = app._replay_conversation_entries(
        {
            "entries": [
                {
                    "sequence": 1,
                    "messages": [
                        {"role": "user", "text": "Please fix it"},
                        {"role": "ai", "text": "Done", "reasoning": "Thinking steps"},
                        {"role": "tool_execution_result", "text": "Command output"},
                    ],
                },
                {"sequence": 2, "summary": "Compressed summary"},
            ]
        }
    )

    assert replayed == 2
    assert ("user", "Please fix it") in fake_chat.calls
    assert ("ai", "Done") in fake_chat.calls
    assert ("tool", "Command output") in fake_chat.calls
    assert any(call[:3] == ("render", "REASONING", "Thinking steps") for call in fake_chat.calls)
    assert ("ai", "Compressed summary") in fake_chat.calls


class FakeStatusLine:
    def __init__(self):
        self.last_kwargs = {}

    def update_status(self, **kwargs):
        self.last_kwargs = kwargs


class FakeLog:
    def query(self, selector):
        return self

    async def remove(self):
        return None


class FakeChat:
    def __init__(self):
        self._message_history = []
        self.status_line = FakeStatusLine()
        self.log = FakeLog()
        self.last_session_cost = None

    def add_system_message(self, msg, level="INFO"):
        pass

    def set_job_running(self, running: bool):
        pass

    def set_token_usage(self, used, max_tokens, fragments, session_cost=None):
        self.last_session_cost = session_cost

    def query_one(self, selector, *args, **kwargs):
        if selector in ("#chat-log", "log"):
            return self.log
        if selector in ("#status-line", "StatusLine"):
            return self.status_line
        raise Exception(f"Unexpected selector: {selector}")


@pytest.mark.asyncio
async def test_resume_then_switch_session_updates_session_cost(tmp_path):
    """
    TUI-oriented hardening test: resume a session with cost, then switch to another
    session with different cost, ensuring UI and BrokkApp state are synchronized.
    """
    workspace = tmp_path
    last_id = "resumed-session"
    other_id = "other-session"

    # Arrange workspace for resume
    save_last_session_id(workspace, last_id)
    get_session_zip_path(workspace, last_id).write_bytes(b"zip-data")

    stub = SessionStubExecutor()
    stub.wait_ready = AsyncMock(return_value=True)

    # Configure stub to return different costs based on session_id
    async def get_context_side_effect():
        if stub.session_id == last_id:
            return {"branch": "main", "totalCost": 5.0}
        if stub.session_id == other_id:
            return {"branch": "main", "totalCost": 1.0}
        return {"branch": "main", "totalCost": 0.0}

    stub.get_context = AsyncMock(side_effect=get_context_side_effect)

    # Stub switch_session to update the stub's internal session_id
    async def switch_side_effect(session_id: str):
        stub.session_id = session_id
        return {"id": session_id}

    stub.switch_session = AsyncMock(side_effect=switch_side_effect)
    stub.get_conversation = AsyncMock(return_value={"entries": []})

    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=True)

    # Start executor (patches ChatPanel to avoid real UI mounting)
    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    # Install fake chat to intercept UI updates
    fake_chat = FakeChat()
    app._maybe_chat = lambda: fake_chat  # type: ignore[method-assign]
    assert app._executor_ready is True

    # Step 1: Seed cost after resume
    await app._refresh_context_panel()

    assert app.session_total_cost == pytest.approx(5.0)
    assert app.session_total_cost_id == last_id
    assert fake_chat.last_session_cost == pytest.approx(5.0)
    assert fake_chat.status_line.last_kwargs["session_cost"] == pytest.approx(5.0)

    # Step 2: Switch to another session
    await app._switch_to_session(other_id)

    # Step 3: Verify costs are updated to the new session's values
    assert app.session_total_cost == pytest.approx(1.0)
    assert app.session_total_cost_id == other_id
    assert fake_chat.last_session_cost == pytest.approx(1.0)
    assert fake_chat.status_line.last_kwargs["session_cost"] == pytest.approx(1.0)
    assert app.current_job_cost == 0.0


@pytest.mark.asyncio
async def test_resume_session_seeds_session_cost_from_context(tmp_path):
    """Verify that resuming a session seeds the session cost from the executor context."""
    workspace = tmp_path
    last_id = "cost-session"
    save_last_session_id(workspace, last_id)
    zip_path = get_session_zip_path(workspace, last_id)
    zip_path.write_bytes(b"zip-data")

    stub = SessionStubExecutor()
    stub.wait_ready = AsyncMock(return_value=True)
    stub.get_context = AsyncMock(
        return_value={
            "branch": "main",
            "totalCost": 4.321,
        }
    )

    app = BrokkApp(executor=stub, workspace_dir=workspace, resume_session=True)

    with patch("brokk_code.app.ChatPanel"):
        await asyncio.wait_for(app._start_executor(), timeout=3.0)

    # Trigger context refresh which seeds the cost
    await app._refresh_context_panel()

    assert app.session_total_cost == pytest.approx(4.321, rel=1e-6)
    assert len(stub.import_calls) == 1
    assert stub.import_calls[0][1] == last_id
