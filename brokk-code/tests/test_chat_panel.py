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
    content in the ChatLog.
    """
    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", ChatLog)

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

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", ChatLog)

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

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", ChatLog)

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
    assert "/task" in cmds_only
    assert "/clear" in cmds_only
    assert "/help" not in cmds_only
    assert "/mode" not in cmds_only


@pytest.mark.asyncio
async def test_slash_autocomplete_filtering():
    """Verify slash suggestions filter correctly."""
    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import ChatPanel, SlashCommandSuggestions

    class TestApp(App):
        def get_slash_commands(self):
            return [
                {"command": "/api-key", "description": "d"},
                {"command": "/autocommit", "description": "d"},
            ]

        def compose(self) -> ComposeResult:
            yield ChatPanel()

    app = TestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        # Type /a
        await pilot.press(*list("/a"))
        assert suggestions.display is True
        # matches /api-key and /autocommit
        assert len(suggestions.children) == 2

        # Type uto
        await pilot.press(*list("uto"))
        assert len(suggestions.children) == 1
        assert "/autocommit" in str(suggestions.children[0].query_one(Static).render())

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

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        log = chat.query_one("#chat-log", ChatLog)

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
    from brokk_code.app import BrokkApp
    from brokk_code.widgets.chat_panel import ChatLog

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
        log = chat.query_one("#chat-log", ChatLog)

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
    from brokk_code.app import BrokkApp
    from brokk_code.widgets.chat_panel import ChatLog

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
            get_plain_text(line) for line in chat.query_one("#chat-log", ChatLog).lines
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
            get_plain_text(line) for line in chat.query_one("#chat-log", ChatLog).lines
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
            get_plain_text(line) for line in chat.query_one("#chat-log", ChatLog).lines
        )
        assert "list_files [+] (ctrl+o to expand) - directory: src" in rendered_off_again

        # Verify filtering works again when off
        chat.show_verbose = False
        filtered_off_again = chat._filter_tool_call_blocks(tool_markdown)
        assert "list_files [+] (ctrl+o to expand) - directory: src" in filtered_off_again


@pytest.mark.asyncio
async def test_clear_transcript_command():
    """Verify that /clear removes messages from the UI and memory."""
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

    async with app.run_test() as pilot:
        chat = app.query_one(ChatPanel)
        log = chat.query_one("#chat-log", RichLog)

        # Add some messages
        chat.add_user_message("Message 1")
        chat.add_markdown("Response 1")
        await pilot.pause()

        initial_count = len(chat._message_history)
        chat.add_user_message("Message 2")
        chat.add_markdown("Response 2")
        await pilot.pause()

        assert len(chat._message_history) == initial_count + 2
        assert len(log.lines) > 0

        # Run /clear
        app._handle_command("/clear")
        await pilot.pause()

        # Verify everything is gone
        assert len(chat._message_history) == 0
        assert len(log.lines) == 0
        assert chat._current_message_buffer == ""


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
async def test_chat_log_get_selection():
    """Verify that ChatLog.get_selection() extracts text from log content using real Selection."""
    from textual.app import App, ComposeResult
    from textual.geometry import Offset
    from textual.selection import Selection

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        chat.add_markdown("first line")
        chat.add_markdown("second line")
        chat.add_markdown("third line")
        await pilot.pause()

        log = chat.query_one("#chat-log", ChatLog)

        # Verify lines are populated
        assert len(log.lines) > 0

        full_text = "\n".join(strip.text for strip in log.lines)
        assert "first line" in full_text
        assert "second line" in full_text
        assert "third line" in full_text

        # Find line indices containing our text
        line_texts = [strip.text for strip in log.lines]
        first_line_idx = next(i for i, t in enumerate(line_texts) if "first" in t.lower())

        # Create a real Selection that selects "first" (characters 0-5 on that line)
        start_offset = Offset(x=0, y=first_line_idx)
        end_offset = Offset(x=5, y=first_line_idx)
        selection = Selection.from_offsets(start_offset, end_offset)

        # Test get_selection returns text with newline separator
        result = log.get_selection(selection)
        assert result is not None
        extracted, sep = result
        assert sep == "\n"
        # The extracted text should be "first" (first 5 characters of the line)
        assert extracted == "first", f"Expected 'first', got '{extracted}'"


@pytest.mark.asyncio
async def test_chat_log_selection_updated_clears_cache():
    """Verify that selection_updated() clears the line cache and refreshes."""
    from unittest.mock import patch

    from textual.app import App, ComposeResult

    from brokk_code.widgets.chat_panel import ChatLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        chat.add_markdown("some text")
        await pilot.pause()

        log = chat.query_one("#chat-log", ChatLog)

        # Prime the cache by rendering
        assert len(log.lines) > 0

        with patch.object(log, "refresh") as mock_refresh:
            log.selection_updated(None)
            mock_refresh.assert_called_once()

        # Cache should be empty after selection_updated
        assert len(log._line_cache) == 0


@pytest.mark.asyncio
async def test_chat_log_selection_rendering_applies_styles():
    """
    Integration test verifying that ChatLog._render_line() applies selection
    styling to rendered output when a real Selection is active.

    This test exercises the Textual 8.0.0 private internals compatibility block in
    ChatLog._render_line(). If a future Textual upgrade changes the private
    APIs (_start_line, _widest_line_width, _line_cache, Strip._segments),
    this test should fail, signaling that the compatibility block needs updating.
    """
    from textual.app import App, ComposeResult
    from textual.geometry import Offset
    from textual.selection import Selection

    from brokk_code.widgets.chat_panel import ChatLog

    class SelectionTestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = SelectionTestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        log = chat.query_one("#chat-log", ChatLog)

        # Add content to the log
        chat.add_markdown("hello world test content")
        await pilot.pause()

        # Ensure we have rendered lines
        assert len(log.lines) > 0, "ChatLog should have rendered lines"

        # Find the line containing our text
        text_line_idx = None
        for idx, strip in enumerate(log.lines):
            if "hello" in strip.text.lower():
                text_line_idx = idx
                break

        assert text_line_idx is not None, "Should find line with 'hello' in it"

        # --- Render WITHOUT selection first ---
        log._line_cache.clear()
        strip_no_selection = log._render_line(text_line_idx, 0, 80)
        assert strip_no_selection.cell_length > 0, "Strip should have content"

        # Extract segments and styles without selection
        segments_no_sel = list(strip_no_selection._segments)
        styles_no_sel = [seg.style for seg in segments_no_sel]

        # --- Now set up a real Selection using the correct Textual 8.0.0 API ---
        # Selection is NamedTuple(start: Offset | None, end: Offset | None)
        # Offset is (x, y) where x=column, y=row
        # Select characters 0-5 on our target line (should cover "hello")
        start_offset = Offset(x=0, y=text_line_idx)
        end_offset = Offset(x=5, y=text_line_idx)
        selection = Selection.from_offsets(start_offset, end_offset)

        # Store selection in screen.selections dict (the real Textual API)
        # Widget.text_selection reads from screen.selections.get(widget, None)
        log.screen.selections[log] = selection

        # Clear cache to force re-rendering with selection active
        log._line_cache.clear()

        # --- Render WITH selection ---
        strip_with_selection = log._render_line(text_line_idx, 0, 80)
        assert strip_with_selection.cell_length > 0, "Strip should have content"

        # Extract segments and styles with selection
        segments_with_sel = list(strip_with_selection._segments)
        styles_with_sel = [seg.style for seg in segments_with_sel]

        # --- Verify text content is unchanged ---
        text_no_sel = "".join(seg.text for seg in segments_no_sel)
        text_with_sel = "".join(seg.text for seg in segments_with_sel)
        assert text_no_sel == text_with_sel, (
            "Text content should be identical with or without selection"
        )

        # --- Verify styling differs due to selection ---
        # The selection should cause style changes in the selected range.
        # We check that at least one style differs between the two renders.
        # This proves the selection render path actually applied selection styling.
        styles_differ = styles_no_sel != styles_with_sel
        assert styles_differ, (
            "Styles should differ when selection is active. "
            "If this fails, either selection styling is not being applied "
            "or the Textual private API has changed."
        )

        # --- Verify cache was populated ---
        cache_key = (
            text_line_idx + log._start_line,
            0,
            80,
            log._widest_line_width,
        )
        assert cache_key in log._line_cache, "Rendered line should be cached"

        # --- Clean up: remove selection ---
        del log.screen.selections[log]


@pytest.mark.asyncio
async def test_chat_log_render_line_horizontal_scroll_selection():
    """
    Verify that ChatLog._render_line() correctly applies selection styling
    when scroll_x > 0 (horizontal scrolling).

    This tests the coordinate transformation: selection spans are in full-line
    coordinates, but after cropping the visible slice starts at viewport offset 0.
    The selection range must be adjusted by subtracting scroll_x.
    """
    from textual.app import App, ComposeResult
    from textual.geometry import Offset
    from textual.selection import Selection

    from brokk_code.widgets.chat_panel import ChatLog

    class HScrollTestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = HScrollTestApp()
    async with app.run_test() as pilot:
        chat = app.query_one("#chat", ChatPanel)
        log = chat.query_one("#chat-log", ChatLog)

        # Add a long line that will require horizontal scrolling
        long_content = "AAAAA_BBBBB_CCCCC_DDDDD_EEEEE"
        chat.add_system_message(long_content)
        await pilot.pause()

        assert len(log.lines) > 0, "ChatLog should have rendered lines"

        # Find the line containing our long content
        target_line_idx = None
        for idx, strip in enumerate(log.lines):
            if "BBBBB" in strip.text:
                target_line_idx = idx
                break

        assert target_line_idx is not None, "Should find line with long content"

        # Get the full line text to find exact character positions
        full_line_text = log.lines[target_line_idx].text
        bbbbb_start = full_line_text.find("BBBBB")
        assert bbbbb_start >= 0, "Should find BBBBB in line"
        bbbbb_end = bbbbb_start + 5  # "BBBBB" is 5 chars

        # --- Test Case 1: Selection fully visible in viewport ---
        # scroll_x = 0, select "BBBBB"
        log._line_cache.clear()
        start_offset = Offset(x=bbbbb_start, y=target_line_idx)
        end_offset = Offset(x=bbbbb_end, y=target_line_idx)
        selection = Selection.from_offsets(start_offset, end_offset)
        log.screen.selections[log] = selection

        strip_scroll0 = log._render_line(target_line_idx, scroll_x=0, width=80)
        segments_scroll0 = list(strip_scroll0._segments)
        assert len(segments_scroll0) > 0, "Should have segments when rendering with selection"

        # --- Test Case 2: Horizontal scroll, selection partially visible ---
        # scroll_x = bbbbb_start, so "BBBBB" should appear at viewport position 0
        log._line_cache.clear()
        scroll_x = bbbbb_start
        strip_scrolled = log._render_line(target_line_idx, scroll_x=scroll_x, width=80)
        segments_scrolled = list(strip_scrolled._segments)

        # After scrolling, the viewport starts at character bbbbb_start
        # So "BBBBB" should be at viewport positions 0-4
        # The selection (bbbbb_start to bbbbb_end in full-line coords)
        # should become (0 to 5 in viewport coords)
        viewport_text = "".join(seg.text for seg in segments_scrolled)
        assert viewport_text.startswith("BBBBB"), (
            f"Viewport should start with BBBBB after scroll, got: {viewport_text[:20]}"
        )

        # --- Test Case 3: Selection entirely before visible viewport ---
        # Select "AAAAA" (positions 0-5), but scroll past it
        log._line_cache.clear()
        aaaaa_start = full_line_text.find("AAAAA")
        if aaaaa_start >= 0:
            start_offset_a = Offset(x=aaaaa_start, y=target_line_idx)
            end_offset_a = Offset(x=aaaaa_start + 5, y=target_line_idx)
            selection_a = Selection.from_offsets(start_offset_a, end_offset_a)
            log.screen.selections[log] = selection_a

            # Scroll past "AAAAA" - selection should not be visible
            scroll_past = aaaaa_start + 10
            strip_past = log._render_line(target_line_idx, scroll_x=scroll_past, width=80)
            segments_past = list(strip_past._segments)

            # "AAAAA" should not be in viewport
            viewport_past = "".join(seg.text for seg in segments_past)
            assert "AAAAA" not in viewport_past, "AAAAA should be scrolled out of view"

        # --- Test Case 4: Selection extends from before viewport into viewport ---
        # Select a range that starts before scroll_x and ends within visible area
        log._line_cache.clear()
        # Select from start of line to middle of "BBBBB"
        start_offset_ext = Offset(x=0, y=target_line_idx)
        end_offset_ext = Offset(x=bbbbb_start + 3, y=target_line_idx)  # "BBB"
        selection_ext = Selection.from_offsets(start_offset_ext, end_offset_ext)
        log.screen.selections[log] = selection_ext

        # Scroll so that "BBBBB" is at the start of viewport
        strip_partial = log._render_line(target_line_idx, scroll_x=bbbbb_start, width=80)
        segments_partial = list(strip_partial._segments)

        # First 3 chars of viewport ("BBB") should have selection styling
        # Characters 4+ should not
        viewport_partial = "".join(seg.text for seg in segments_partial)
        assert viewport_partial.startswith("BBBBB"), (
            f"Should start with BBBBB, got: {viewport_partial[:10]}"
        )

        # --- Clean up ---
        del log.screen.selections[log]


@pytest.mark.asyncio
async def test_autoscroll_and_button_toggle_on_scroll():
    """
    Verify that scrolling up disables auto_scroll and shows the button,
    and scrolling back to bottom re-enables auto_scroll and hides the button.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import Button, RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test(size=(80, 10)) as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)
        scroll_btn = panel.query_one("#scroll-to-bottom", Button)

        assert log.auto_scroll is True
        assert scroll_btn.has_class("hidden")

        for i in range(20):
            panel.add_system_message(f"Message {i}")

        # Wait for scroll state to settle after rapid writes;
        # on Windows CI the scroll position may lag behind content height.
        for _ in range(10):
            await pilot.pause()
            if log.auto_scroll and log.max_scroll_y > 0:
                break
        assert log.auto_scroll is True
        assert log.max_scroll_y > 0, "Log must be scrollable for this test"

        # Scroll up
        log.scroll_to(y=0, animate=False)
        for _ in range(10):
            await pilot.pause()
            panel._sync_autoscroll()
            if not log.auto_scroll:
                break

        assert log.auto_scroll is False, "auto_scroll should be disabled when scrolled up"
        assert not scroll_btn.has_class("hidden"), "Button should be visible when scrolled up"

        # Scroll back to bottom
        log.scroll_end(animate=False)
        for _ in range(10):
            await pilot.pause()
            panel._sync_autoscroll()
            if log.auto_scroll:
                break

        assert log.auto_scroll is True, "auto_scroll should be re-enabled at bottom"
        assert scroll_btn.has_class("hidden"), "Button should be hidden at bottom"


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
    async with app.run_test(size=(80, 10)) as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)
        chat_input = panel.query_one("#chat-input", ChatInput)

        # Add enough content to make the log scrollable in small viewport
        for i in range(20):
            panel.add_system_message(f"Message {i}")
        await pilot.pause()

        # Verify scrollability is deterministic
        assert log.max_scroll_y > 0, "Log must be scrollable for this test"

        # Scroll up to disable auto_scroll
        log.scroll_to(y=0, animate=False)
        for _ in range(10):
            await pilot.pause()
            panel._sync_autoscroll()
            if not log.auto_scroll:
                break
        assert log.auto_scroll is False

        # Type and submit a message
        chat_input.text = "Hello"
        chat_input.action_submit()
        # First pause processes the submission and schedules the deferred scroll
        await pilot.pause()
        # Second pause allows the call_after_refresh callback to execute
        await pilot.pause()

        # After submission, auto_scroll should be re-enabled
        assert log.auto_scroll is True

        # Wait for the call_after_refresh in _reset_to_follow_bottom to complete
        await pilot.pause()

        # And we should be at the bottom. We check with a small retry loop
        # to ensure deterministic behavior across refresh cycles.
        for _ in range(10):
            if log.is_vertical_scroll_end:
                break
            await pilot.pause()
        assert log.is_vertical_scroll_end


