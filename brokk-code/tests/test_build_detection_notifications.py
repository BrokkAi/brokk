import pytest
from pathlib import Path
from unittest.mock import MagicMock

from brokk_code.app import BrokkApp
from brokk_code.settings import Settings


@pytest.mark.asyncio
async def test_build_detection_notification_handling(monkeypatch):
    # Mock settings to avoid loading/creating real config files
    monkeypatch.setattr(Settings, "load", MagicMock(return_value=Settings()))

    # Setup App with mock executor and Path object
    app = BrokkApp(workspace_dir=Path("/tmp"))
    app.executor = MagicMock()

    # Mock chat panel and status line
    mock_chat = MagicMock()
    mock_status = MagicMock()

    # Mock the internal lookups used by _update_statusline
    monkeypatch.setattr(app, "_maybe_chat", lambda: mock_chat)
    monkeypatch.setattr(app, "_maybe_statusline", lambda: mock_status)

    # In BrokkApp._update_statusline, it tries chat.query_one("#status-line")
    # We make that return our mock_status
    mock_chat.query_one.return_value = mock_status

    # 1. Test "Inferring..."
    event_inferring = {
        "type": "NOTIFICATION",
        "data": {"level": "INFO", "message": "Inferring project build details"},
    }
    app._handle_event(event_inferring)

    assert app.build_detection_status == "Detecting project settings…"
    mock_status.update_status.assert_called()
    # Check if last call passed build_status
    args, kwargs = mock_status.update_status.call_args
    assert kwargs.get("build_status") == "Detecting project settings…"
    mock_chat.add_system_message.assert_called_with("Inferring project build details", level="INFO")

    # 2. Test "Inferred and saved"
    event_ready = {
        "type": "NOTIFICATION",
        "data": {"level": "INFO", "message": "Build details inferred and saved"},
    }
    app._handle_event(event_ready)
    assert app.build_detection_status == "Build settings ready"
    _, kwargs = mock_status.update_status.call_args
    assert kwargs.get("build_status") == "Build settings ready"

    # 3. Test "Unsupported"
    event_unsupported = {
        "type": "NOTIFICATION",
        "data": {
            "level": "INFO",
            "message": "Could not determine build configuration - project structure may be unsupported or incomplete",
        },
    }
    app._handle_event(event_unsupported)
    assert app.build_detection_status == "Build settings not detected"
    _, kwargs = mock_status.update_status.call_args
    assert kwargs.get("build_status") == "Build settings not detected"

    # 4. Test normal notification doesn't change build status
    old_status = app.build_detection_status
    event_normal = {
        "type": "NOTIFICATION",
        "data": {"level": "INFO", "message": "A normal message"},
    }
    app._handle_event(event_normal)
    assert app.build_detection_status == old_status
    mock_chat.add_system_message.assert_called_with("A normal message", level="INFO")
