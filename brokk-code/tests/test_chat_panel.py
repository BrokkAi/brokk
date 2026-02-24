from unittest.mock import AsyncMock, MagicMock

import pytest
from rich.text import Text
from textual.widgets import Static

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
        assert "98.5% context remaining" in str(token_bar.render())

        # Update with half usage
        panel.set_token_usage(50000, 100000)
        assert "50.0% context remaining" in str(token_bar.render())

        # Update with only used
        panel.set_token_usage(2500)
        # TokenBar defaults max to 200,000 if not provided
        # 1 - (2500/200000) = 0.9875 -> 98.8%
        assert "98.8% context remaining" in str(token_bar.render())

        # Update with 0 clears it
        panel.set_token_usage(0)
        assert "No context yet" in str(token_bar.render())


@pytest.mark.asyncio
async def test_job_progress_in_chat_panel():
    """
    Verify that job running state is reflected in ChatPanel's status timer
    and the help row spinner.
    """
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        chat = app.query_one(ChatPanel)
        help_elapsed = chat.query_one("#help-elapsed")
        help_spinner = chat.query_one("#help-spinner")

        # Initially hidden
        assert help_elapsed.has_class("hidden")
        assert help_spinner.has_class("hidden")

        # Start job
        chat.set_job_running(True)
        assert not help_elapsed.has_class("hidden")
        assert not help_spinner.has_class("hidden")

        # Stop job
        chat.set_job_running(False)
        assert help_elapsed.has_class("hidden")
        assert help_spinner.has_class("hidden")


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
async def test_no_notification_panel_widget():
    """Verify that ChatPanel does not contain a notification panel widget."""
    from textual.app import App, ComposeResult
    from textual.css.query import NoMatches

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        chat = app.query_one("#chat", ChatPanel)
        # It should not have a widget with id notification-panel
        with pytest.raises(NoMatches):
            chat.query_one("#notification-panel")


@pytest.mark.asyncio
async def test_notification_routing_to_chat_log():
    """Verify that notifications are routed to the main chat log with styling."""
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)

        # Test INFO notification (no prefix)
        panel.add_system_message("Info message", level="INFO")
        await pilot.pause()
        # Ensure 'Info message' is present but NOT explicitly prefixed with '[INFO]'
        assert any(
            "Info message" in str(line) and not str(line).startswith("[INFO]") for line in log.lines
        )

        # Test ERROR notification (prefixed)
        panel.add_system_message("Error message", level="ERROR")
        await pilot.pause()
        assert any("[ERROR] Error message" in str(line) for line in log.lines)

        # Test COST notification (prefixed)
        panel.add_system_message("Cost message", level="COST")
        await pilot.pause()
        assert any("[COST] Cost message" in str(line) for line in log.lines)


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


def _static_rendered_text(widget: Static) -> str:
    rendered = widget.render()
    if isinstance(rendered, Text):
        return rendered.plain
    return str(rendered)


