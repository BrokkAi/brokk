import pytest
from textual.app import App, ComposeResult

from brokk_code.widgets.tasklist_panel import TaskListPanel


@pytest.mark.asyncio
async def test_tasklist_panel_arrow_navigation_updates_selection():
    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield TaskListPanel(id="tl")

    app = TestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#tl", TaskListPanel)
        panel.update_tasklist_details(
            {
                "bigPicture": "x",
                "tasks": [
                    {"id": "1", "title": "One", "text": "One", "done": False},
                    {"id": "2", "title": "Two", "text": "Two", "done": False},
                ],
            }
        )
        panel.focus()
        await pilot.pause()

        assert panel.selected_index == 0
        await pilot.press("down")
        await pilot.pause()
        assert panel.selected_index == 1
