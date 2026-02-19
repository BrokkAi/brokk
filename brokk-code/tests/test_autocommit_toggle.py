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


@pytest.mark.asyncio
async def test_run_job_attaches_at_mentions_to_context(tmp_path):
    executor = MagicMock()
    executor.workspace_dir = tmp_path
    executor.submit_job = AsyncMock(return_value="job-1")
    executor.get_completions = AsyncMock(
        side_effect=[
            {
                "completions": [
                    {
                        "type": "file",
                        "name": "app.py",
                        "detail": "brokk_code/app.py",
                    }
                ]
            },
            {
                "completions": [
                    {
                        "type": "class",
                        "name": "ContextManager",
                        "detail": "ai.brokk.ContextManager",
                    }
                ]
            },
            {
                "completions": [
                    {
                        "type": "function",
                        "name": "findUser",
                        "detail": "com.example.UserService.findUser",
                    }
                ]
            },
        ]
    )
    executor.add_context_files = AsyncMock(return_value={"added": []})
    executor.add_context_classes = AsyncMock(return_value={"added": []})
    executor.add_context_methods = AsyncMock(return_value={"added": []})

    async def stream_events(_job_id: str):
        if False:  # pragma: no cover
            yield {}

    executor.stream_events = stream_events

    app = BrokkApp(executor=executor)
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    await app._run_job(
        "Use @brokk_code/app.py and @ai.brokk.ContextManager and @com.example.UserService.findUser"
    )

    executor.add_context_files.assert_awaited_once_with(["brokk_code/app.py"])
    executor.add_context_classes.assert_awaited_once_with(["ai.brokk.ContextManager"])
    executor.add_context_methods.assert_awaited_once_with(["com.example.UserService.findUser"])
    executor.submit_job.assert_awaited_once()


@pytest.mark.asyncio
async def test_run_job_skips_non_matching_at_mentions(tmp_path):
    executor = MagicMock()
    executor.workspace_dir = tmp_path
    executor.submit_job = AsyncMock(return_value="job-1")
    executor.get_completions = AsyncMock(
        return_value={
            "completions": [
                {
                    "type": "class",
                    "name": "ContextManager",
                    "detail": "ai.brokk.ContextManager",
                }
            ]
        }
    )
    executor.add_context_files = AsyncMock(return_value={"added": []})
    executor.add_context_classes = AsyncMock(return_value={"added": []})
    executor.add_context_methods = AsyncMock(return_value={"added": []})

    async def stream_events(_job_id: str):
        if False:  # pragma: no cover
            yield {}

    executor.stream_events = stream_events

    app = BrokkApp(executor=executor)
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    await app._run_job("Use @DoesNotMatch")

    executor.add_context_files.assert_not_awaited()
    executor.add_context_classes.assert_not_awaited()
    executor.add_context_methods.assert_not_awaited()

    mock_chat.add_system_message.assert_any_call("No exact match for @DoesNotMatch")
    executor.submit_job.assert_awaited_once()


@pytest.mark.asyncio
async def test_run_job_rolls_back_attached_mention_fragments_on_submit_failure(tmp_path):
    executor = MagicMock()
    executor.workspace_dir = tmp_path
    executor.get_completions = AsyncMock(
        return_value={
            "completions": [
                {
                    "type": "file",
                    "name": "app.py",
                    "detail": "brokk_code/app.py",
                }
            ]
        }
    )
    executor.add_context_files = AsyncMock(return_value={"added": [{"id": "frag-1"}]})
    executor.drop_context_fragments = AsyncMock(return_value={"dropped": ["frag-1"]})
    executor.submit_job = AsyncMock(side_effect=RuntimeError("submit failed"))

    app = BrokkApp(executor=executor)
    mock_chat = MagicMock(spec=ChatPanel)
    app.query_one = MagicMock(return_value=mock_chat)

    await app._run_job("Use @brokk_code/app.py")

    executor.add_context_files.assert_awaited_once_with(["brokk_code/app.py"])
    executor.drop_context_fragments.assert_awaited_once_with(["frag-1"])
    assert executor.submit_job.await_count == 1
