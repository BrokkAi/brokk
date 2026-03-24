from unittest.mock import AsyncMock, MagicMock

import pytest

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
async def test_update_all_settings_makes_correct_request(manager):
    """Verify update_all_settings calls POST /v1/settings with payload."""
    payload = {
        "buildDetails": {"buildLintCommand": "npm run lint"},
        "projectSettings": {"codeAgentTestScope": "WORKSPACE"},
        "shellConfig": {"executable": "/bin/zsh", "args": ["-c"]},
        "issueProvider": {"type": "NONE", "config": {}},
        "dataRetentionPolicy": "MINIMAL",
        "analyzerLanguages": {"languages": ["JAVA", "PYTHON"]},
    }
    expected_response = {"status": "updated"}

    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = expected_response
    manager._http_client.post = AsyncMock(return_value=mock_response)

    result = await manager.update_all_settings(payload)

    manager._http_client.post.assert_called_once_with("/v1/settings", json=payload)
    mock_response.raise_for_status.assert_called_once()
    assert result == expected_response


@pytest.mark.asyncio
async def test_update_all_settings_raises_when_not_started(tmp_path):
    """Verify update_all_settings raises ExecutorError when executor not started."""
    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match="Executor not started"):
        await manager.update_all_settings({"buildDetails": {}})
