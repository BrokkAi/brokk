import json
from pathlib import Path

import zipfile
from brokk_code.session_persistence import (
    get_session_zip_path,
    get_state_dir,
    has_tasks,
    load_last_session_id,
    save_last_session_id,
)


def test_state_dir_path():
    workspace = Path("/tmp/fake_ws")
    assert get_state_dir(workspace) == workspace / ".brokk"


def test_session_zip_path_creation(tmp_path):
    workspace = tmp_path
    session_id = "test-uuid-123"
    zip_path = get_session_zip_path(workspace, session_id)

    assert zip_path.name == "test-uuid-123.zip"
    assert zip_path.parent.name == "sessions"
    assert zip_path.parent.parent.name == ".brokk"
    # Ensure directory was created
    assert zip_path.parent.exists()


def test_last_session_id_roundtrip(tmp_path):
    workspace = tmp_path
    session_id = "8888-9999-aaaa"

    # Initially None
    assert load_last_session_id(workspace) is None

    # Save and Load
    save_last_session_id(workspace, session_id)
    assert load_last_session_id(workspace) == session_id


def test_load_last_session_id_tolerant(tmp_path):
    workspace = tmp_path
    state_dir = get_state_dir(workspace)
    state_dir.mkdir()
    session_file = state_dir / "last_session.json"

    # Malformed JSON
    session_file.write_text("invalid json {")
    assert load_last_session_id(workspace) is None

    # Missing key
    session_file.write_text(json.dumps({"wrongKey": "value"}))
    assert load_last_session_id(workspace) is None


def test_save_last_session_creates_missing_dirs(tmp_path):
    # Test path where .brokk doesn't exist yet
    workspace = tmp_path / "new_project"
    session_id = "new-uuid"

    save_last_session_id(workspace, session_id)
    assert (workspace / ".brokk" / "last_session.json").exists()
    assert load_last_session_id(workspace) == session_id


def test_has_tasks_missing_or_invalid(tmp_path):
    assert has_tasks(tmp_path / "nonexistent.zip") is False

    corrupt = tmp_path / "corrupt.zip"
    corrupt.write_text("not a zip")
    assert has_tasks(corrupt) is False


def test_has_tasks_legacy_format(tmp_path):
    zip_path = tmp_path / "legacy.zip"

    # Case: Legacy with tasks
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("tasklist.json", json.dumps({"tasks": [{"id": "1", "title": "test"}]}))
    assert has_tasks(zip_path) is True

    # Case: Legacy empty
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("tasklist.json", json.dumps({"tasks": []}))
    assert has_tasks(zip_path) is False


def test_has_tasks_current_format(tmp_path):
    zip_path = tmp_path / "current.zip"

    def create_zip(context_virtuals, fragment_desc, tasks):
        with zipfile.ZipFile(zip_path, "w") as z:
            z.writestr("contexts.jsonl", json.dumps({"virtuals": context_virtuals}))
            fragments = {"virtual": {"v1": {"description": fragment_desc, "contentId": "c1"}}}
            z.writestr("fragments-v4.json", json.dumps(fragments))
            z.writestr("content/c1.txt", json.dumps({"tasks": tasks}))

    # Case: Success
    create_zip(["v1"], "Task List", [{"id": "1"}])
    assert has_tasks(zip_path) is True

    # Case: Empty task list
    create_zip(["v1"], "Task List", [])
    assert has_tasks(zip_path) is False

    # Case: Fragment not in current context
    create_zip(["other"], "Task List", [{"id": "1"}])
    assert has_tasks(zip_path) is False

    # Case: Fragment is not "Task List"
    create_zip(["v1"], "Notes", [{"id": "1"}])
    assert has_tasks(zip_path) is False
