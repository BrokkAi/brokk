from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from textual.widgets import RichLog

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


@pytest.mark.asyncio
async def test_switch_to_worktree_already_current():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value

    target = Path("/repo/main")
    # Resolve uses real filesystem usually, so we mock it to ensure equality in test
    with patch.object(Path, "resolve", return_value=Path("/repo/main")):
        await app._switch_to_worktree(target)

    mock_chat.add_system_message.assert_called_with(f"Already in worktree: {target}")


@pytest.mark.asyncio
async def test_switch_to_worktree_lazy_start():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    app._refresh_context_panel = AsyncMock()
    app._replay_conversation_entries = MagicMock()

    new_path = Path("/repo/feat1")
    resolved_new = Path("/repo/feat1")
    resolved_old = Path("/repo/main")

    mock_new_exec = MagicMock(spec=ExecutorManager)
    mock_new_exec.workspace_dir = new_path
    mock_new_exec.start = AsyncMock()
    mock_new_exec.create_session = AsyncMock()
    mock_new_exec.wait_ready = AsyncMock(return_value=True)
    mock_new_exec.get_conversation = AsyncMock(return_value={"entries": []})

    with patch.object(Path, "resolve", side_effect=[resolved_new, resolved_old, resolved_new]):
        with patch.object(app, "_make_executor", return_value=mock_new_exec):
            await app._switch_to_worktree(new_path)

            assert app.executor == mock_new_exec
            mock_new_exec.start.assert_called_once()
            assert app._worktree_executors[resolved_new] == mock_new_exec


@pytest.mark.asyncio
async def test_remove_worktree_refuses_current():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value

    path = Path("/repo/main")
    with patch.object(Path, "resolve", return_value=path):
        await app._remove_worktree(path)

    mock_chat.add_system_message.assert_called_with(
        "Cannot remove the active worktree.", level="ERROR"
    )


@pytest.mark.asyncio
async def test_shutdown_stops_all_executors():
    app = BrokkApp(workspace_dir=Path("/repo/main"))

    mock_exec1 = app.executor
    mock_exec1.stop = AsyncMock()

    mock_exec2 = MagicMock(spec=ExecutorManager)
    mock_exec2.workspace_dir = Path("/repo/feat1")
    mock_exec2.stop = AsyncMock()

    app._worktree_executors[Path("/repo/feat1").resolve()] = mock_exec2

    await app._shutdown_once(show_message=False)

    mock_exec1.stop.assert_called()
    mock_exec2.stop.assert_called()


@pytest.mark.asyncio
async def test_switch_to_worktree_cleanup_on_failure():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value
    app._refresh_context_panel = AsyncMock()

    target = Path("/repo/feat-fail")
    resolved_target = Path("/repo/feat-fail")
    resolved_main = Path("/repo/main")

    # Mock resolved path for the target vs main
    with patch.object(
        Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
    ):
        # 1. First attempt: Fail during startup
        mock_fail_exec = MagicMock(spec=ExecutorManager)
        mock_fail_exec.start = AsyncMock(side_effect=Exception("Startup failed"))

        with patch.object(app, "_make_executor", return_value=mock_fail_exec) as mock_make:
            with patch.object(
                Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
            ):
                await app._switch_to_worktree(target)

            # Assert error message was shown
            args, kwargs = mock_chat.add_system_message.call_args
            assert "Startup failed" in args[0]
            assert kwargs.get("level") == "ERROR"

            # Assert it was NOT added to the pool
            assert resolved_target not in app._worktree_executors
            assert mock_make.call_count == 1

        # 2. Second attempt: Success (Retry)
        mock_chat.add_system_message.reset_mock()
        mock_success_exec = MagicMock(spec=ExecutorManager)
        mock_success_exec.workspace_dir = target
        mock_success_exec.start = AsyncMock()
        mock_success_exec.create_session = AsyncMock()
        mock_success_exec.wait_ready = AsyncMock(return_value=True)
        mock_success_exec.get_conversation = AsyncMock(return_value={"entries": []})

        with patch.object(app, "_make_executor", return_value=mock_success_exec) as mock_make:
            await app._switch_to_worktree(target)

            # Assert it IS now in the pool and was swapped
            assert mock_make.call_count == 1
            assert app.executor == mock_success_exec
            assert app._worktree_executors[resolved_target] == mock_success_exec


@pytest.mark.asyncio
async def test_rich_log_cleared_on_switch():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._refresh_context_panel = AsyncMock()
    mock_chat = MagicMock()
    app._maybe_chat = MagicMock(return_value=mock_chat)

    mock_log = MagicMock(spec=RichLog)
    mock_chat.query_one.return_value = mock_log

    # Setup target worktree
    target = Path("/repo/other")
    resolved_target = Path("/repo/other")
    resolved_main = Path("/repo/main")

    mock_exec = MagicMock(spec=ExecutorManager)
    mock_exec.workspace_dir = target
    mock_exec.start = AsyncMock()
    mock_exec.create_session = AsyncMock()
    mock_exec.wait_ready = AsyncMock(return_value=True)
    mock_exec.get_conversation = AsyncMock(return_value={"entries": []})

    with patch.object(
        Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
    ):
        with patch.object(app, "_make_executor", return_value=mock_exec):
            await app._switch_to_worktree(target)

    # Verify log was cleared
    mock_chat.query_one.assert_any_call("#chat-log", RichLog)
    mock_log.clear.assert_called_once()


