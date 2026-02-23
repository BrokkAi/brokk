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
    mock_executor.start = AsyncMock()

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
async def test_api_key_startup_save_failure_prevents_executor_start(tmp_path: Path, monkeypatch):
    """Verify that if saving the API key fails at startup, the executor is NOT started."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Mock settings to have no key
    settings = Settings(brokk_api_key=None)
    monkeypatch.setattr(Settings, "load", lambda: settings)

    # Mock save to fail
    def mock_save_fail():
        raise OSError("Disk full or permission denied")

    monkeypatch.setattr(settings, "save", mock_save_fail)

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()

    app = BrokkApp(executor=mock_executor)

    async with app.run_test() as pilot:
        assert isinstance(app.screen, BrokkApiKeyModalScreen)

        # Submit a key
        await pilot.type("sk-failure-test")
        await pilot.press("enter")

        # Verify start was NOT called
        mock_executor.start.assert_not_called()

        # Verify error message in chat log
        chat_log = app.query_one("#chat-log")
        content = "".join(str(line) for line in chat_log.lines)
        assert "Failed to save API key" in content
        assert "Disk full" in content


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


@pytest.mark.asyncio
async def test_no_crash_message_during_api_key_prompt(tmp_path: Path, monkeypatch):
    """
    Verify that the 'Executor process crashed unexpectedly' message does not appear
    while waiting for the API key, even if check_alive() returns False.
    """
    import asyncio

    import brokk_code.app

    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Mock settings to have no key
    settings = Settings(brokk_api_key=None)
    monkeypatch.setattr(Settings, "load", lambda: settings)

    # Patch asyncio.sleep to be fast and trackable in the app module.
    # We await the real sleep(0) to allow the event loop to cycle.
    async def fast_sleep(delay):
        await asyncio.sleep(0)

    sleep_mock = AsyncMock(side_effect=fast_sleep)
    monkeypatch.setattr(brokk_code.app.asyncio, "sleep", sleep_mock)

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    # check_alive returning False usually triggers the crash message,
    # but it should be ignored until _executor_started is True.
    mock_executor.check_alive.return_value = False
    mock_executor.start = AsyncMock()

    app = BrokkApp(executor=mock_executor)

    async with app.run_test() as pilot:
        # Verify we are at the API key prompt
        assert isinstance(app.screen, BrokkApiKeyModalScreen)

        # Allow background workers (like _monitor_executor) to cycle.
        # Since sleep is patched to 0, this will process several iterations.
        await pilot.pause()

        # Check chat log for crash message
        chat_log = app.query_one("#chat-log")
        content = "".join(str(line) for line in chat_log.lines)
        assert "Executor process crashed unexpectedly" not in content

        # The guard should have prevented calling check_alive while _executor_started is False
        assert app._executor_started is False
        mock_executor.check_alive.assert_not_called()

        # Now enter the key to start the executor
        await pilot.type("sk-test-key")
        await pilot.press("enter")

        # Verify start was called
        mock_executor.start.assert_called_once()
