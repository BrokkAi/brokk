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

        assert "↑/↓: History" in rendered
        assert "Shift+Tab: Mode" in rendered
        assert "/commands" not in rendered
        assert rendered.index("↑/↓: History") < rendered.index("Shift+Tab: Mode")


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
    """Verify that ChatPanel records history and filters correctly during refresh_log."""
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        log = chat.query_one("#chat-log", RichLog)

        # 1. Add various message types
        chat.add_user_message("Hello User")
        chat.append_token(
            "Thinking hard", "AI", is_new_message=True, is_reasoning=True, is_terminal=True
        )
        chat.append_token(
            "Hello from AI", "AI", is_new_message=True, is_reasoning=False, is_terminal=True
        )
        chat.add_tool_result("Command success")
        chat.add_system_message("System info")
        chat.add_welcome("ICON", "Welcome body")

        # Verify history structure
        assert len(chat._message_history) == 6
        kinds = [m["kind"] for m in chat._message_history]
        assert kinds == ["USER", "REASONING", "AI", "TOOL_RESULT", "SYSTEM", "WELCOME"]

        # 2. Refresh log with verbose=True (show all)
        chat.refresh_log(show_verbose=True)
        await pilot.pause()

        def get_plain_text(line):
            if isinstance(line, Text):
                return line.plain
            if hasattr(line, "plain"):
                return line.plain
            # For Strip and other objects, try to extract plain text
            text_str = str(line)
            # If it looks like a repr of Strip/Segment, try to extract content
            if "Segment(" in text_str:
                import re

                # Match Segment('content', ...) or Segment('content')
                matches = re.findall(r"Segment\('([^'\\]*(?:\\.[^'\\]*)*)'", text_str)
                if matches:
                    return "".join(matches)
            return text_str

        content = "".join(get_plain_text(line) for line in log.lines)
        assert "Hello User" in content
        assert "Thinking [-] (ctrl+o to collapse)" in content  # Reasoning panel title
        assert "Thinking hard" in content
        assert "Hello from AI" in content
        assert "Command Output [-] (ctrl+o to collapse)" in content  # Tool result panel title
        assert "Command success" in content
        assert "System info" in content

        # 3. Refresh log with verbose=False (collapse REASONING and hide TOOL_RESULT)
        chat.refresh_log(show_verbose=False)
        await pilot.pause()

        content_filtered = "".join(get_plain_text(line) for line in log.lines)
        assert "Hello User" in content_filtered
        assert "Hello from AI" in content_filtered
        assert "System info" in content_filtered

        # REASONING should show a compact collapsed summary, not a full panel body.
        assert "Thinking [+] (ctrl+o to expand)" in content_filtered
        assert "Thinking hard" in content_filtered

        # TOOL_RESULT should show compact single-line summary in collapsed mode.
        assert "Command Output [+] (ctrl+o to expand)" in content_filtered
        assert "Command success" in content_filtered


@pytest.mark.asyncio
async def test_brokk_app_command_result_handling_and_filtering():
    """Verify that BrokkApp correctly routes COMMAND_RESULT events and they obey verbosity."""
    from textual.widgets import RichLog

    from brokk_code.app import BrokkApp

    executor = MagicMock()
    app = BrokkApp(executor=executor)

    async def _noop() -> None:
        return None

    # Avoid starting background workers during this integration test.
    app._start_executor = _noop  # type: ignore[method-assign]
    app._monitor_executor = _noop  # type: ignore[method-assign]
    app._poll_tasklist = _noop  # type: ignore[method-assign]
    app._poll_context = _noop  # type: ignore[method-assign]

    app.show_verbose_output = False

    async with app.run_test() as pilot:
        chat = app.query_one(ChatPanel)
        log = chat.query_one("#chat-log", RichLog)

        # Simulate a COMMAND_RESULT event
        event = {
            "type": "COMMAND_RESULT",
            "data": {
                "stage": "TestStage",
                "command": "echo hello",
                "success": True,
                "output": "hello world",
            },
        }

        app._handle_event(event)
        await pilot.pause()

        # Verify it was added to history
        assert any(m["kind"] == "TOOL_RESULT" for m in chat._message_history)
        # Verbose is OFF by default, so it should NOT be in the rendered log lines
        assert "hello world" not in "".join(str(line) for line in log.lines)

        # Toggle output ON
        app.action_toggle_output()
        await pilot.pause()
        assert app.show_verbose_output is True
        assert "hello world" in "".join(str(line) for line in log.lines)


