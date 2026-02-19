import pytest
from textual.app import App, ComposeResult
from textual.widgets import Static

from brokk_code.widgets.chat_panel import (
    ChatInput,
    ChatPanel,
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
        # Should match /model and /model-code
        assert len(suggestions.children) == 2
        # Verify the first match is /model
        assert "/model -" in str(suggestions.children[0].query_one(Static).render())


@pytest.mark.asyncio
async def test_autocomplete_multi_word_filtering():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)

        await pilot.press(*list("/task t"))
        # Should match /task toggle
        assert len(suggestions.children) == 1
        assert "/task toggle" in str(suggestions.children[0].query_one(Static).render())


@pytest.mark.asyncio
async def test_autocomplete_selection_submits_on_enter():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press(*list("/as"))
        # suggestions.display should be True here
        assert app.query_one(SlashCommandSuggestions).display is True

        # Enter now accepts the suggestion AND submits
        await pilot.press("enter")

        assert chat_input.text == ""  # Cleared because of submission
        assert app.query_one(SlashCommandSuggestions).display is False
        assert len(submissions) == 1
        assert submissions[0] == "/ask"


@pytest.mark.asyncio
async def test_autocomplete_esc_hides():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)
        container = app.query_one("#chat-input-container")

        await pilot.press("/")
        assert suggestions.display is True
        assert container.has_class("autocomplete-open")

        await pilot.press("escape")
        assert suggestions.display is False
        assert not container.has_class("autocomplete-open")


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
        container = app.query_one("#chat-input-container")

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press("/", "m", "o")
        assert suggestions.display is True
        assert container.has_class("autocomplete-open")

        await pilot.press("tab")

        # /model needs args, so it should have a trailing space
        assert chat_input.text == "/model "
        assert suggestions.display is False
        assert not container.has_class("autocomplete-open")
        assert len(submissions) == 0


@pytest.mark.asyncio
async def test_autocomplete_task_next_clears_on_enter():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        # User types /task n
        await pilot.press(*list("/task n"))
        await pilot.press("enter")

        # Now clears on enter because it submits
        assert chat_input.text == ""
        assert submissions[0] == "/task next"


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

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

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
        assert len(submissions) == 0


@pytest.mark.asyncio
async def test_autocomplete_mode_submits_on_enter():
    """Verify /mode submits on enter."""
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        # Type /mo and select /mode (which should be a match)
        await pilot.press(*list("/mode"))
        await pilot.press("enter")

        # /mode should be submitted and cleared
        assert chat_input.text == ""
        assert submissions[0] == "/mode"


@pytest.mark.asyncio
async def test_autocomplete_ui_hidden_after_selection_even_if_prefix_matches_others():
    """
    Regression test: accepting /model should hide the UI even if /model-code exists.
    Previously, the Change event would re-trigger suggestions because
    /model is a prefix of /model-code.
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
        container = app.query_one("#chat-input-container")

        # 1. Type partial command
        await pilot.press("/", "a")
        assert suggestions.display is True
        assert container.has_class("autocomplete-open")

        # 2. Accept and Submit (Enter)
        await pilot.press("enter")

        # 3. Verify UI is hidden and text is cleared (submitted)
        assert chat_input.text == ""
        assert suggestions.display is False
        assert not container.has_class("autocomplete-open")


@pytest.mark.asyncio
async def test_autocomplete_scrollbar_configuration():
    """Verify that the suggestions list is configured to show scrollbars."""
    app = AutocompleteTestApp()
    async with app.run_test():
        suggestions = app.query_one(SlashCommandSuggestions)
        # Verify vertical scrollbar is enabled via the correct attribute
        assert suggestions.show_vertical_scrollbar is True


@pytest.mark.asyncio
async def test_slash_triggers_menu_from_app_commands():
    """
    Regression test: Typing '/' should trigger the autocomplete menu
    and populate it using the app's get_slash_commands() hook.
    """
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        suggestions = app.query_one(SlashCommandSuggestions)
        container = app.query_one("#chat-input-container")

        # Ensure menu is hidden initially
        assert suggestions.display is False
        assert not container.has_class("autocomplete-open")

        # Type '/'
        await pilot.press("/")

        # Assert menu is now visible and populated
        assert suggestions.display is True
        assert container.has_class("autocomplete-open")
        assert len(suggestions.children) == 5

        # Verify the first command matches the app's mock data
        first_cmd = app.get_slash_commands()[0]["command"]
        assert first_cmd in str(suggestions.children[0].query_one(Static).render())

        # Escape hides it
        await pilot.press("escape")
        assert suggestions.display is False
        assert not container.has_class("autocomplete-open")


@pytest.mark.asyncio
async def test_autocomplete_hides_mode_menu():
    """Verify that typing a slash to show autocomplete hides the mode menu if it was open."""
    from brokk_code.widgets.chat_panel import ModeSuggestions

    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        mode_suggestions = app.query_one(ModeSuggestions)
        slash_suggestions = app.query_one(SlashCommandSuggestions)

        # Manually show mode menu
        mode_suggestions.display = True
        assert mode_suggestions.display is True
        assert slash_suggestions.display is False

        # Type slash
        await pilot.press("/")

        assert slash_suggestions.display is True
        assert mode_suggestions.display is False
