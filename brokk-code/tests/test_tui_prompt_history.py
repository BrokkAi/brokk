from pathlib import Path
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

    stub = StubExecutor(workspace_dir=workspace, auto_release=True)

    app = BrokkApp(executor=stub, workspace_dir=workspace)

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")
        await submit_prompt(app, pilot, "hello world")
        await submit_prompt(app, pilot, "second prompt")

    history = load_history(app.executor.workspace_dir)
    assert history == ["hello world", "second prompt"]


@pytest.mark.asyncio
async def test_tui_prompt_trimming(tmp_path):
    """
    Verify that prompt history is trimmed when it exceeds the limit.
    """
    workspace = tmp_path / "project_trim"
    workspace.mkdir()

    stub = StubExecutor(workspace_dir=workspace, auto_release=True)

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    app.settings.prompt_history_size = 2

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")

        for i in range(3):
            await submit_prompt(app, pilot, f"prompt {i}")

    history = load_history(app.executor.workspace_dir)
    assert len(history) == 2
    assert history == ["prompt 1", "prompt 2"]


@pytest.mark.asyncio
async def test_tui_history_navigation_and_duplicates(tmp_path, monkeypatch):
    """
    Verify Up/Down history navigation behavior, draft restoration, and duplicates.
    """
    workspace = tmp_path / "project_nav"
    workspace.mkdir()

    stub = StubExecutor(workspace_dir=workspace, auto_release=True)

    app = BrokkApp(executor=stub, workspace_dir=workspace)
    monkeypatch.setattr("brokk_code.app.append_prompt", lambda *args, **kwargs: None)

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")

        for prompt in ["one", "two", "three"]:
            await submit_prompt(app, pilot, prompt)

        chat_input = app.query_one("#chat-input")
        await pilot.click("#chat-input")
        set_chat_input(app, "draft")

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "three"

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "two"

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "one"

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "one"

        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        await pilot.pause(0)
        assert chat_input.text == "two"

        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        await pilot.pause(0)
        assert chat_input.text == "three"

        chat_input.move_cursor(chat_input.document.end)
        await pilot.press("down")
        await pilot.pause(0)
        assert chat_input.text == "draft"

        # Cursor should be at end after loading history; Down should work immediately.
        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "three"
        await pilot.press("down")
        await pilot.pause(0)
        assert chat_input.text == "draft"

        # Duplicates are preserved and traversed in chronological order.
        for prompt in ["a", "b", "a"]:
            await submit_prompt(app, pilot, prompt)

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "a"

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "b"

        chat_input.cursor_location = (0, 0)
        await pilot.press("up")
        await pilot.pause(0)
        assert chat_input.text == "a"


@pytest.mark.asyncio
async def test_slash_commands_recorded_in_history(tmp_path):
    """
    Verify that slash commands are recorded in prompt history alongside regular prompts.
    """
    workspace = tmp_path / "project_slash"
    workspace.mkdir()

    stub = StubExecutor(workspace_dir=workspace, auto_release=True)

    app = BrokkApp(executor=stub, workspace_dir=workspace)

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")

        # Submit a regular prompt
        await submit_prompt(app, pilot, "hello world")

        # Submit slash commands
        await submit_prompt(app, pilot, "/info")
        await submit_prompt(app, pilot, "/clear")

        # Submit another regular prompt
        await submit_prompt(app, pilot, "goodbye")

    history = load_history(app.executor.workspace_dir)
    assert history == ["hello world", "/info", "/clear", "goodbye"]


@pytest.mark.asyncio
async def test_prompt_history_with_cwd_workspace(tmp_path, monkeypatch):
    """
    Regression test for Issue #2798:
    Verify that when workspace_dir is Path("."), the history file is created
    correctly under the current working directory's .brokk folder.
    """
    # Isolate the test environment by changing to a temporary directory
    test_cwd = tmp_path / "test_cwd"
    test_cwd.mkdir()
    monkeypatch.chdir(test_cwd)

    # Use Path(".") as the workspace
    workspace = Path(".")

    # Use StubExecutor which mimics the real executor's handling of workspace_dir
    stub = StubExecutor(workspace_dir=workspace, auto_release=True)

    # Initialize the app with the stub executor pointing to "."
    # BrokkApp will resolve workspace_dir internally using resolve_workspace_dir
    app = BrokkApp(executor=stub, workspace_dir=workspace)

    # Resolve Path(".") as it will be used internally (it lands in test_cwd)
    expected_history_path = (test_cwd / ".brokk" / "prompts.json").resolve()

    async with app.run_test() as pilot:
        await pilot.click("#chat-input")
        # Submit a non-slash prompt to trigger append_prompt in app.py
        await submit_prompt(app, pilot, "test prompt in cwd")

    # Verify the history file was created in the expected location
    assert expected_history_path.exists(), f"History file should exist at {expected_history_path}"

    # Double check content via load_history using the original workspace path
    history = load_history(workspace)
    assert history == ["test prompt in cwd"]
