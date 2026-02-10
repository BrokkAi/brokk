import json
from pathlib import Path
from brokk_code.prompt_history import append_prompt, load_history, get_history_file

def test_append_and_load_history(tmp_path):
    workspace = tmp_path
    prompt = "Hello Brokk"
    
    append_prompt(workspace, prompt)
    
    history = load_history(workspace)
    assert history == [prompt]
    
    history_file = get_history_file(workspace)
    assert history_file.exists()
    assert ".brokk" in history_file.parts

def test_history_trimming(tmp_path):
    workspace = tmp_path
    max_n = 3
    
    for i in range(5):
        append_prompt(workspace, f"prompt {i}", max_history=max_n)
        
    history = load_history(workspace)
    assert len(history) == max_n
    assert history == ["prompt 2", "prompt 3", "prompt 4"]

def test_corrupt_history_fallback(tmp_path):
    workspace = tmp_path
    history_file = get_history_file(workspace)
    history_file.parent.mkdir(parents=True)
    history_file.write_text("not valid json")
    
    # Should fall back to empty list rather than crashing
    history = load_history(workspace)
    assert history == []
    
    # Should be able to append and recover
    append_prompt(workspace, "new prompt")
    assert load_history(workspace) == ["new prompt"]

def test_missing_directory_creation(tmp_path):
    # Ensure it creates .brokk if it doesn't exist
    workspace = tmp_path / "new_project"
    append_prompt(workspace, "test")
    
    assert (workspace / ".brokk").is_dir()
    assert load_history(workspace) == ["test"]
