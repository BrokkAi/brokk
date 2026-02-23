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
async def test_api_key_update_notes_restart_requirement(tmp_path: Path, monkeypatch):
    """Verify the API key update dialog mentions that a restart is required."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    settings = Settings(brokk_api_key="existing-key")
    monkeypatch.setattr(Settings, "load", lambda: settings)

    app = BrokkApp(executor=MagicMock())
    from textual.widgets import Static

    async with app.run_test() as pilot:
        await pilot.type("/api-key")
        await pilot.press("enter")

        modal = app.screen
        assert isinstance(modal, BrokkApiKeyModalScreen)
        footer = modal.query_one("#api-key-modal-footer", Static)
        # Check the actual content of the Static widget's renderable
        footer_text = str(footer.renderable)
        assert "next executor restart" in footer_text.lower()


@pytest.mark.asyncio
async def test_api_key_prompt_shows_brokk_welcome_and_signup_link(tmp_path: Path, monkeypatch):
    """Verify the API key prompt includes a short Brokk intro and signup URL."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    settings = Settings(brokk_api_key=None)
    monkeypatch.setattr(Settings, "load", lambda: settings)

    from textual.widgets import Markdown, Static
    app = BrokkApp(executor=MagicMock())
    async with app.run_test():
        modal = app.screen
        assert isinstance(modal, BrokkApiKeyModalScreen)

        # Verify Braille Icon
        icon = modal.query_one("#api-key-modal-icon", Static)
        assert "\u2800" in str(icon.renderable)

        # Verify Welcome Markdown content via the rendered text
        welcome = modal.query_one("#api-key-modal-welcome", Markdown)
        welcome_text = str(welcome.document).lower()
        assert "welcome to brokk" in welcome_text
        assert "context engineering" in welcome_text
        assert "https://brokk.ai/" in welcome_text


@pytest.mark.asyncio
@pytest.mark.parametrize("quit_key", ["ctrl+c", "ctrl+d"])
async def test_api_key_prompt_supports_ctrl_c_and_ctrl_d_exit(
    tmp_path: Path, monkeypatch, quit_key: str
):
    """Verify Ctrl+C/Ctrl+D trigger app quit while the API key prompt is open."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    settings = Settings(brokk_api_key=None)
    monkeypatch.setattr(Settings, "load", lambda: settings)

    app = BrokkApp(executor=MagicMock())
    app.action_quit = AsyncMock()  # type: ignore[method-assign]

    async with app.run_test() as pilot:
        assert isinstance(app.screen, BrokkApiKeyModalScreen)
        await pilot.press(quit_key)
        app.action_quit.assert_awaited_once()


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
