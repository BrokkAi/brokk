import os

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from brokk_code.executor import ExecutorError, ExecutorManager
from brokk_code.app import BrokkApp, _infer_github_repo_from_remote


@pytest.mark.asyncio
async def test_submit_pr_review_job_payload_construction(tmp_path):
    """Verify correct endpoint and JSON payload keys are sent."""
    manager = ExecutorManager(workspace_dir=tmp_path)
    manager.base_url = "http://127.0.0.1:12345"

    mock_response = MagicMock()
    mock_response.status_code = 201
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = {"jobId": "pr-review-job-123"}

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=mock_response)
    manager._http_client = mock_client

    job_id = await manager.submit_pr_review_job(
        planner_model="gpt-4",
        github_token="ghp_test_token",
        owner="test-owner",
        repo="test-repo",
        pr_number=42,
    )

    assert job_id == "pr-review-job-123"

    mock_client.post.assert_called_once()
    call_args = mock_client.post.call_args

    assert call_args[0][0] == "/v1/jobs/pr-review"

    payload = call_args[1]["json"]
    assert payload["plannerModel"] == "gpt-4"
    assert payload["githubToken"] == "ghp_test_token"
    assert payload["owner"] == "test-owner"
    assert payload["repo"] == "test-repo"
    assert payload["prNumber"] == 42

    headers = call_args[1]["headers"]
    assert "Idempotency-Key" in headers
    assert len(headers["Idempotency-Key"]) > 0


@pytest.mark.asyncio
async def test_submit_pr_review_job_returns_job_id(tmp_path):
    """Verify jobId is correctly extracted from response."""
    manager = ExecutorManager(workspace_dir=tmp_path)
    manager.base_url = "http://127.0.0.1:12345"

    mock_response = MagicMock()
    mock_response.status_code = 201
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = {"jobId": "unique-job-id-456"}

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=mock_response)
    manager._http_client = mock_client

    job_id = await manager.submit_pr_review_job(
        planner_model="claude-3",
        github_token="token",
        owner="owner",
        repo="repo",
        pr_number=1,
    )

    assert job_id == "unique-job-id-456"


@pytest.mark.asyncio
async def test_submit_pr_review_job_raises_on_http_error(tmp_path):
    """Verify ExecutorError is raised on non-2xx responses."""
    import httpx

    manager = ExecutorManager(workspace_dir=tmp_path)
    manager.base_url = "http://127.0.0.1:12345"

    mock_response = MagicMock()
    mock_response.status_code = 400

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(
        side_effect=httpx.HTTPStatusError(
            "Bad Request", request=MagicMock(), response=mock_response
        )
    )
    manager._http_client = mock_client

    with pytest.raises(ExecutorError) as exc_info:
        await manager.submit_pr_review_job(
            planner_model="model",
            github_token="token",
            owner="owner",
            repo="repo",
            pr_number=99,
        )

    assert "Failed POST /v1/jobs/pr-review" in str(exc_info.value)
    assert "status=400" in str(exc_info.value)