@pytest.mark.asyncio
async def test_ai_tool_call_filtering():
    """Verify that tool-call YAML blocks in AI messages are filtered when verbose is off."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)

        tool_markdown = (
            "I will check the file.\n\n`read_file` \n```yaml\npath: foo.py\n```\n\nDone."
        )

        # 1. Verbose ON - content should be stored unfiltered in history
        chat.show_verbose = True
        chat.add_markdown(tool_markdown)
        await pilot.pause()

        # History stores raw content
        assert len(chat._message_history) == 1
        assert chat._message_history[0]["content"] == tool_markdown

        # 2. Clear and test with Verbose OFF
        chat._message_history.clear()
        chat.show_verbose = False
        chat.add_markdown(tool_markdown)
        await pilot.pause()

        # History still stores raw content (filtering happens at render time)
        assert len(chat._message_history) == 1
        assert chat._message_history[0]["content"] == tool_markdown

        # But _filter_tool_call_blocks should collapse it
        filtered = chat._filter_tool_call_blocks(tool_markdown)
        assert "read_file [+] (ctrl+o to expand) - path: foo.py" in filtered
        assert "I will check the file." in filtered
        assert "Done." in filtered


@pytest.mark.asyncio
async def test_filter_tool_call_blocks_noop_when_verbose():
    """Verify that _filter_tool_call_blocks returns content unchanged when verbose is True."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        panel.show_verbose = True

        # Use four backticks to mirror ExplanationRenderer format
        content = "`Adding files to workspace`\n````yaml\nfoo: bar\n````\nAfter."

        filtered = panel._filter_tool_call_blocks(content)
        assert filtered == content


@pytest.mark.asyncio
async def test_filter_tool_call_blocks_collapses_four_backtick_yaml():
    """Verify that four-backtick YAML blocks are collapsed when verbose is False."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        panel.show_verbose = False

        # Use four backticks to mirror ExplanationRenderer format
        content = "`Adding files to workspace`\n````yaml\nfoo: bar\n````\nAfter."

        filtered = panel._filter_tool_call_blocks(content)

        # Summary marker should be present with first line of YAML as hint
        assert "Adding files to workspace [+] (ctrl+o to expand) - foo: bar" in filtered
        # Surrounding content preserved
        assert "After." in filtered


@pytest.mark.asyncio
async def test_filter_tool_call_blocks_triple_backtick_compatibility():
    """Verify that triple-backtick YAML blocks are also collapsed for robustness."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        panel.show_verbose = False

        # Use triple backticks (legacy format)
        content = "`read_file`\n```yaml\npath: foo.py\n```\nDone."

        filtered = panel._filter_tool_call_blocks(content)

        # Summary marker should be present with first line of YAML as hint
        assert "read_file [+] (ctrl+o to expand) - path: foo.py" in filtered
        # Surrounding content preserved
        assert "Done." in filtered


@pytest.mark.asyncio
async def test_tool_call_visibility_toggle_integration():
    """
    Integration-style test: verify that toggling output via BrokkApp.action_toggle_output
    correctly refreshes the visibility of tool calls in existing AI messages.
    """
    from textual.widgets import RichLog

    from brokk_code.app import BrokkApp

    executor = MagicMock()
    app = BrokkApp(executor=executor)

    async def _noop() -> None:
        return None

    app._start_executor = _noop  # type: ignore[method-assign]
    app._monitor_executor = _noop  # type: ignore[method-assign]
    app._poll_tasklist = _noop  # type: ignore[method-assign]
    app._poll_context = _noop  # type: ignore[method-assign]

    # Start with verbose OFF
    app.show_verbose_output = False

    async with app.run_test() as pilot:
        chat = app.query_one(ChatPanel)

        tool_markdown = (
            "Thinking about a file.\n\n`list_files` \n```yaml\ndirectory: src\n```\n\nFinished."
        )

        # Add message while verbose is OFF
        chat.add_markdown(tool_markdown)
        await pilot.pause()

        def get_plain_text(line):
            if isinstance(line, Text):
                return line.plain
            if hasattr(line, "plain"):
                return line.plain
            # For Strip and other objects, try to extract plain text
            text_str = str(line)
            # If it looks like a repr of Strip/Segment, try to extract content
            if "Segment(" in text_str:
                import re

                # Match Segment('content', ...) or Segment('content')
                matches = re.findall(r"Segment\('([^'\\]*(?:\\.[^'\\]*)*)'", text_str)
                if matches:
                    return "".join(matches)
            return text_str

        rendered_off = "".join(
            get_plain_text(line) for line in chat.query_one("#chat-log", RichLog).lines
        )
        # Collapsed tool call should show summary line with first YAML line as hint
        assert "list_files [+] (ctrl+o to expand) - directory: src" in rendered_off

        # Verify the raw content is in history
        assert any(m["content"] == tool_markdown for m in chat._message_history)

        # Verify filtering works correctly based on show_verbose state
        chat.show_verbose = False
        filtered_off = chat._filter_tool_call_blocks(tool_markdown)
        assert "list_files [+] (ctrl+o to expand) - directory: src" in filtered_off

        # Toggle output ON
        app.action_toggle_output()
        await pilot.pause()
        assert app.show_verbose_output is True
        rendered_on = "".join(
            get_plain_text(line) for line in chat.query_one("#chat-log", RichLog).lines
        )
        assert "Tool Call: list_files [-] (ctrl+o to collapse)" in rendered_on

        # With verbose ON, filtering should be a no-op
        chat.show_verbose = True
        filtered_on = chat._filter_tool_call_blocks(tool_markdown)
        assert filtered_on == tool_markdown
        assert "directory: src" in filtered_on

        # Toggle output OFF again
        app.action_toggle_output()
        await pilot.pause()
        assert app.show_verbose_output is False

        rendered_off_again = "".join(
            get_plain_text(line) for line in chat.query_one("#chat-log", RichLog).lines
        )
        assert "list_files [+] (ctrl+o to expand) - directory: src" in rendered_off_again

        # Verify filtering works again when off
        chat.show_verbose = False
        filtered_off_again = chat._filter_tool_call_blocks(tool_markdown)
        assert "list_files [+] (ctrl+o to expand) - directory: src" in filtered_off_again


