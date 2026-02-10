import pytest
from pathlib import Path
from brokk_code.app import BrokkApp
from brokk_code.prompt_history import load_history
from tests.test_tui_resubmit import StubExecutor

@pytest.mark.asyncio
async def test_tui_prompt_persistence(tmp_path):
    """
    Verify that submitting prompts via the TUI correctly persists them to 
    the workspace history file and respects trimming.
    """
    workspace = tmp_path / "project"
    workspace.mkdir()
    
    stub = StubExecutor()
    stub.workspace_dir = workspace
    # Allow jobs to finish immediately for this test
    stub.release_stream.set()
    
    app = BrokkApp(executor=stub, workspace_dir=workspace)
    
    async with app.run_test() as pilot:
        # 1. Submit a normal prompt
        await pilot.click("#chat-input")
        await pilot.press_ascii("hello world")
        await pilot.press("enter")
        await pilot.pause()
        
        # 2. Submit a command (should NOT be persisted)
        await pilot.press_ascii("/info")
        await pilot.press("enter")
        await pilot.pause()
        
        # 3. Submit another normal prompt
        await pilot.press_ascii("second prompt")
        await pilot.press("enter")
        await pilot.pause()
        
        # Verify history on disk
        history = load_history(workspace)
        assert history == ["hello world", "second prompt"]

@pytest.mark.asyncio
async def test_tui_prompt_trimming(tmp_path, monkeypatch):
    """
    Verify that prompt history is trimmed when it exceeds the limit.
    """
    from brokk_code import prompt_history
    # Force a small max history for testing
    monkeypatch.setattr(prompt_history, "DEFAULT_MAX_HISTORY", 2)
    
    workspace = tmp_path / "project_trim"
    workspace.mkdir()
    
    stub = StubExecutor()
    stub.workspace_dir = workspace
    stub.release_stream.set()
    
    app = BrokkApp(executor=stub, workspace_dir=workspace)
    
    async with app.run_test() as pilot:
        await pilot.click("#chat-input")
        
        for i in range(3):
            await pilot.press_ascii(f"prompt {i}")
            await pilot.press("enter")
            await pilot.pause()
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
    
    stub = StubExecutor()
    stub.workspace_dir = workspace
    stub.release_stream.set()
    
    app = BrokkApp(executor=stub, workspace_dir=workspace)
    
    async with app.run_test() as pilot:
        # 1. Add some history
        await pilot.click("#chat-input")
        await pilot.press_ascii("prompt A")
        await pilot.press("enter")
        await pilot.pause()
        
        # 2. Check /history (just ensures no crash)
        await pilot.press_ascii("/history")
        await pilot.press("enter")
        await pilot.pause()
        
        # 3. Clear history
        await pilot.press_ascii("/history-clear")
        await pilot.press("enter")
        await pilot.pause()
        
        # Verify empty on disk
        assert load_history(workspace) == []

@pytest.mark.asyncio
async def test_tui_history_navigation(tmp_path):
    """
    Verify that Up/Down arrows cycle through history in the TUI.
    """
    workspace = tmp_path / "project_nav"
    workspace.mkdir()
    
    stub = StubExecutor()
    stub.workspace_dir = workspace
    stub.release_stream.set()
    
    app = BrokkApp(executor=stub, workspace_dir=workspace)
    
    async with app.run_test() as pilot:
        await pilot.click("#chat-input")
        
        # 1. Populate some history
        await pilot.press_ascii("first prompt")
        await pilot.press("enter")
        await pilot.pause()
        await pilot.press_ascii("second prompt")
        await pilot.press("enter")
        await pilot.pause()
        
        # 2. Test navigation
        await pilot.click("#chat-input")
        await pilot.press_ascii("draft text")
        
        # Up once -> second prompt
        await pilot.press("up")
        assert app.query_one("#chat-input").value == "second prompt"
        
        # Up again -> first prompt
        await pilot.press("up")
        assert app.query_one("#chat-input").value == "first prompt"
        
        # Up again -> stays at first prompt (boundary)
        await pilot.press("up")
        assert app.query_one("#chat-input").value == "first prompt"
        
        # Down -> second prompt
        await pilot.press("down")
        assert app.query_one("#chat-input").value == "second prompt"
        
        # Down -> draft text
        await pilot.press("down")
        assert app.query_one("#chat-input").value == "draft text"

@pytest.mark.asyncio
async def test_tui_history_navigation_complex(tmp_path):
    """
    Verify complex history navigation:
    1. Submit multiple prompts.
    2. Check Up/Down cycling.
    3. Ensure draft is preserved when navigating away and back.
    """
    workspace = tmp_path / "project_nav_complex"
    workspace.mkdir()

    stub = StubExecutor()
    stub.workspace_dir = workspace
    stub.release_stream.set()

    app = BrokkApp(executor=stub, workspace_dir=workspace)

    async with app.run_test() as pilot:
        # Submit: one, two, three
        prompts = ["one", "two", "three"]
        for p in prompts:
            await pilot.click("#chat-input")
            await pilot.press_ascii(p)
            await pilot.press("enter")
            await pilot.pause()

        chat_input = app.query_one("#chat-input")

        # Start a draft
        await pilot.click("#chat-input")
        await pilot.press_ascii("draft")
        assert chat_input.value == "draft"

        # Up x1 -> "three"
        await pilot.press("up")
        assert chat_input.value == "three"

        # Up x1 -> "two"
        await pilot.press("up")
        assert chat_input.value == "two"

        # Up x1 -> "one"
        await pilot.press("up")
        assert chat_input.value == "one"

        # Up x1 -> stays at "one"
        await pilot.press("up")
        assert chat_input.value == "one"

        # Down x1 -> "two"
        await pilot.press("down")
        assert chat_input.value == "two"

        # Down x1 -> "three"
        await pilot.press("down")
        assert chat_input.value == "three"

        # Down x1 -> "draft"
        await pilot.press("down")
        assert chat_input.value == "draft"

        # Down x1 -> stays at "draft"
        await pilot.press("down")
        assert chat_input.value == "draft"
