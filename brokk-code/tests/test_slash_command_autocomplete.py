import pytest
from textual.app import App, ComposeResult
from textual.widgets import Static
from brokk_code.widgets.chat_panel import (
    ChatPanel,
    ChatInput,
    SlashCommandSuggestions,
    SlashCommandInlineHint,
)


class AutocompleteTestApp(App):
    def get_slash_commands(self):
        return [
            {"command": "/ask", "description": "Ask mode"},
            {"command": "/model", "description": "Set model"},
            {"command": "/model-code", "description": "Set code model"},
            {"command": "/task next", "description": "Next task"},
            {"command": "/task toggle", "description": "Toggle task"},
        ]

    def compose(self) -> ComposeResult:
        yield ChatPanel()


@pytest.mark.asyncio
async def test_autocomplete_shows_on_slash():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)
        assert suggestions.display is False

        await pilot.press("/")
        assert suggestions.display is True
        # All 5 commands should show
        assert len(suggestions.children) == 5


@pytest.mark.asyncio
async def test_autocomplete_filtering():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press("/", "m", "o")
        # Should match /model and /model-code
        assert len(suggestions.children) == 2

        await pilot.press("d", "e", "l")
        # Should only match /model
        assert len(suggestions.children) == 1
        assert "/model -" in str(suggestions.children[0].query_one(Static).renderable)


@pytest.mark.asyncio
async def test_autocomplete_multi_word_filtering():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press(*list("/task t"))
        # Should match /task toggle
        assert len(suggestions.children) == 1
        assert "/task toggle" in str(suggestions.children[0].query_one(Static).renderable)


@pytest.mark.asyncio
async def test_autocomplete_selection_updates_input():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        await pilot.press(*list("/as"))
        await pilot.press("enter")

        assert chat_input.text == "/ask"
        assert app.query_one(SlashCommandSuggestions).display is False


@pytest.mark.asyncio
async def test_autocomplete_esc_hides():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)
        hint = app.query_one(SlashCommandInlineHint)

        await pilot.press("/")
        assert suggestions.display is True
        assert hint.display is True

        await pilot.press("escape")
        assert suggestions.display is False
        assert hint.display is False


@pytest.mark.asyncio
async def test_autocomplete_navigates_with_arrows_bypassing_history():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        chat_panel.set_history(["previous message"])
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press("/")
        # suggestions index defaults to 0
        assert suggestions.index == 0

        await pilot.press("down")
        assert suggestions.index == 1
        # Check that history didn't kick in
        assert chat_input.text == "/"

        await pilot.press("up")
        assert suggestions.index == 0
        assert chat_input.text == "/"


@pytest.mark.asyncio
async def test_autocomplete_accept_with_tab_does_not_submit():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)
        hint = app.query_one(SlashCommandInlineHint)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press("/", "m", "o")
        assert hint.display is True
        await pilot.press("tab")

        # /model needs args, so it should have a trailing space
        assert chat_input.text == "/model "
        assert suggestions.display is False
        assert hint.display is False
        assert len(submissions) == 0


@pytest.mark.asyncio
async def test_autocomplete_accept_with_enter_submits_immediately():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press("/", "a", "s")
        assert suggestions.display is True

        # Enter should now both complete and submit
        await pilot.press("enter")

        assert chat_input.text == ""  # Submitted text is cleared
        assert suggestions.display is False
        assert len(submissions) == 1
        assert submissions[0] == "/ask"


@pytest.mark.asyncio
async def test_autocomplete_task_next_does_not_double_space():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        # User types /task n
        await pilot.press(*list("/task n"))
        await pilot.press("enter")

        # "/task next" already has internal spaces/is complete, shouldn't get extra space
        assert chat_input.text == "/task next"


@pytest.mark.asyncio
async def test_autocomplete_tab_regression_no_crash():
    """
    Regression test for TypeError when pressing Tab with suggestions open.
    This ensures that ChatInput._on_key correctly calls suggestions.action_select_cursor()
    and that it doesn't collide with any internal message naming.
    """
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # 1. Type to show suggestions
        await pilot.press("/", "m", "o")
        assert suggestions.display is True

        # 2. Press Tab. This triggers ChatInput._on_key logic for 'tab'
        # which calls suggestions.action_select_cursor().
        await pilot.press("tab")

        # 3. Assertions
        assert suggestions.display is False
        # /model is the first match and requires args, so expect trailing space
        assert chat_input.text == "/model "


@pytest.mark.asyncio
async def test_inline_hint_visibility_and_content():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        hint = app.query_one(SlashCommandInlineHint)
        assert hint.display is False

        # Start typing a command
        await pilot.press("/")
        # Best match for "/" is "/ask" based on order in AutocompleteTestApp
        assert hint.display is True
        assert str(hint.renderable) == "/ask"

        # Type more to change best match
        await pilot.press("m")
        assert hint.display is True
        assert str(hint.renderable) == "/model"

        # Type enough to match exactly - hint should disappear
        await pilot.press("o", "d", "e", "l")
        assert hint.display is False

        # Backspace should bring it back
        await pilot.press("backspace")
        assert hint.display is True
        assert str(hint.renderable) == "/model"

        # Clear input
        await pilot.press("ctrl+u")
        assert hint.display is False


@pytest.mark.asyncio
async def test_inline_hint_hidden_on_newline():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        hint = app.query_one(SlashCommandInlineHint)

        await pilot.press("/")
        assert hint.display is True

        await pilot.press("shift+enter")
        assert hint.display is False


@pytest.mark.asyncio
async def test_autocomplete_history_interaction():
    """Ensure history navigation is gated correctly by autocomplete visibility."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        chat_panel.set_history(["history 1", "history 2"])
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # 1. No autocomplete: Up should show history
        await pilot.press("up")
        assert chat_input.text == "history 2"

        # 2. Reset and start typing a command to show autocomplete
        chat_input.text = ""
        await pilot.press("/")
        assert suggestions.display is True
        assert chat_input.text == "/"

        # 3. With autocomplete: Up should navigate suggestions, NOT history
        await pilot.press("up")
        assert chat_input.text == "/"
        # (Assuming index stays at 0 if moving 'up' from 0 in ListView,
        # but the key is that text didn't change to history)

        # 4. Escape to hide autocomplete
        await pilot.press("escape")
        assert suggestions.display is False

        # 5. Up should now navigate history again
        await pilot.press("up")
        assert chat_input.text == "history 2"


@pytest.mark.asyncio
async def test_inline_hint_cleared_on_submit():
    """Ensures the inline hint is hidden when a command is submitted."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        hint = app.query_one(SlashCommandInlineHint)
        chat_input = app.query_one(ChatInput)

        await pilot.press("/", "a")
        assert hint.display is True

        await pilot.press("enter")
        assert chat_input.text == ""
        assert hint.display is False


@pytest.mark.asyncio
async def test_inline_hint_multi_word_no_double_space():
    """Ensures /task next doesn't result in double spaces when completed via hint."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        hint = app.query_one(SlashCommandInlineHint)

        # Type /task n
        await pilot.press(*list("/task n"))
        assert hint.display is True
        assert str(hint.renderable) == "/task next"

        # Accept hint with Tab
        await pilot.press("tab")
        assert chat_input.text == "/task next"
        # Ensure no trailing space added because /task next is a complete multi-word command
