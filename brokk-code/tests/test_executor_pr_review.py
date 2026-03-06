import pytest
from unittest.mock import AsyncMock, MagicMock

from brokk_code.executor import ExecutorError, ExecutorManager


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
