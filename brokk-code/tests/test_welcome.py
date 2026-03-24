import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_welcome_message_shown_on_startup(tmp_path: Path):
    """Verify welcome message appears on every startup."""
    from brokk_code.settings import Settings

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)
    mock_executor.check_alive = MagicMock(return_value=True)
    mock_executor.get_health_live = AsyncMock(return_value={})
    mock_executor.create_session = AsyncMock()
    mock_executor.session_id = "test-session"
    mock_executor.stop = AsyncMock()
    mock_executor.get_context = AsyncMock(return_value={"usedTokens": 0})
    mock_executor.get_tasklist = AsyncMock(return_value={"tasks": []})
    mock_executor.get_model_config = AsyncMock(return_value={})

    for history in ([], ["previous prompt"]):
        with patch("brokk_code.app.load_history", return_value=history):
            # Ensure an API key exists so welcome is shown immediately
            with patch("brokk_code.settings.Settings.get_brokk_api_key", return_value="test-key"):
                with patch("brokk_code.settings.Settings.load") as mock_load:
                    mock_load.return_value = Settings(show_full_welcome=False)
                    app = BrokkApp(executor=mock_executor)
                    async with app.run_test() as pilot:
                        await pilot.pause()

                        chat_log = app.query_one("#chat-log")
                        content = "".join(str(line) for line in chat_log.lines)
                        message = f"Expected welcome with history={history!r}"
                        assert "Welcome to Brokk" in content, message
                        # Default startup uses the compact welcome variant.
                        assert "Context Engineering" not in content


def test_build_welcome_message_content():
    """Verify the welcome message contains expected branded content and commands."""
    from brokk_code import __version__
    from brokk_code.welcome import build_welcome_message

    mock_commands = [
        {"command": "/context", "description": "test"},
        {"command": "/task", "description": "test"},
    ]

    # Test Full
    msg = build_welcome_message(mock_commands, full=True)
    assert "Welcome to Brokk" in msg
    assert f"v{__version__}" in msg
    assert "context engineering" in msg.lower()
    assert "https://brokk.ai/" in msg
    assert "/context" in msg
    assert "/task" in msg

    # Test Compact
    msg_compact = build_welcome_message(mock_commands, full=False)
    assert "Welcome to Brokk" in msg_compact
    assert f"v{__version__}" in msg_compact
    assert "context engineering" not in msg_compact.lower()
    assert "### Context Engineering" not in msg_compact
    assert "https://brokk.ai/" not in msg_compact
    assert "/context" in msg_compact
    assert "/task" in msg_compact


def test_get_braille_icon_contains_braille():
    """Verify the icon helper returns characters in the Braille Unicode block."""
    from brokk_code.welcome import get_braille_icon

    icon = get_braille_icon()
    assert isinstance(icon, str)
    assert len(icon) > 0
    # Check for at least one Braille character (U+2800 to U+28FF)
    has_braille = any("\u2800" <= char <= "\u28ff" for char in icon)
    assert has_braille, "Icon should contain Unicode Braille characters"


def test_build_welcome_message_with_pypi_version():
    """Verify welcome message includes latest version notice when provided."""
    from brokk_code import __version__
    from brokk_code.welcome import build_welcome_message

    # Same version - no extra notice
    msg = build_welcome_message([], latest_pypi_version=__version__)
    assert f"Welcome to Brokk v{__version__}" in msg
    assert "Latest:" not in msg

    # Newer version available
    msg = build_welcome_message([], latest_pypi_version="99.9.9")
    assert f"Welcome to Brokk v{__version__} (Latest: 99.9.9)" in msg


