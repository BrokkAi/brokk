from typing import Any

import pytest

from brokk_code.app import BrokkApp
from brokk_code.prompt_history import load_history
from tests.test_tui_resubmit import StubExecutor


def set_chat_input(app: BrokkApp, text: str) -> None:
    chat_input = app.query_one("#chat-input")
    chat_input.text = text
    chat_input.move_cursor(chat_input.document.end)


async def submit_prompt(app: BrokkApp, pilot: Any, text: str) -> None:
    set_chat_input(app, text)
    await pilot.press("enter")
    await pilot.pause(0)


@pytest.mark.asyncio
async def test_tui_prompt_persistence(tmp_path):
    """
    Verify that submitting prompts via the TUI correctly persists them to
    the workspace history file and respects trimming.
    """
    workspace = tmp_path / "project"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)

    async with app.run_test() as pilot:
        # 1. Submit a normal prompt
        await pilot.click("#chat-input")
        await submit_prompt(app, pilot, "hello world")

        # 2. Submit a command (should NOT be persisted)
        await submit_prompt(app, pilot, "/info")

        # 3. Submit another normal prompt
        await submit_prompt(app, pilot, "second prompt")

        # Verify history on disk
        history = load_history(workspace)
        assert history == ["hello world", "second prompt"]


@pytest.mark.asyncio
async def test_tui_prompt_trimming(tmp_path):
    """
    Verify that prompt history is trimmed when it exceeds the limit.
    """
    workspace = tmp_path / "project_trim"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    # Force a small max history for testing via settings
    app.settings.prompt_history_size = 2

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")

        for i in range(3):
            await submit_prompt(app, pilot, f"prompt {i}")
            # Clear input manually if needed (ChatPanel usually clears on submit)

        history = load_history(workspace)
        assert len(history) == 2
        assert history == ["prompt 1", "prompt 2"]


@pytest.mark.asyncio
async def test_tui_history_commands(tmp_path):
    """
    Verify /history and /history-clear commands via TUI.
    """
    workspace = tmp_path / "project_cmd"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)

    async with app.run_test() as pilot:
        # 1. Add some history
        await pilot.click("#chat-input")
        await submit_prompt(app, pilot, "prompt A")

        # 2. Check /history (just ensures no crash)
        await submit_prompt(app, pilot, "/history")

        # 3. Clear history
        await submit_prompt(app, pilot, "/history-clear")

        # Verify empty on disk
        assert load_history(workspace) == []


@pytest.mark.asyncio
async def test_tui_history_navigation(tmp_path, monkeypatch):
    """
    Verify that Up/Down arrows cycle through history in the TUI.
    """
    workspace = tmp_path / "project_nav"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    monkeypatch.setattr("brokk_code.app.append_prompt", lambda *args, **kwargs: None)

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")

        # 1. Populate some history
        await submit_prompt(app, pilot, "first prompt")
        await submit_prompt(app, pilot, "second prompt")

        # 2. Test navigation
        await pilot.click("#chat-input")
        set_chat_input(app, "draft text")

        # Ensure cursor is at start for Up navigation
        chat_input_widget = app.query_one("#chat-input")
        chat_input_widget.cursor_location = (0, 0)

        # Up once -> second prompt
        await pilot.press("up")
        await pilot.pause()
        assert chat_input_widget.text == "second prompt"

        # Up again -> first prompt
        chat_input_widget.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause()
        assert chat_input_widget.text == "first prompt"

        # Up again -> stays at first prompt (boundary)
        chat_input_widget.cursor_location = (0, 0)
        await pilot.press("up")
        assert chat_input_widget.text == "first prompt"

        # Down -> second prompt
        chat_input_widget.move_cursor(chat_input_widget.document.end)
        await pilot.press("down")
        assert chat_input_widget.text == "second prompt"

        # Down -> draft text
        chat_input_widget.move_cursor(chat_input_widget.document.end)
        await pilot.press("down")
        assert chat_input_widget.text == "draft text"


@pytest.mark.asyncio
async def test_tui_history_navigation_places_cursor_at_end(tmp_path, monkeypatch):
    """
    Verify history navigation moves cursor to end so Down works immediately.
    """
    workspace = tmp_path / "project_nav_cursor_end"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    monkeypatch.setattr("brokk_code.app.append_prompt", lambda *args, **kwargs: None)

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")
        await submit_prompt(app, pilot, "first")
        await submit_prompt(app, pilot, "second")

        chat_input = app.query_one("#chat-input")
        await pilot.click("#chat-input")
        set_chat_input(app, "draft")

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause()
        assert chat_input.text == "second"

        # Cursor should already be at end after loading history.
        await pilot.press("down")
        await pilot.pause()
        assert chat_input.text == "draft"


@pytest.mark.asyncio
async def test_tui_history_navigation_complex(tmp_path, monkeypatch):
    """
    Verify complex history navigation:
    1. Submit multiple prompts.
    2. Check Up/Down cycling.
    3. Ensure draft is preserved when navigating away and back.
    """
    workspace = tmp_path / "project_nav_complex"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    monkeypatch.setattr("brokk_code.app.append_prompt", lambda *args, **kwargs: None)

    async with app.run_test() as pilot:
        # Submit: one, two, three
        prompts = ["one", "two", "three"]
        for p in prompts:
            await pilot.click("#chat-input")
            await submit_prompt(app, pilot, p)

        chat_input = app.query_one("#chat-input")

        # Start a draft
        await pilot.click("#chat-input")
        set_chat_input(app, "draft")
        assert chat_input.text == "draft"

        # Start a draft. Cursor is at the end after typing.
        # To trigger Up navigation from non-empty draft, must move to start.
        chat_input.cursor_location = (0, 0)

        # Up x1 -> "three"
        await pilot.press("up")
        await pilot.pause()
        assert chat_input.text == "three"

        # Up x1 -> "two"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause()
        assert chat_input.text == "two"

        # Up x1 -> "one"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause()
        assert chat_input.text == "one"

        # Up x1 -> stays at "one"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        assert chat_input.text == "one"

        # Down x1 -> "two"
        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        assert chat_input.text == "two"

        # Down x1 -> "three"
        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        assert chat_input.text == "three"

        # Down x1 -> "draft"
        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        assert chat_input.text == "draft"

        # Down x1 -> stays at "draft"
        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        assert chat_input.text == "draft"


@pytest.mark.asyncio
async def test_tui_history_duplicates(tmp_path, monkeypatch):
    """
    Verify that history preserves duplicates and cycles through them.
    """
    workspace = tmp_path / "project_dupes"
    workspace.mkdir()

    stub = StubExecutor(auto_release=True)
    stub.workspace_dir = workspace

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    monkeypatch.setattr("brokk_code.app.append_prompt", lambda *args, **kwargs: None)

    async with app.run_test() as pilot:
        chat_input = app.query_one("#chat-input")

        # Submit: "a", "b", "a"
        for p in ["a", "b", "a"]:
            await pilot.click("#chat-input")
            await submit_prompt(app, pilot, p)

        await pilot.click("#chat-input")

        # Up 1 -> "a"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        assert chat_input.text == "a"

        # Up 2 -> "b"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        assert chat_input.text == "b"

        # Up 3 -> "a"
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        assert chat_input.text == "a"
