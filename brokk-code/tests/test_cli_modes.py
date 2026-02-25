import sys
from types import ModuleType
from typing import Any
from unittest.mock import patch

import pytest

import brokk_code.__main__ as main_module


def test_main_defaults_to_tui(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")

    class FakeApp:
        def __init__(self, **kwargs: Any):
            captured["kwargs"] = kwargs

        def run(self) -> None:
            captured["ran"] = True

    fake_app_module.BrokkApp = FakeApp
    monkeypatch.setitem(sys.modules, "brokk_code.app", fake_app_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "--workspace",
            str(tmp_path),
            "--session",
            "session-1",
            "--vendor",
            "OpenAI",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["session_id"] == "session-1"
    assert captured["kwargs"]["vendor"] == "OpenAI"


def test_main_acp_routes_to_server(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    fake_acp_module = ModuleType("brokk_code.acp_server")

    async def fake_run_acp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    fake_acp_module.run_acp_server = fake_run_acp_server
    monkeypatch.setitem(sys.modules, "brokk_code.acp_server", fake_acp_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "acp",
            "--workspace",
            str(tmp_path),
            "--executor-stable",
            "--vendor",
            "Gemini",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["executor_snapshot"] is False
    assert captured["kwargs"]["ide"] == "intellij"
    assert captured["kwargs"]["vendor"] == "Gemini"


def test_main_acp_routes_to_server_with_ide(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    fake_acp_module = ModuleType("brokk_code.acp_server")

    async def fake_run_acp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    fake_acp_module.run_acp_server = fake_run_acp_server
    monkeypatch.setitem(sys.modules, "brokk_code.acp_server", fake_acp_module)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "acp", "--workspace", str(tmp_path), "--ide", "zed"],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["ide"] == "zed"


def test_main_acp_rejects_extra_positional(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "acp", "zed", "--workspace", str(tmp_path)],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 2


def test_main_install_zed_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "zed" / "settings.json"

    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed", "--force"])

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Zed ACP integration" in output


def test_main_install_zed_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_zed_invalid_json_exits_nonzero(monkeypatch) -> None:
    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        raise ValueError("Could not parse as JSON/JSONC")

    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_intellij_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / "intellij-config"

    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij", "--force"])

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured IntelliJ ACP integration" in output


def test_main_install_intellij_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_intellij_invalid_json_exits_nonzero(monkeypatch) -> None:
    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        raise ValueError("Could not parse as JSON")

    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_uses_git_repo_root_for_nested_workspace(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")
    repo_root = tmp_path / "repo"
    nested_workspace = repo_root / "src" / "feature"
    nested_workspace.mkdir(parents=True)
    (repo_root / ".git").mkdir()

    class FakeApp:
        def __init__(self, **kwargs: Any):
            captured["kwargs"] = kwargs

        def run(self) -> None:
            captured["ran"] = True

    fake_app_module.BrokkApp = FakeApp
    monkeypatch.setitem(sys.modules, "brokk_code.app", fake_app_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "--workspace",
            str(nested_workspace),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == repo_root.resolve()


def test_main_keeps_workspace_when_not_in_git_repo(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")
    nested_workspace = tmp_path / "workspace" / "src"
    nested_workspace.mkdir(parents=True)

    class FakeApp:
        def __init__(self, **kwargs: Any):
            captured["kwargs"] = kwargs

        def run(self) -> None:
            captured["ran"] = True

    fake_app_module.BrokkApp = FakeApp
    monkeypatch.setitem(sys.modules, "brokk_code.app", fake_app_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "--workspace",
            str(nested_workspace),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == nested_workspace.resolve()


def test_main_resume_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")

    class FakeApp:
        def __init__(self, **kwargs: Any):
            captured["kwargs"] = kwargs

        def run(self) -> None:
            captured["ran"] = True

    fake_app_module.BrokkApp = FakeApp
    monkeypatch.setitem(sys.modules, "brokk_code.app", fake_app_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "resume",
            "session-xyz",
            "--workspace",
            str(tmp_path),
            "--vendor",
            "Anthropic",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["session_id"] == "session-xyz"
    assert captured["kwargs"]["resume_session"] is False
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["vendor"] == "Anthropic"


def test_main_issue_create_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_123",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
            "--planner-model",
            "custom-model",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["task_input"] == "Broken build"
    assert captured["kwargs"]["mode"] == "ISSUE_WRITER"
    assert captured["kwargs"]["planner_model"] == "custom-model"
    assert captured["kwargs"]["tags"]["github_token"] == "ghp_123"
    assert captured["kwargs"]["tags"]["repo_owner"] == "acme"
    assert captured["kwargs"]["tags"]["repo_name"] == "tools"


def test_main_issue_create_missing_prompt_exits_nonzero(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "--github-token",
            "ghp_123",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_respects_env_github_token(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setenv("GITHUB_TOKEN", "env-token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["tags"]["github_token"] == "env-token"
    assert captured["kwargs"]["planner_model"] == "gemini-3-flash-preview"
    assert captured["kwargs"]["planner_reasoning_level"] == "disable"
    assert captured["kwargs"]["verbose"] is False


def test_main_issue_create_verbose_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "-v",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["verbose"] is True


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_creates_session_before_wait_ready(
    mock_executor_class, tmp_path
) -> None:
    """Verifies that run_headless_job creates a session before polling for readiness.

    This ordering is required because the Java executor's /health/ready endpoint
    only returns 200 OK after a session has been created.
    """
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    async def mock_start():
        call_order.append("start")

    async def mock_create_session(name: str = ""):
        call_order.append("create_session")
        return "session-123"

    async def mock_wait_ready(timeout: float = 30.0):
        call_order.append("wait_ready")
        return True

    async def mock_submit_job(**kwargs):
        call_order.append("submit_job")
        return "job-456"

    async def mock_stream_events(job_id: str):
        # Yield a terminal state event to end the job
        yield {"type": "STATE_CHANGE", "state": "COMPLETED"}

    async def mock_stop():
        call_order.append("stop")

    mock_manager.start = AsyncMock(side_effect=mock_start)
    mock_manager.create_session = AsyncMock(side_effect=mock_create_session)
    mock_manager.wait_ready = AsyncMock(side_effect=mock_wait_ready)
    mock_manager.submit_job = AsyncMock(side_effect=mock_submit_job)
    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock(side_effect=mock_stop)

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Test task",
        planner_model="test-model",
        mode="LUTZ",
        tags={},
    )

    # Verify the critical ordering: create_session MUST come before wait_ready
    assert "start" in call_order
    assert "create_session" in call_order
    assert "wait_ready" in call_order
    assert "submit_job" in call_order

    start_idx = call_order.index("start")
    create_session_idx = call_order.index("create_session")
    wait_ready_idx = call_order.index("wait_ready")
    submit_job_idx = call_order.index("submit_job")

    # The critical assertion: session must be created before waiting for readiness
    assert start_idx < create_session_idx, "start() must be called before create_session()"
    assert create_session_idx < wait_ready_idx, (
        "create_session() must be called before wait_ready()"
    )
    assert wait_ready_idx < submit_job_idx, "wait_ready() must be called before submit_job()"


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_reports_failed_terminal_state(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "STATE_CHANGE", "state": "RUNNING"}
        yield {"type": "ERROR", "message": "GitHub API returned 403"}
        yield {"type": "STATE_CHANGE", "state": "FAILED"}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "Error event: GitHub API returned 403" in captured.err
    assert "ISSUE_WRITER job ended with state FAILED." in captured.err
    assert "Last error: GitHub API returned 403" in captured.err
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_reports_stage_on_submit_failure(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    from brokk_code.executor import ExecutorError

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(side_effect=ExecutorError("401 Unauthorized"))
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert (
        "Executor error during ISSUE_WRITER job (submitting job): 401 Unauthorized" in captured.err
    )
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_uses_nested_event_data_for_errors_and_quiet_notifications(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": ""}}
        yield {"type": "NOTIFICATION", "data": {"level": "WARN", "message": "rate limit near"}}
        yield {"type": "LLM_TOKEN", "data": {"token": "hello"}}
        yield {"type": "ERROR", "data": {"message": "executor boom"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "FAILED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "[INFO] None" not in captured.out
    assert "[WARN] rate limit near" in captured.out
    assert "hello" not in captured.out
    assert "Error event: executor boom" in captured.err
    assert "Unknown error event" not in captured.err


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_verbose_shows_full_event_output(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "planning"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "RUNNING"}}
        yield {"type": "LLM_TOKEN", "data": {"token": "hello"}}
        yield {"type": "COMMAND_RESULT", "data": {"command": "gh issue create", "output": "ok"}}
        yield {"type": "TOOL_OUTPUT", "data": {"text": "tool text"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
        verbose=True,
    )

    captured = capsys.readouterr()
    assert "[INFO] planning" in captured.out
    assert "Job state: RUNNING" in captured.out
    assert "hello" in captured.out
    assert "[COMMAND_RESULT]" in captured.out
    assert "[TOOL_OUTPUT]" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_exits_nonzero_on_error_event_without_failed_state(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "Job started"}}
        yield {"type": "ERROR", "data": {"message": "parseIssueResponse: invalid JSON"}}
        # Stream ends without a terminal FAILED/CANCELLED state event.

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "Job finished." not in captured.out
    assert "ISSUE_WRITER job ended with errors (last observed state: UNKNOWN)." in captured.err
    assert "Last error: parseIssueResponse: invalid JSON" in captured.err


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_suppressed_tokens(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "LLM_TOKEN", "data": {"token": "Created issue: "}}
        yield {
            "type": "LLM_TOKEN",
            "data": {"token": "https://github.com/brokkai/brokk/issues/123"},
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/123" in captured.out
    assert "Job submitted:" not in captured.out
    assert "Job finished." not in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_issue_writer_notification(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "NOTIFICATION",
            "data": {
                "level": "INFO",
                "message": "ISSUE_WRITER: issue created I_kwDOXYZ https://github.com/brokkai/brokk/issues/456",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/456" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_tool_output_result_text(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "TOOL_OUTPUT",
            "data": {
                "resultText": "Created: https://github.com/brokkai/brokk/issues/789",
                "name": "createGitHubIssue",
                "status": "SUCCESS",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/789" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_structured_issue_created_event(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "ISSUE_CREATED",
            "data": {
                "issueId": "#987",
                "issueUrl": "https://github.com/brokkai/brokk/issues/987",
                "repoOwner": "brokkai",
                "repoName": "brokk",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/987" in captured.out


def test_main_issue_solve_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_solve",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
            "--skip-verification",
            "--max-issue-fix-attempts",
            "7",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["mode"] == "ISSUE"
    assert captured["kwargs"]["task_input"] == "Resolve GitHub Issue #123"
    assert captured["kwargs"]["tags"]["issue_number"] == "123"
    assert captured["kwargs"]["tags"]["github_token"] == "ghp_solve"
    assert captured["kwargs"]["skip_verification"] is True
    assert captured["kwargs"]["max_issue_fix_attempts"] == 7


def test_main_issue_solve_missing_number_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--repo-owner",
            "acme",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0