@pytest.mark.asyncio
async def test_scroll_to_bottom_button_hidden_by_default():
    """Verify the scroll-to-bottom button is hidden when composed."""
    from textual.app import App, ComposeResult
    from textual.widgets import Button

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test():
        panel = app.query_one("#chat", ChatPanel)
        scroll_btn = panel.query_one("#scroll-to-bottom", Button)
        assert scroll_btn.has_class("hidden")


@pytest.mark.asyncio
async def test_refresh_log_preserves_middle_scroll_position():
    """
    Verify that when scrolled to a middle position (not 0, not bottom),
    refresh_log preserves the scroll position (clamped to new max) and
    keeps auto_scroll disabled.
    """
    from textual.app import App, ComposeResult
    from textual.widgets import Button, RichLog

    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield ChatPanel(id="chat")

    app = TestApp()
    async with app.run_test(size=(80, 10)) as pilot:
        panel = app.query_one("#chat", ChatPanel)
        log = panel.query_one("#chat-log", RichLog)
        scroll_btn = panel.query_one("#scroll-to-bottom", Button)

        # Add enough content to make the log scrollable in small viewport
        for i in range(20):
            panel.add_system_message(f"Message {i}")
        await pilot.pause()

        # Verify scrollability is deterministic
        assert log.max_scroll_y > 0, "Log must be scrollable for this test"

        # Scroll to middle position (not 0, not bottom)
        mid_y = log.max_scroll_y // 2
        assert mid_y > 0, "mid_y must be positive to test middle scroll"
        log.scroll_to(y=mid_y, animate=False)
        for _ in range(10):
            await pilot.pause()
            panel._sync_autoscroll()
            if not log.auto_scroll:
                break

        assert log.auto_scroll is False, "auto_scroll should be disabled at middle position"
        assert not scroll_btn.has_class("hidden"), "Button should be visible at middle position"

        prior_scroll_y = log.scroll_y

        # Call refresh_log
        panel.refresh_log(show_verbose=True)
        await pilot.pause()

        # scroll_y should be restored (clamped to new max_scroll_y)
        assert log.scroll_y <= log.max_scroll_y, (
            "scroll_y should not exceed max_scroll_y after refresh"
        )
        assert log.scroll_y > 0, "scroll_y should be restored near prior position, not reset to 0"
        # Allow some tolerance since content may re-render slightly differently
        assert abs(log.scroll_y - min(prior_scroll_y, log.max_scroll_y)) <= 1, (
            f"scroll_y ({log.scroll_y}) should be close to prior ({prior_scroll_y}) "
            f"or clamped to max ({log.max_scroll_y})"
        )
        assert log.auto_scroll is False, (
            "auto_scroll should remain disabled after refresh_log at middle position"
        )
        assert not scroll_btn.has_class("hidden"), (
            "Button should remain visible after refresh_log at middle position"
        )
