import pytest
from textual.app import App, ComposeResult
from textual.widgets import Static
from brokk_code.widgets.chat_panel import ChatPanel, ChatInput, SlashCommandSuggestions


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

        await pilot.press("/")
        assert suggestions.display is True

        await pilot.press("escape")
        assert suggestions.display is False


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
async def test_autocomplete_accept_with_tab_appends_space_for_model():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        await pilot.press("/", "m", "o")
        await pilot.press("tab")

        # /model needs args, so it should have a trailing space
        assert chat_input.text == "/model "
        assert app.query_one(SlashCommandSuggestions).display is False


@pytest.mark.asyncio
async def test_autocomplete_accept_with_enter_submits_immediately():
    app = AutocompleteTestApp()
    async with app.run_test() as pilot:
        chat_input = app.query_one(ChatInput)

        # Track submissions
        submissions = []
        app.on_chat_panel_submitted = lambda msg: submissions.append(msg.text)

        await pilot.press("/", "a", "s")
        # Enter should now both complete and submit
        await pilot.press("enter")

        assert chat_input.text == ""  # Submitted text is cleared
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
