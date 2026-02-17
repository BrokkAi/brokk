import asyncio
import time

import pytest
from textual.containers import Horizontal
from textual.widgets import Static

from brokk_code.widgets.chat_panel import ChatPanel


@pytest.mark.asyncio
async def test_spinner_and_timer_lifecycle():
    """
    Verify that the spinner and elapsed timer visibility and values are controlled
    explicitly and respond to simulated time.
    """
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        spinner_area = panel.query_one("#chat-spinner-area", Horizontal)
        timer_label = panel.query_one("#chat-timer", Static)

        # Setup deterministic clock
        current_time = 1000.0

        def mock_now():
            return current_time

        panel._get_now = mock_now

        # Helper to wait for timer label to reach expected state
        async def wait_for_timer(expected: str, timeout: float = 1.0):
            start = time.time()
            while time.time() - start < timeout:
                if str(timer_label.render()) == expected:
                    return
                await pilot.pause()
                await asyncio.sleep(0.01)
            assert str(timer_label.render()) == expected

        # Initial state: hidden, no timer text
        await pilot.pause()
        assert spinner_area.has_class("hidden")
        assert str(timer_label.render()) == ""

        # Start job
        panel.set_job_running(True)
        await pilot.pause()
        assert not spinner_area.has_class("hidden")

        # Wait for the update worker to run once
        await wait_for_timer("Elapsed: 00:00")

        # Advance time by 65 seconds
        current_time += 65.0
        # Give the Textual interval a moment to fire and process the mock time update
        await wait_for_timer("Elapsed: 01:05")

        # Advance time to cross 1 hour (3600s + 65s = 3665s)
        current_time += 3600.0
        await wait_for_timer("Elapsed: 01:01:05")

        # Verify timer continues even with NO tokens arriving
        current_time += 10.0
        await wait_for_timer("Elapsed: 01:01:15")

        # Append tokens - spinner should STAY visible
        panel.append_token(
            "Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False
        )
        assert "hidden" not in spinner_area.classes

        panel.append_token(
            " world", "AI", is_new_message=False, is_reasoning=False, is_terminal=True
        )
        assert "hidden" not in spinner_area.classes

        # Verify set_response_finished does NOT hide spinner or stop timer
        panel.set_response_finished()
        assert "hidden" not in spinner_area.classes

        current_time += 5.0
        await wait_for_timer("Elapsed: 01:01:20")

        # Explicit job finish - hides area and clears timer
        panel.set_job_running(False)
        await pilot.pause()
        assert spinner_area.has_class("hidden")
        # Wait for worker to exit and check final state
        await asyncio.sleep(0.1)
        assert str(timer_label.render()) == ""


@pytest.mark.asyncio
async def test_token_usage_update():
    """Verify that updating token usage updates the widget text."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        usage_label = panel.query_one("#chat-token-usage", Static)

        # Initial empty
        assert str(usage_label.render()) == ""

        # Update with used and max
        panel.set_token_usage(1500, 100000)
        # 1,500 / 100,000 is 1.5%. In a 20-char bar, that's 0 blocks filled.
        # bar_width = 20, ratio = 0.015, filled_len = 0
        expected_bar = "░" * 20
        assert str(usage_label.render()) == f"[{expected_bar}] 1,500 / 100,000"

        # Update with half usage
        panel.set_token_usage(50000, 100000)
        expected_half_bar = "█" * 10 + "░" * 10
        assert str(usage_label.render()) == f"[{expected_half_bar}] 50,000 / 100,000"

        # Update with only used
        panel.set_token_usage(2500)
        assert str(usage_label.render()) == "Tokens: 2,500"

        # Update with 0 clears it
        panel.set_token_usage(0)
        assert str(usage_label.render()) == ""


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
        usage_label = panel.query_one("#chat-token-usage", Static)

        # Initial state: hidden
        assert usage_label.has_class("hidden")

        # Set visible
        panel.set_token_bar_visible(True)
        assert not usage_label.has_class("hidden")

        # Set hidden
        panel.set_token_bar_visible(False)
        assert usage_label.has_class("hidden")


def test_selectable_rich_log_extracts_selection_text():
    """SelectableRichLog should provide text extraction for Textual selection."""
    from textual.geometry import Offset
    from textual.selection import Selection

    from brokk_code.widgets.chat_panel import SelectableRichLog

    log = SelectableRichLog()
    log.lines = ["alpha", "beta"]

    extracted = log.get_selection(Selection.from_offsets(Offset(0, 0), Offset(5, 0)))

    assert extracted is not None
    assert extracted[0] == "alpha"


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
async def test_chat_input_focus_does_not_block_ctrl_u_model_select():
    """
    Verify model selection action still works when ChatInput has focus.
    """
    from unittest.mock import AsyncMock, MagicMock, patch

    from brokk_code.app import BrokkApp

    # Setup app with ready executor to avoid early return in action_select_model
    executor = MagicMock()
    executor.get_models = AsyncMock(return_value={"models": ["model1"]})
    executor.stop = AsyncMock()
    executor.session_id = None
    app = BrokkApp(executor=executor)
    app._executor_ready = True

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            chat_input = app.query_one("#chat-input")
            assert chat_input.has_focus

            # We mock push_screen to see if the action was triggered
            app.push_screen = MagicMock()

            # Trigger model selection action while input remains focused
            await app.action_select_model()
            await pilot.pause()

            # Verify push_screen was called, indicating the action triggered
            assert app.push_screen.called


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
