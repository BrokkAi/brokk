import pytest

from brokk_code.widgets.chat_panel import ChatPanel


@pytest.mark.asyncio
async def test_token_usage_update():
    """Verify that updating token usage updates the widget text."""
    from textual.app import App, ComposeResult

    from brokk_code.widgets.token_bar import TokenBar

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        token_bar = panel.query_one("#chat-token-bar", TokenBar)

        # Initial empty - should show no context message
        assert "No context yet" in str(token_bar.render())

        # Update with used and max
        panel.set_token_usage(1500, 100000)
        assert "1.5k / 100k tokens" in str(token_bar.render())

        # Update with half usage
        panel.set_token_usage(50000, 100000)
        assert "50k / 100k tokens" in str(token_bar.render())

        # Update with only used
        panel.set_token_usage(2500)
        # TokenBar defaults max to 200,000 if not provided
        assert "2.5k / 200k tokens" in str(token_bar.render())

        # Update with 0 clears it
        panel.set_token_usage(0)
        assert "No context yet" in str(token_bar.render())


@pytest.mark.asyncio
async def test_job_progress_in_chat_panel():
    """
    Verify that job running state is reflected in ChatPanel's progress widget.
    """
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        chat = app.query_one(ChatPanel)
        progress = chat.query_one("#status-progress")

        # Initially hidden
        assert progress.has_class("hidden")

        # Start job
        chat.set_job_running(True)
        assert not progress.has_class("hidden")

        # Stop job
        chat.set_job_running(False)
        assert progress.has_class("hidden")


@pytest.mark.asyncio
async def test_token_bar_visibility_control():
    """Verify that the token bar visibility can be controlled explicitly."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        token_bar = panel.query_one("#chat-token-bar")

        # Initial state: hidden
        assert token_bar.has_class("hidden")

        # Set visible
        panel.set_token_bar_visible(True)
        assert not token_bar.has_class("hidden")

        # Set hidden
        panel.set_token_bar_visible(False)
        assert token_bar.has_class("hidden")


@pytest.mark.asyncio
async def test_streaming_duplication_regression():
    """
    Verify that streaming tokens incrementally does not result in duplicated
    content in the RichLog.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)

        # Simulate a stream: "Hello" -> "Hello world" -> "Hello world!"
        # We use a short flush interval to ensure incremental flushes trigger.
        panel._flush_interval = 0.01

        panel.append_token(
            "Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False
        )
        await pilot.pause()

        panel.append_token(
            " world", "AI", is_new_message=False, is_reasoning=False, is_terminal=False
        )
        await pilot.pause()

        panel.append_token("!", "AI", is_new_message=False, is_reasoning=False, is_terminal=True)
        await pilot.pause()

        # Check the rendered lines in the log.
        # We expect "Hello world!" to appear exactly once.
        # It should not appear as:
        # Hello
        # Hello world
        # Hello world!

        # log.lines contains the objects passed to write()
        content_strings = [str(line) for line in log.lines]
        combined_text = "".join(content_strings)

        # Count occurrences of the final string
        count = combined_text.count("Hello world!")
        assert count == 1, (
            f"Expected 'Hello world!' once, found {count}. Content: {content_strings}"
        )


@pytest.mark.asyncio
async def test_whitespace_reasoning_terminal_does_not_stick():
    """
    Regression test: If a reasoning token that is only whitespace is flushed as terminal,
    the panel must not remain in reasoning mode for the next non-reasoning message.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)

        # 1) Simulate a reasoning stream that only emits whitespace and is terminal.
        panel.append_token("   ", "AI", is_new_message=True, is_reasoning=True, is_terminal=True)
        await pilot.pause()

        # Ensure nothing meaningful was rendered as a Thinking panel
        combined = "".join(str(line) for line in log.lines)
        assert "Thinking" not in combined

        # 2) Now append a normal non-reasoning message and ensure it renders as Markdown
        panel.append_token("Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=True)
        await pilot.pause()

        combined = "".join(str(line) for line in log.lines)
        # Should contain the Markdown-rendered Hello, and still not contain a Thinking panel.
        assert "Hello" in combined
        assert "Thinking" not in combined


@pytest.mark.asyncio
async def test_action_handle_ctrl_c_no_input_widget():
    """
    Regression test: Verify action_handle_ctrl_c does not crash if _maybe_chat()
    returns a panel but the #chat-input widget is not currently in the DOM.
    """
    from unittest.mock import AsyncMock, MagicMock, patch

    from brokk_code.app import BrokkApp

    executor = MagicMock()
    executor.stop = AsyncMock()
    app = BrokkApp(executor=executor)

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            # Simulate a job in progress so we can verify the fall-through behavior
            app.job_in_progress = True
            app.current_job_id = "test-job-id"
            app.executor.cancel_job = AsyncMock()

            # Mock query to return empty when looking for #chat-input
            # This simulates the widget being unmounted/missing
            original_query = app.query

            def mocked_query(selector: str):
                if selector == "#chat-input":
                    empty_result = MagicMock()
                    empty_result.results.return_value = iter([])
                    return empty_result
                return original_query(selector)

            with patch.object(app, "query", side_effect=mocked_query):
                # This should NOT raise even though #chat-input is 'missing'
                await app.action_handle_ctrl_c()
                await pilot.pause()

            # Verify it fell through to job cancellation
            app.executor.cancel_job.assert_called_once_with("test-job-id")


@pytest.mark.asyncio
async def test_no_ctrl_u_e_bindings_in_chat_input():
    """Verify that ChatInput does not have ctrl+u or ctrl+e bindings."""
    from brokk_code.widgets.chat_panel import ChatInput
    bindings = {b.key for b in ChatInput.BINDINGS}
    assert "ctrl+u" not in bindings
    assert "ctrl+e" not in bindings