@pytest.mark.asyncio
async def test_welcome_message_updates_after_pypi_fetch(tmp_path: Path):
    """Verify the app fetches PyPI version and updates the welcome message."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)

    with patch("brokk_code.settings.Settings.get_brokk_api_key", return_value="sk-test"):
        with patch(
            "brokk_code.app.BrokkApp._fetch_latest_pypi_version", new_callable=AsyncMock
        ) as mock_fetch:
            mock_fetch.return_value = "1.2.3"
            app = BrokkApp(executor=mock_executor)

            async with app.run_test() as pilot:
                await pilot.pause()
                # Wait for background check_for_updates worker
                for _ in range(10):
                    chat_log = app.query_one("#chat-log")
                    content = "".join(str(line) for line in chat_log.lines)
                    if "(Latest: 1.2.3)" in content:
                        break
                    await pilot.pause(0.1)

                assert "(Latest: 1.2.3)" in content


@pytest.mark.asyncio
async def test_full_welcome_on_first_startup_after_login(tmp_path: Path):
    """Verify show_full_welcome=True in settings triggers the full variant."""
    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)

    from brokk_code.settings import Settings

    with patch("brokk_code.settings.Settings.get_brokk_api_key", return_value="sk-test"):
        with patch("brokk_code.settings.Settings.load") as mock_load:
            # Setup settings with the one-time flag set
            mock_settings = Settings(show_full_welcome=True)
            mock_load.return_value = mock_settings

            app = BrokkApp(executor=mock_executor)
            async with app.run_test() as pilot:
                await pilot.pause()
                chat_log = app.query_one("#chat-log")
                content = "".join(str(line) for line in chat_log.lines)

                assert "Welcome to Brokk" in content
                assert "Context Engineering" in content
                # The flag should have been cleared and saved
                assert mock_settings.show_full_welcome is False


@pytest.mark.asyncio
async def test_show_welcome_message_handles_nomatches_gracefully(tmp_path: Path):
    """Verify _show_welcome_message does not crash if UI
    widgets are missing (e.g. during unmount)."""
    from textual.css.query import NoMatches

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    app = BrokkApp(executor=mock_executor)

    # Mock _maybe_chat to return a mock that raises NoMatches
    mock_chat = MagicMock()
    mock_chat.update_welcome.side_effect = NoMatches("No nodes match '#chat-log'")
    mock_chat.add_welcome.side_effect = NoMatches("No nodes match '#chat-log'")

    with patch.object(app, "_maybe_chat", return_value=mock_chat):
        # Should not raise
        app._show_welcome_message(refresh=False)
        app._show_welcome_message(refresh=True)

    mock_chat.add_welcome.assert_called_once()
    mock_chat.update_welcome.assert_called_once()


@pytest.mark.asyncio
async def test_login_flow_shows_full_welcome_once_only(tmp_path: Path):
    """Verify first-time login shows full welcome, but next startup is compact."""
    from brokk_code.settings import Settings

    mock_executor = MagicMock()
    mock_executor.workspace_dir = tmp_path
    mock_executor.start = AsyncMock()
    mock_executor.wait_live = AsyncMock(return_value=True)
    mock_executor.get_health_live = AsyncMock(return_value={})
    mock_executor.get_model_config = AsyncMock(return_value={})
    mock_executor.get_context = AsyncMock(return_value={"usedTokens": 0})
    mock_executor.get_tasklist = AsyncMock(return_value={"tasks": []})

    settings_inst = Settings(show_full_welcome=False)

    # 1. Simulate startup with NO API key
    with (
        patch("brokk_code.settings.Settings.load", return_value=settings_inst),
        patch("brokk_code.settings.Settings.save"),
        patch("brokk_code.settings.Settings.get_brokk_api_key", return_value=None),
        patch("brokk_code.app.write_brokk_api_key") as mock_write_key,
        patch("brokk_code.app.BrokkApiKeyModalScreen") as mock_modal_cls,
    ):
        app = BrokkApp(executor=mock_executor)

        # Capture the on_submit callback passed to the modal
        on_submit_cb = None

        def capture_modal_init(*args, **kwargs):
            nonlocal on_submit_cb
            on_submit_cb = kwargs.get("on_submit")
            if on_submit_cb is None and args:
                on_submit_cb = args[0]
            return MagicMock()

        mock_modal_cls.side_effect = capture_modal_init

        async with app.run_test() as pilot:
            await pilot.pause()
            assert on_submit_cb is not None

            # Invoke the login callback manually
            res = on_submit_cb("sk-new-key")
            if asyncio.iscoroutine(res):
                await res

            chat_log = app.query_one("#chat-log")
            content = "".join(str(line) for line in chat_log.lines)

            # Should show FULL welcome after login
            assert "Welcome to Brokk" in content
            assert "Context Engineering" in content

            # Verify settings.show_full_welcome is NOT True (which would cause double welcome)
            assert settings_inst.show_full_welcome is False
            mock_write_key.assert_called_once_with("sk-new-key")

    # 2. Simulate next startup WITH API key
    with (
        patch("brokk_code.settings.Settings.load", return_value=settings_inst),
        patch("brokk_code.settings.Settings.save"),
        patch("brokk_code.settings.Settings.get_brokk_api_key", return_value="sk-new-key"),
    ):
        app_next = BrokkApp(executor=mock_executor)
        async with app_next.run_test() as pilot:
            await pilot.pause()
            chat_log = app_next.query_one("#chat-log")
            content = "".join(str(line) for line in chat_log.lines)

            # Should show COMPACT welcome on second startup
            assert "Welcome to Brokk" in content
            assert "Context Engineering" not in content