@pytest.mark.asyncio
async def test_collapsed_summary_text_bold_label():
    """Verify that _collapsed_summary_text renders the label portion in bold."""
    from textual.app import App, ComposeResult

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)

        # Test with content
        result = panel._collapsed_summary_text("Thinking", "Some content here")
        assert result.plain == "Thinking [+] (ctrl+o to expand) - Some content here"

        # Verify spans: first span should be bold covering the label
        assert len(result.spans) >= 1
        assert result.spans[0].start == 0
        assert result.spans[0].end == len("Thinking")
        assert "bold" in str(result.spans[0].style).lower()

        # Test without content (empty string)
        result2 = panel._collapsed_summary_text("Command Output", "")
        assert result2.plain == "Command Output [+] (ctrl+o to expand)"
        assert len(result2.spans) >= 1
        assert result2.spans[0].start == 0
        assert result2.spans[0].end == len("Command Output")
        assert "bold" in str(result2.spans[0].style).lower()


@pytest.mark.asyncio
async def test_autoscroll_disabled_when_scrolled_up():
    """
    Verify that when the user scrolls up from the bottom, auto_scroll is disabled,
    and when they scroll back to the bottom, it is re-enabled.
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

        # auto_scroll defaults to True in RichLog
        assert log.auto_scroll is True

        # Add enough content to make the log scrollable
        for i in range(50):
            panel.add_system_message(f"Message {i}")
        await pilot.pause()

        # After writes with auto_scroll=True, we should still have auto_scroll enabled
        # (RichLog.write() scrolls to end when auto_scroll is True)
        assert log.auto_scroll is True

        # Simulate user scrolling up
        if log.max_scroll_y > 0:
            log.scroll_to(y=0, animate=False)
            await pilot.pause()

            # Call _sync_autoscroll to simulate the scroll event handler
            panel._sync_autoscroll()
            assert log.auto_scroll is False, "auto_scroll should be disabled when scrolled up"

            # Scroll back to the bottom
            log.scroll_end(animate=False)
            await pilot.pause()

            # Call _sync_autoscroll to simulate the scroll event handler
            panel._sync_autoscroll()
            assert log.auto_scroll is True, "auto_scroll should be re-enabled at bottom"


@pytest.mark.asyncio
async def test_autoscroll_reset_on_submission():
    """
    Verify that submitting a new user message resets the chat to bottom-follow mode
    and scrolls the log back to the end.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import RichLog

    from brokk_code.widgets.chat_panel import ChatInput

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)
        chat_input = panel.query_one("#chat-input", ChatInput)

        # Add enough content to make the log scrollable
        for i in range(50):
            panel.add_system_message(f"Message {i}")
        await pilot.pause()

        # Scroll up to disable auto_scroll
        if log.max_scroll_y > 0:
            log.scroll_to(y=0, animate=False)
            await pilot.pause()
            panel._sync_autoscroll()
            assert log.auto_scroll is False

            # Type and submit a message
            chat_input.text = "Hello"
            chat_input.action_submit()
            await pilot.pause()

            # After submission, auto_scroll should be re-enabled
            assert log.auto_scroll is True
            # And we should be at the bottom
            assert log.is_vertical_scroll_end


@pytest.mark.asyncio
async def test_sync_autoscroll_respects_scroll_position():
    """
    Verify that _sync_autoscroll correctly updates auto_scroll based on
    the current scroll position, not based on message writes.
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

        # Add content to make log scrollable
        for i in range(30):
            panel.add_system_message(f"Line {i}")
        await pilot.pause()

        # auto_scroll should remain True after writes (RichLog keeps it)
        assert log.auto_scroll is True

        # Manually disable auto_scroll and scroll up to simulate user action
        log.auto_scroll = False
        log.scroll_to(y=0, animate=False)
        await pilot.pause()

        # _sync_autoscroll should keep it False since we're not at bottom
        panel._sync_autoscroll()
        assert log.auto_scroll is False

        # Now scroll to end
        log.scroll_end(animate=False)
        await pilot.pause()

        # _sync_autoscroll should re-enable it
        panel._sync_autoscroll()
        assert log.auto_scroll is True