@pytest.mark.asyncio
async def test_switch_to_worktree_failure_not_cached():
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._maybe_chat = MagicMock()
    mock_chat = app._maybe_chat.return_value
    app._refresh_context_panel = AsyncMock()

    target = Path("/repo/fail")
    resolved_target = Path("/repo/fail")
    resolved_main = Path("/repo/main")

    # 1. First attempt: Fail during startup
    mock_fail_exec = MagicMock(spec=ExecutorManager)
    mock_fail_exec.start = AsyncMock(side_effect=Exception("Crash on start"))

    with patch.object(
        Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
    ):
        with patch.object(app, "_make_executor", return_value=mock_fail_exec) as mock_make:
            await app._switch_to_worktree(target)

            # Assert error message shown
            mock_chat.add_system_message.assert_called_with(
                "Failed to switch worktree: Crash on start", level="ERROR"
            )

            # Assert NOT in pool
            assert resolved_target not in app._worktree_executors
            assert mock_make.call_count == 1

    # 2. Second attempt: Success
    mock_chat.add_system_message.reset_mock()
    mock_success_exec = MagicMock(spec=ExecutorManager)
    mock_success_exec.workspace_dir = target
    mock_success_exec.start = AsyncMock()
    mock_success_exec.create_session = AsyncMock()
    mock_success_exec.wait_ready = AsyncMock(return_value=True)
    mock_success_exec.get_conversation = AsyncMock(return_value={"entries": []})

    with patch.object(
        Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
    ):
        with patch.object(app, "_make_executor", return_value=mock_success_exec) as mock_make:
            await app._switch_to_worktree(target)

            # Assert it was made AGAIN (not pulled from cache)
            assert mock_make.call_count == 1
            assert app.executor == mock_success_exec
            assert app._worktree_executors[resolved_target] == mock_success_exec


@pytest.mark.asyncio
async def test_switch_to_worktree_clears_ui_before_replay():
    """
    Integration test ensuring _switch_to_worktree clears existing chat content
    before fetching and replaying the new worktree's conversation.
    """
    app = BrokkApp(workspace_dir=Path("/repo/main"))
    app._refresh_context_panel = AsyncMock()
    app._refresh_worktree_name = MagicMock()

    # Target worktree setup
    target_path = Path("/repo/new_feat")
    resolved_target = Path("/repo/new_feat")
    resolved_main = Path("/repo/main")

    mock_new_exec = MagicMock(spec=ExecutorManager)
    mock_new_exec.workspace_dir = target_path
    mock_new_exec.start = AsyncMock()
    mock_new_exec.create_session = AsyncMock()
    mock_new_exec.wait_ready = AsyncMock(return_value=True)

    # Prepare a conversation to replay so we can see it's not empty at the end
    mock_new_exec.get_conversation = AsyncMock(
        return_value={"entries": [{"messages": [{"role": "ai", "text": "new content"}]}]}
    )

    async with app.run_test():
        chat = app._maybe_chat()
        assert chat is not None
        chat.query_one("#chat-log", RichLog)

        # 1. Populate "old" content
        chat._message_history.clear()  # Clear initial welcome/system messages
        chat.add_markdown("old content")
        # ChatPanel.add_markdown adds to _message_history, no need to append manually
        assert len(chat._message_history) == 1

        # 2. We want to assert that things are cleared DURING the switch.
        # We'll wrap _replay_conversation_entries to check the state before it runs.
        original_replay = app._replay_conversation_entries
        cleared_before_replay = False

        def wrapped_replay(data):
            nonlocal cleared_before_replay
            # Check that history is empty and log has been cleared
            # (RichLog.clear() is async-queued in Textual, but _message_history is immediate)
            if len(chat._message_history) == 0:
                cleared_before_replay = True
            return original_replay(data)

        app._replay_conversation_entries = MagicMock(side_effect=wrapped_replay)

        # 3. Execute the switch
        with patch.object(
            Path, "resolve", side_effect=[resolved_target, resolved_main, resolved_target]
        ):
            with patch.object(app, "_make_executor", return_value=mock_new_exec):
                await app._switch_to_worktree(target_path)

        # 4. Final assertions
        assert cleared_before_replay, (
            "Message history should be cleared before replaying new entries"
        )
        # 2 messages: "new content" and then "Switched to worktree: ..."
        assert len(chat._message_history) == 2
        assert chat._message_history[0]["content"] == "new content"
        assert "Switched to worktree" in chat._message_history[1]["content"]
