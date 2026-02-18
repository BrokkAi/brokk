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
