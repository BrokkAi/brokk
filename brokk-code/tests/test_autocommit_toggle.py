from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.settings import Settings
from brokk_code.widgets.chat_panel import ChatPanel


def test_handle_command_autocommit_shows_status(tmp_path):
    app = BrokkApp(executor=MagicMock(workspace_dir=tmp_path))
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.auto_commit = True
    app._handle_command("/autocommit")

    args, kwargs = mock_chat.add_system_message_markup.call_args
    assert "Auto-commit mode" in args[0]
    assert "ON" in args[0]
    assert kwargs.get("level") == "WARNING"


def test_handle_command_autocommit_off_persists_and_announces(tmp_path):
    app = BrokkApp(executor=MagicMock(workspace_dir=tmp_path))
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.settings = Settings()
    app.settings.save = MagicMock()

    app.auto_commit = True
    app._handle_command("/autocommit off")

    assert app.auto_commit is False
    assert app.settings.last_auto_commit is False
    app.settings.save.assert_called_once()

    args, kwargs = mock_chat.add_system_message_markup.call_args
    assert "Auto-commit mode" in args[0]
    assert "OFF" in args[0]
    assert kwargs.get("level") == "WARNING"


@pytest.mark.asyncio
async def test_run_job_passes_auto_commit_flag(tmp_path):
    executor = MagicMock()
    executor.workspace_dir = tmp_path
    executor.submit_job = AsyncMock(return_value="job-1")

    async def stream_events(_job_id: str):
        if False:  # pragma: no cover
            yield {}

    executor.stream_events = stream_events

    app = BrokkApp(executor=executor)
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    app.auto_commit = False
    await app._run_job("hello")

    assert executor.submit_job.await_count == 1
    assert executor.submit_job.await_args.kwargs["auto_commit"] is False