@pytest.mark.asyncio
async def test_submit_pr_review_job_raises_when_not_started(tmp_path):
    """Verify ExecutorError is raised when executor is not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError) as exc_info:
        await manager.submit_pr_review_job(
            planner_model="model",
            github_token="token",
            owner="owner",
            repo="repo",
            pr_number=1,
        )

    assert "Executor not started" in str(exc_info.value)


def test_review_command_in_slash_commands():
    """Verify /review appears in the slash command catalog."""
    commands = BrokkApp.get_slash_commands()
    command_names = [c["command"] for c in commands]
    assert "/review" in command_names

    review_cmd = next(c for c in commands if c["command"] == "/review")
    assert "description" in review_cmd
    assert review_cmd["description"]


def test_infer_github_repo_from_remote_https(tmp_path, monkeypatch):
    """Test HTTPS remote URL parsing."""
    import subprocess as real_subprocess

    class FakeResult:
        returncode = 0
        stdout = "https://github.com/test-owner/test-repo.git\n"
        stderr = ""

    def fake_run(cmd, **kwargs):
        return FakeResult()

    monkeypatch.setattr("brokk_code.app.subprocess.run", fake_run)

    owner, repo = _infer_github_repo_from_remote(tmp_path)
    assert owner == "test-owner"
    assert repo == "test-repo"


def test_infer_github_repo_from_remote_ssh(tmp_path, monkeypatch):
    """Test SSH remote URL parsing."""

    class FakeResult:
        returncode = 0
        stdout = "git@github.com:ssh-owner/ssh-repo.git\n"
        stderr = ""

    def fake_run(cmd, **kwargs):
        return FakeResult()

    monkeypatch.setattr("brokk_code.app.subprocess.run", fake_run)

    owner, repo = _infer_github_repo_from_remote(tmp_path)
    assert owner == "ssh-owner"
    assert repo == "ssh-repo"


def test_infer_github_repo_from_remote_failure(tmp_path, monkeypatch):
    """Test handling of git command failure."""

    class FakeResult:
        returncode = 1
        stdout = ""
        stderr = "fatal: not a git repository"

    def fake_run(cmd, **kwargs):
        return FakeResult()

    monkeypatch.setattr("brokk_code.app.subprocess.run", fake_run)

    owner, repo = _infer_github_repo_from_remote(tmp_path)
    assert owner is None
    assert repo is None


def test_handle_review_command_missing_token(tmp_path, monkeypatch):
    """Verify error message when GITHUB_TOKEN is not set."""
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = True

    # Mock the chat panel
    messages = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())

    # Mock remote inference to succeed
    monkeypatch.setattr(
        "brokk_code.app._infer_github_repo_from_remote",
        lambda _: ("owner", "repo"),
    )

    app._handle_review_command(["/review", "123"])

    assert len(messages) == 1
    assert "GITHUB_TOKEN" in messages[0][0]
    assert messages[0][1] == "ERROR"


def test_handle_review_command_missing_remote(tmp_path, monkeypatch):
    """Verify error message when git remote cannot be inferred."""
    monkeypatch.setenv("GITHUB_TOKEN", "test-token")

    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = True

    messages = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())

    # Mock remote inference to fail
    monkeypatch.setattr(
        "brokk_code.app._infer_github_repo_from_remote",
        lambda _: (None, None),
    )

    app._handle_review_command(["/review", "123"])

    assert len(messages) == 1
    assert "Could not infer" in messages[0][0]
    assert messages[0][1] == "ERROR"


def test_handle_review_command_submits_job(tmp_path, monkeypatch):
    """Verify that /review 123 submits a job with correct parameters."""
    monkeypatch.setenv("GITHUB_TOKEN", "test-token")

    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = True
    app.current_model = "gpt-4"

    messages = []
    workers = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())
    monkeypatch.setattr(
        "brokk_code.app._infer_github_repo_from_remote",
        lambda _: ("inferred-owner", "inferred-repo"),
    )

    # Capture run_worker calls
    original_run_worker = app.run_worker

    def mock_run_worker(coro):
        workers.append(coro)

    monkeypatch.setattr(app, "run_worker", mock_run_worker)

    app._handle_review_command(["/review", "42"])

    # Verify submission message was shown
    assert any("Submitting PR review" in m[0] for m in messages)
    assert any("inferred-owner/inferred-repo#42" in m[0] for m in messages)

    # Verify worker was queued
    assert len(workers) == 1


def test_handle_review_command_explicit_owner_repo(tmp_path, monkeypatch):
    """Verify /review owner repo 123 uses explicit values."""
    monkeypatch.setenv("GITHUB_TOKEN", "test-token")

    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = True
    app.current_model = "gpt-4"

    messages = []
    workers = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())

    def mock_run_worker(coro):
        workers.append(coro)

    monkeypatch.setattr(app, "run_worker", mock_run_worker)

    app._handle_review_command(["/review", "explicit-owner", "explicit-repo", "99"])

    assert any("explicit-owner/explicit-repo#99" in m[0] for m in messages)
    assert len(workers) == 1


def test_handle_review_command_invalid_pr_number(tmp_path, monkeypatch):
    """Verify error on non-integer PR number."""
    monkeypatch.setenv("GITHUB_TOKEN", "test-token")

    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = True

    messages = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())

    app._handle_review_command(["/review", "not-a-number"])

    assert len(messages) == 1
    assert "Invalid PR number" in messages[0][0]
    assert messages[0][1] == "ERROR"


def test_handle_review_command_executor_not_ready(tmp_path, monkeypatch):
    """Verify error when executor is not ready."""
    app = BrokkApp(workspace_dir=tmp_path)
    app._executor_ready = False

    messages = []

    class MockChat:
        def add_system_message(self, msg, level="INFO"):
            messages.append((msg, level))

    monkeypatch.setattr(app, "_maybe_chat", lambda: MockChat())

    app._handle_review_command(["/review", "123"])

    assert len(messages) == 1
    assert "not ready" in messages[0][0].lower()
    assert messages[0][1] == "ERROR"


@pytest.mark.asyncio
async def test_submit_pr_review_job_uses_unique_idempotency_keys(tmp_path):
    """Verify each call generates a unique idempotency key."""
    manager = ExecutorManager(workspace_dir=tmp_path)
    manager.base_url = "http://127.0.0.1:12345"

    mock_response = MagicMock()
    mock_response.status_code = 201
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = {"jobId": "job-id"}

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=mock_response)
    manager._http_client = mock_client

    await manager.submit_pr_review_job(
        planner_model="model",
        github_token="token",
        owner="owner",
        repo="repo",
        pr_number=1,
    )

    await manager.submit_pr_review_job(
        planner_model="model",
        github_token="token",
        owner="owner",
        repo="repo",
        pr_number=2,
    )

    assert mock_client.post.call_count == 2

    first_key = mock_client.post.call_args_list[0][1]["headers"]["Idempotency-Key"]
    second_key = mock_client.post.call_args_list[1][1]["headers"]["Idempotency-Key"]

    assert first_key != second_key
