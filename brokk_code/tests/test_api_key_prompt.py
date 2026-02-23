from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp, BrokkApiKeyModalScreen
from brokk_code.settings import Settings


@pytest.mark.asyncio
async def test_api_key_prompt_shown_when_missing(tmp_path: Path, monkeypatch):
    """Verify API key prompt appears on startup if no key is present."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    
    # Mock settings to have no key
    settings = Settings(brokk_api_key=None)
    monkeypatch.setattr(Settings, "load", lambda: settings)

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    
    app = BrokkApp(executor=mock_executor)
    
    async with app.run_test() as pilot:
        # Check if BrokkApiKeyModalScreen is the active screen
        assert isinstance(app.screen, BrokkApiKeyModalScreen)
        
        # Enter a key
        await pilot.type("sk-test-key-123")
        await pilot.press("enter")
        
        # Verify settings were updated and saved
        assert settings.brokk_api_key == "sk-test-key-123"
        assert mock_executor.brokk_api_key == "sk-test-key-123"
        
        # Verify start was eventually called (via worker)
        # We might need a small wait since start_executor is a worker
        mock_executor.start.assert_called_once()


@pytest.mark.asyncio
async def test_api_key_slash_command_opens_modal(tmp_path: Path, monkeypatch):
    """Verify /api-key slash command opens the API key modal and updates settings."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Mock settings to have an existing key
    settings = Settings(brokk_api_key="old-key")
    monkeypatch.setattr(Settings, "load", lambda: settings)

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()

    app = BrokkApp(executor=mock_executor)

    async with app.run_test() as pilot:
        # Simulate typing the slash command
        await pilot.type("/api-key")
        await pilot.press("enter")

        # Verify modal is shown
        assert isinstance(app.screen, BrokkApiKeyModalScreen)

        # Type new key
        await pilot.type("new-secret-key")
        await pilot.press("enter")

        # Verify update
        assert settings.brokk_api_key == "new-secret-key"
        assert mock_executor.brokk_api_key == "new-secret-key"


@pytest.mark.asyncio
async def test_api_key_not_prompted_if_present(tmp_path: Path, monkeypatch):
    """Verify app starts normally if API key is already present."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    
    settings = Settings(brokk_api_key="existing-key")
    monkeypatch.setattr(Settings, "load", lambda: settings)

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    
    app = BrokkApp(executor=mock_executor)
    
    async with app.run_test() as pilot:
        # Should NOT be on the modal screen
        assert not isinstance(app.screen, BrokkApiKeyModalScreen)
        mock_executor.start.assert_called_once()
