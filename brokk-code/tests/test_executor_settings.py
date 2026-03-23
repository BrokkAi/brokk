"""Tests for ExecutorManager settings HTTP client methods."""

from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.executor import ExecutorError, ExecutorManager


@pytest.fixture
def mock_http_client():
    """Creates a mock HTTP client for testing."""
    client = AsyncMock()
    return client


@pytest.fixture
def executor_with_mock_client(tmp_path, mock_http_client):
    """Creates an ExecutorManager with a mocked HTTP client."""
    manager = ExecutorManager(workspace_dir=tmp_path)
    manager._http_client = mock_http_client
    manager.base_url = "http://127.0.0.1:12345"
    return manager


@pytest.mark.asyncio
async def test_get_settings_returns_json(executor_with_mock_client, mock_http_client):
    """Test that get_settings() calls GET /v1/settings and returns JSON."""
    expected_settings = {
        "buildDetails": {"buildLintCommand": "make lint"},
        "projectSettings": {"codeAgentTestScope": "ALL"},
    }
    mock_response = MagicMock()
    mock_response.json.return_value = expected_settings
    mock_response.raise_for_status = MagicMock()
    mock_http_client.get.return_value = mock_response

    result = await executor_with_mock_client.get_settings()

    mock_http_client.get.assert_called_once_with("/v1/settings")
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_settings


@pytest.mark.asyncio
async def test_get_settings_raises_when_not_started(tmp_path):
    """Test that get_settings() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.get_settings()


@pytest.mark.asyncio
async def test_update_build_settings_posts_json(executor_with_mock_client, mock_http_client):
    """Test that update_build_settings() POSTs to /v1/settings/build."""
    payload = {"buildLintCommand": "npm run lint", "buildLintEnabled": True}
    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "updated"}
    mock_response.raise_for_status = MagicMock()
    mock_http_client.post.return_value = mock_response

    result = await executor_with_mock_client.update_build_settings(payload)

    mock_http_client.post.assert_called_once_with("/v1/settings/build", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == {"status": "updated"}


@pytest.mark.asyncio
async def test_update_build_settings_raises_when_not_started(tmp_path):
    """Test that update_build_settings() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_build_settings({"buildLintCommand": "make"})


@pytest.mark.asyncio
async def test_update_project_settings_posts_json(executor_with_mock_client, mock_http_client):
    """Test that update_project_settings() POSTs to /v1/settings/project."""
    payload = {"codeAgentTestScope": "WORKSPACE", "runCommandTimeoutSeconds": 600}
    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "updated"}
    mock_response.raise_for_status = MagicMock()
    mock_http_client.post.return_value = mock_response

    result = await executor_with_mock_client.update_project_settings(payload)

    mock_http_client.post.assert_called_once_with("/v1/settings/project", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == {"status": "updated"}


@pytest.mark.asyncio
async def test_update_project_settings_raises_when_not_started(tmp_path):
    """Test that update_project_settings() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_project_settings({"codeAgentTestScope": "ALL"})


@pytest.mark.asyncio
async def test_update_shell_config_posts_json(executor_with_mock_client, mock_http_client):
    """Test that update_shell_config() POSTs to /v1/settings/shell."""
    payload = {"executable": "/bin/zsh", "args": ["-c"]}
    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "updated"}
    mock_response.raise_for_status = MagicMock()
    mock_http_client.post.return_value = mock_response

    result = await executor_with_mock_client.update_shell_config(payload)

    mock_http_client.post.assert_called_once_with("/v1/settings/shell", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == {"status": "updated"}


@pytest.mark.asyncio
async def test_update_shell_config_raises_when_not_started(tmp_path):
    """Test that update_shell_config() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_shell_config({"executable": "/bin/bash"})


@pytest.mark.asyncio
async def test_update_issue_provider_posts_json(executor_with_mock_client, mock_http_client):
    """Test that update_issue_provider() POSTs to /v1/settings/issues."""
    payload = {"type": "GITHUB", "config": {"owner": "acme", "repo": "project"}}
    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "updated"}
    mock_response.raise_for_status = MagicMock()
    mock_http_client.post.return_value = mock_response

    result = await executor_with_mock_client.update_issue_provider(payload)

    mock_http_client.post.assert_called_once_with("/v1/settings/issues", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == {"status": "updated"}


@pytest.mark.asyncio
async def test_update_issue_provider_raises_when_not_started(tmp_path):
    """Test that update_issue_provider() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_issue_provider({"type": "NONE"})


@pytest.mark.asyncio
async def test_update_data_retention_posts_policy(executor_with_mock_client, mock_http_client):
    """Test that update_data_retention() POSTs {"policy": ...} to /v1/settings/data-retention."""
    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "updated"}
    mock_response.raise_for_status = MagicMock()
    mock_http_client.post.return_value = mock_response

    result = await executor_with_mock_client.update_data_retention("MINIMAL")

    mock_http_client.post.assert_called_once_with(
        "/v1/settings/data-retention", json={"policy": "MINIMAL"}
    )
    mock_response.raise_for_status.assert_called_once()
    assert result == {"status": "updated"}


@pytest.mark.asyncio
async def test_update_data_retention_raises_when_not_started(tmp_path):
    """Test that update_data_retention() raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_data_retention("IMPROVE_BROKK")
