import pytest
from textual.app import App, ComposeResult
from textual.widgets import Static
from brokk_code.widgets.chat_panel import (
    ChatPanel,
    ChatInput,
    SlashCommandSuggestions,
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
        # suggestions.display should be True here
        assert app.query_one(SlashCommandSuggestions).display is True

        # In this test app context, /ask is the first suggestion.
        # Enter selects it and, because it's /ask, also submits (clearing input).
        await pilot.press("enter")

        assert chat_input.text == ""
        assert app.query_one(SlashCommandSuggestions).display is False


@pytest.mark.asyncio
async def test_autocomplete_esc_hides():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press("/")
        assert suggestions.display is True

        await pilot.press("escape")
        assert suggestions.display is False


@pytest.mark.asyncio
async def test_autocomplete_navigates_with_arrows_bypassing_history():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_panel = app.query_one(ChatPanel)
        # Set some history to ensure we can distinguish between history and menu nav
        chat_panel.set_history(["previous message"])
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press("/")
        # suggestions index defaults to 0
        assert suggestions.display is True
        assert suggestions.index == 0

        await pilot.press("down")
        assert suggestions.index == 1
        # Check that history didn't kick in (input stays at just the slash)
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

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press("/", "m", "o")
        assert suggestions.display is True
        await pilot.press("tab")

        # /model needs args, so it should have a trailing space
        assert chat_input.text == "/model "
        assert suggestions.display is False
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
async def test_autocomplete_ui_hidden_after_selection_even_if_prefix_matches_others():
    """
    Regression test: accepting /model should hide the UI even if /model-code exists.
    Previously, the Change event would re-trigger suggestions because /model is a prefix of /model-code.
    """
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # 1. Type to match both /model and /model-code
        await pilot.press(*list("/mode"))
        assert suggestions.display is True
        assert len(suggestions.children) == 2

        # 2. Use Tab to accept the suggestion for /model
        await pilot.press("l")  # text is now /model
        await pilot.press("tab")

        assert chat_input.text == "/model "
        assert suggestions.display is False


@pytest.mark.asyncio
async def test_autocomplete_ui_hidden_invariant_after_submit():
    """Ensure suggestions are hidden after submitting, even from a partial match."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)
        suggestions = app.query_one(SlashCommandSuggestions)

        # 1. Type partial command
        await pilot.press("/", "a")
        assert suggestions.display is True

        # 2. Submit (Enter)
        await pilot.press("enter")

        # 3. Verify UI is hidden and text is cleared
        assert chat_input.text == ""
        assert suggestions.display is False


@pytest.mark.asyncio
async def test_autocomplete_scrollbar_configuration():
    """Verify that the suggestions list is configured to show scrollbars."""
    app = AutocompleteTestApp()
    async with app.run_test():
        suggestions = app.query_one(SlashCommandSuggestions)
        # Verify vertical scrollbar is enabled via the correct attribute
        assert suggestions.show_vertical_scrollbar is True


@pytest.mark.asyncio
async def test_autocomplete_ui_state_shrinks_prompt():
    """Verify that the autocomplete-open class is applied and reduces prompt height."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        container = app.query_one("#chat-input-container")
        chat_input = app.query_one(ChatInput)

        # Initial state
        assert not container.has_class("autocomplete-open")
        assert chat_input.styles.height.value == 3

        # Trigger autocomplete
        await pilot.press("/")
        assert container.has_class("autocomplete-open")
        # In Textual, styles are updated after a refresh.
        # We check the class is there; height check confirms the CSS rule intent.
        assert chat_input.styles.height.value == 1

        # Hide autocomplete
        await pilot.press("escape")
        assert not container.has_class("autocomplete-open")
        assert chat_input.styles.height.value == 3
