import pytest
from unittest.mock import AsyncMock, MagicMock

from brokk_code.executor import ExecutorError, ExecutorManager


@pytest.fixture
def manager(tmp_path):
    """Create an ExecutorManager with a mocked HTTP client."""
    mgr = ExecutorManager(workspace_dir=tmp_path)
    mgr.base_url = "http://127.0.0.1:12345"
    mgr._http_client = AsyncMock()
    return mgr


@pytest.mark.asyncio
async def test_get_settings_makes_correct_request(manager):
    """Verify get_settings calls GET /v1/settings."""
    expected_response = {
        "buildDetails": {"buildLintCommand": "make lint"},
        "projectSettings": {"codeAgentTestScope": "ALL"},
        "shellConfig": {"executable": "/bin/bash", "args": ["-lc"]},
        "issueProvider": {"type": "NONE", "config": {}},
        "dataRetentionPolicy": "IMPROVE_BROKK",
    }

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.get = AsyncMock(return_value=mock_response)

    result = await manager.get_settings()

    manager._http_client.get.assert_called_once_with("/v1/settings")
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_get_settings_raises_when_not_started(tmp_path):
    """Verify get_settings raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.get_settings()


@pytest.mark.asyncio
async def test_update_build_settings_makes_correct_request(manager):
    """Verify update_build_settings calls POST /v1/settings/build with payload."""
    payload = {
        "buildLintCommand": "npm run lint",
        "buildLintEnabled": True,
    }
    expected_response = {"status": "ok", **payload}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_build_settings(payload)

    manager._http_client.post.assert_called_once_with("/v1/settings/build", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_build_settings_raises_when_not_started(tmp_path):
    """Verify update_build_settings raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_build_settings({"buildLintCommand": "make"})


@pytest.mark.asyncio
async def test_update_project_settings_makes_correct_request(manager):
    """Verify update_project_settings calls POST /v1/settings/project with payload."""
    payload = {
        "codeAgentTestScope": "WORKSPACE",
        "commitMessageFormat": "feat: {message}",
    }
    expected_response = {"status": "ok", **payload}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_project_settings(payload)

    manager._http_client.post.assert_called_once_with("/v1/settings/project", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_project_settings_raises_when_not_started(tmp_path):
    """Verify update_project_settings raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_project_settings({"codeAgentTestScope": "ALL"})


@pytest.mark.asyncio
async def test_update_shell_config_makes_correct_request(manager):
    """Verify update_shell_config calls POST /v1/settings/shell with payload."""
    payload = {
        "executable": "/usr/bin/zsh",
        "args": ["-c"],
    }
    expected_response = {"status": "ok", **payload}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_shell_config(payload)

    manager._http_client.post.assert_called_once_with("/v1/settings/shell", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_shell_config_raises_when_not_started(tmp_path):
    """Verify update_shell_config raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_shell_config({"executable": "/bin/sh"})


@pytest.mark.asyncio
async def test_update_issue_provider_makes_correct_request(manager):
    """Verify update_issue_provider calls POST /v1/settings/issues with payload."""
    payload = {
        "type": "GITHUB",
        "config": {"owner": "acme", "repo": "project"},
    }
    expected_response = {"status": "ok", **payload}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_issue_provider(payload)

    manager._http_client.post.assert_called_once_with("/v1/settings/issues", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_issue_provider_raises_when_not_started(tmp_path):
    """Verify update_issue_provider raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_issue_provider({"type": "NONE"})


@pytest.mark.asyncio
async def test_update_data_retention_makes_correct_request(manager):
    """Verify update_data_retention calls POST /v1/settings/data-retention with policy."""
    expected_response = {"policy": "MINIMAL"}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_data_retention("MINIMAL")

    manager._http_client.post.assert_called_once_with(
        "/v1/settings/data-retention", json={"policy": "MINIMAL"}
    )
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_data_retention_raises_when_not_started(tmp_path):
    """Verify update_data_retention raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_data_retention("IMPROVE_BROKK")
