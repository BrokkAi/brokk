import pytest
from textual.app import App, ComposeResult
from textual.widgets import Static, TabbedContent, TabPane

from brokk_code.app import SettingsModalScreen


class SettingsTestApp(App):
    def compose(self) -> ComposeResult:
        yield Static("main")


@pytest.mark.asyncio
async def test_settings_modal_has_tabbed_content():
    app = SettingsTestApp()
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()

        screen = app.screen
        tabs = screen.query_one(TabbedContent)
        assert tabs is not None
        assert tabs.id == "settings-tabs"

        tab_panes = list(screen.query(TabPane))
        tab_ids = [tp.id for tp in tab_panes]
        assert "settings-tab-build" in tab_ids
        assert "settings-tab-ci" in tab_ids
        assert "settings-tab-integrations" in tab_ids
        assert "settings-tab-general" in tab_ids
        assert len(tab_panes) == 4


@pytest.mark.asyncio
async def test_settings_modal_tab_labels():
    app = SettingsTestApp()
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()

        screen = app.screen
        tab_panes = list(screen.query(TabPane))

        # Check tab labels by iterating panes
        # TabPane stores its title in the `title` property
        labels = {tp.id: str(tp.title) for tp in tab_panes}
        assert labels.get("settings-tab-build") == "Build"
        assert labels.get("settings-tab-ci") == "Code Intelligence"
        assert labels.get("settings-tab-integrations") == "Integrations"
        assert labels.get("settings-tab-general") == "General"
