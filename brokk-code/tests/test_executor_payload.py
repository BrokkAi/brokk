import json
import pytest
from unittest.mock import AsyncMock, MagicMock
from brokk_code.executor import ExecutorManager

@pytest.mark.asyncio
async def test_submit_job_payload_construction():
    """Verify that submit_job builds the correct JSON payload for various options."""
    manager = ExecutorManager()
    manager._http_client = AsyncMock()
    
    # Mock response
    mock_response = MagicMock()
    mock_response.json.return_value = {"jobId": "test-job-123"}
    mock_response.raise_for_status = MagicMock()
    manager._http_client.post.return_value = mock_response

    # Test case 1: Basic submission
    await manager.submit_job(
        task_input="fix bug",
        planner_model="gpt-4o",
        mode="ARCHITECT"
    )
    
    _, kwargs = manager._http_client.post.call_args
    payload = kwargs["json"]
    assert payload["taskInput"] == "fix bug"
    assert payload["plannerModel"] == "gpt-4o"
    assert payload["tags"]["mode"] == "ARCHITECT"
    assert "skipVerification" not in payload
    assert "maxIssueFixAttempts" not in payload

    # Test case 2: ISSUE mode with specific fields
    await manager.submit_job(
        task_input="solve issue",
        planner_model="claude-3-5-sonnet",
        code_model="claude-3-5-sonnet",
        mode="ISSUE",
        skip_verification=True,
        max_issue_fix_attempts=5,
        tags={"custom": "tag"}
    )
    
    _, kwargs = manager._http_client.post.call_args
    payload = kwargs["json"]
    assert payload["taskInput"] == "solve issue"
    assert payload["codeModel"] == "claude-3-5-sonnet"
    assert payload["tags"]["mode"] == "ISSUE"
    assert payload["tags"]["custom"] == "tag"
    assert payload["skipVerification"] is True
    assert payload["maxIssueFixAttempts"] == 5

@pytest.mark.asyncio
async def test_submit_job_omits_none_fields():
    """Ensure optional fields are omitted from JSON when they are None."""
    manager = ExecutorManager()
    manager._http_client = AsyncMock()
    
    mock_response = MagicMock()
    mock_response.json.return_value = {"jobId": "test-job-456"}
    manager._http_client.post.return_value = mock_response

    await manager.submit_job(
        task_input="minimal",
        planner_model="model",
        skip_verification=None,
        max_issue_fix_attempts=None
    )
    
    _, kwargs = manager._http_client.post.call_args
    payload = kwargs["json"]
    assert "skipVerification" not in payload
    assert "maxIssueFixAttempts" not in payload


@pytest.mark.asyncio
async def test_submit_job_issue_solve_payload():
    """Verify payload for ISSUE solve mode specifically."""
    manager = ExecutorManager()
    manager._http_client = AsyncMock()

    mock_response = MagicMock()
    mock_response.json.return_value = {"jobId": "issue-job-1"}
    manager._http_client.post.return_value = mock_response

    await manager.submit_job(
        task_input="Resolve #123",
        planner_model="claude-3-5",
        mode="ISSUE",
        tags={"issue_number": "123", "github_token": "secret"},
        skip_verification=True,
        max_issue_fix_attempts=10,
    )

    _, kwargs = manager._http_client.post.call_args
    payload = kwargs["json"]

    assert payload["taskInput"] == "Resolve #123"
    assert payload["tags"]["mode"] == "ISSUE"
    assert payload["tags"]["issue_number"] == "123"
    assert payload["tags"]["github_token"] == "secret"
    assert payload["skipVerification"] is True
    assert payload["maxIssueFixAttempts"] == 10
