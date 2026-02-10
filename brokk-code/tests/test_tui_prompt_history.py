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