@pytest.mark.asyncio
async def test_chat_panel_composition_success():
    """
    Verify that ChatPanel composes without NameError and contains expected widgets.
    Regression test for missing Static import.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import Static

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        # Verify ChatPanel exists
        chat = app.query_one("#chat", ChatPanel)
        assert chat is not None

        # Verify #help-elapsed (Static) exists - this would have failed with NameError
        help_elapsed = chat.query_one("#help-elapsed", Static)
        assert help_elapsed is not None


@pytest.mark.asyncio
async def test_chat_help_line_includes_shift_tab_mode_after_history() -> None:
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        chat = app.query_one("#chat", ChatPanel)
        help_line = chat.query_one("#chat-help", Static)
        rendered = _static_rendered_text(help_line)

        assert "Up/Down: History" in rendered
        assert "Shift+Tab: Mode" in rendered
        assert "/commands" not in rendered
        assert rendered.index("Up/Down: History") < rendered.index("Shift+Tab: Mode")


@pytest.mark.asyncio
async def test_slash_command_catalog_stability():
    """Verify the slash command catalog is stable and follows rules."""
    from unittest.mock import MagicMock

    from brokk_code.app import BrokkApp

    app = BrokkApp(executor=MagicMock())
    commands = app.get_slash_commands()

    assert len(commands) > 0
    seen = set()
    for cmd_entry in commands:
        cmd = cmd_entry["command"]
        assert cmd.startswith("/"), f"Command {cmd} must start with /"
        assert cmd not in seen, f"Duplicate command found: {cmd}"
        assert "description" in cmd_entry
        seen.add(cmd)

    # Verify key commands exist
    cmds_only = {c["command"] for c in commands}
    assert "/ask" in cmds_only
    assert "/task" in cmds_only
    assert "/help" in cmds_only


@pytest.mark.asyncio
async def test_slash_autocomplete_filtering():
    """Verify slash suggestions filter correctly."""
    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import ChatPanel, SlashCommandSuggestions

    class TestApp(App):
        def get_slash_commands(self):
            return [
                {"command": "/ask", "description": "d"},
                {"command": "/ask-more", "description": "d"},
                {"command": "/help", "description": "d"},
            ]

        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        # Type /a
        await pilot.press(*list("/a"))
        assert suggestions.display is True
        assert len(suggestions.children) == 2

        # Type sk-
        await pilot.press(*list("sk-"))
        assert len(suggestions.children) == 1
        assert "/ask-more" in str(suggestions.children[0].query_one(Static).render())

        # Esc hides
        await pilot.press("escape")
        assert suggestions.display is False


@pytest.mark.asyncio
async def test_mention_autocomplete_fetch_and_accept():
    """Verify @mention autocomplete fetches remote completions and inserts selection."""
    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import ChatInput, MentionSuggestions

    class TestApp(App):
        def __init__(self) -> None:
            super().__init__()
            self.executor = MagicMock()
            self.executor.get_completions = AsyncMock(
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

        def get_slash_commands(self):
            return []

        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test() as pilot:
        mentions = app.query_one(MentionSuggestions)
        chat_input = app.query_one("#chat-input", ChatInput)

        await pilot.press("@", "C", "o", "n")
        await pilot.pause(0.25)

        assert mentions.display is True
        assert len(mentions.children) == 1
        app.executor.get_completions.assert_awaited()

        await pilot.press("enter")
        await pilot.pause()
        assert mentions.display is False
        assert chat_input.text == "@ai.brokk.ContextManager "


@pytest.mark.asyncio
async def test_mention_autocomplete_ignores_email_like_text():
    """Verify email-like text does not trigger @mention autocomplete."""
    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import MentionSuggestions

    class TestApp(App):
        def __init__(self) -> None:
            super().__init__()
            self.executor = MagicMock()
            self.executor.get_completions = AsyncMock(return_value={"completions": []})

        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test() as pilot:
        mentions = app.query_one(MentionSuggestions)
        await pilot.press(*list("foo@bar"))
        await pilot.pause(0.25)

        assert mentions.display is False
        app.executor.get_completions.assert_not_awaited()


@pytest.mark.asyncio
async def test_chat_panel_history_and_filtering():
    """Verify that message history is recorded and filtered correctly by refresh_log."""
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)

        # 1. Add various message types
        panel.add_user_message("Hello Brokk")
        panel.add_system_message("System online", level="INFO")
        panel.add_tool_result_message("Executed: ls -la")
        
        # Simulate LLM stream with reasoning and AI content
        panel.append_token("Thinking...", "AI", is_new_message=True, is_reasoning=True, is_terminal=True)
        panel.append_token("Hello!", "AI", is_new_message=True, is_reasoning=False, is_terminal=True)
        await pilot.pause()

        # Check internal history recording
        history = panel._message_history
        kinds = [m["kind"] for m in history]
        assert "USER" in kinds
        assert "SYSTEM" in kinds
        assert "TOOL_RESULT" in kinds
        assert "REASONING" in kinds
        assert "AI" in kinds

        # 2. Test refresh_log(show_verbose=True)
        panel.refresh_log(show_verbose=True)
        await pilot.pause()
        content_verbose = "".join(str(line) for line in log.lines)
        assert "Hello Brokk" in content_verbose
        assert "System online" in content_verbose
        assert "Executed: ls -la" in content_verbose
        assert "Thinking..." in content_verbose
        assert "Hello!" in content_verbose

        # 3. Test refresh_log(show_verbose=False)
        panel.refresh_log(show_verbose=False)
        await pilot.pause()
        content_filtered = "".join(str(line) for line in log.lines)
        assert "Hello Brokk" in content_filtered
        assert "System online" in content_filtered
        assert "Hello!" in content_filtered
        
        # Verbose items should be hidden
        assert "Thinking..." not in content_filtered
        assert "Executed: ls -la" not in content_filtered


@pytest.mark.asyncio
async def test_chat_panel_refresh_log_empty():
    """Verify refresh_log doesn't crash with empty history."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test():
        panel = app.query_one(ChatPanel)
        # Should not raise
        panel.refresh_log(show_verbose=True)
        panel.refresh_log(show_verbose=False)


@pytest.mark.asyncio
async def test_app_toggle_output_integration():
    """Integration test for BrokkApp.action_toggle_output."""
    from unittest.mock import MagicMock, patch
    from brokk_code.app import BrokkApp

    executor = MagicMock()
    app = BrokkApp(executor=executor)

    with (
        patch.object(BrokkApp, "_start_executor", return_value=None),
        patch.object(BrokkApp, "_monitor_executor", return_value=None),
        patch.object(BrokkApp, "_poll_tasklist", return_value=None),
        patch.object(BrokkApp, "_poll_context", return_value=None),
    ):
        async with app.run_test() as pilot:
            chat = app.query_one(ChatPanel)
            
            # Initial state
            assert app.show_verbose_output is True
            
            # Toggle OFF
            await pilot.press("ctrl+o")
            assert app.show_verbose_output is False
            assert chat._show_verbose is False
            
            # Toggle ON
            await pilot.press("ctrl+o")
            assert app.show_verbose_output is True
            assert chat._show_verbose is True
