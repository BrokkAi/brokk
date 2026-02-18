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


def test_has_tasks_history_definition(tmp_path):
    zip_path = tmp_path / "session.zip"

    def create_zip(lines: list[str] | None):
        with zipfile.ZipFile(zip_path, "w") as z:
            if lines is not None:
                z.writestr("contexts.jsonl", "\n".join(lines))

    # Missing contexts.jsonl
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("something_else.txt", "data")
    assert has_tasks(zip_path) is False

    # Empty contexts.jsonl
    create_zip([])
    assert has_tasks(zip_path) is False

    # contexts.jsonl but no tasks array
    create_zip([json.dumps({"id": "ctx1"})])
    assert has_tasks(zip_path) is False

    # tasks array but missing meta
    create_zip([json.dumps({"tasks": [{"sequence": 1}]})])
    assert has_tasks(zip_path) is False

    # tasks array with meta but missing/invalid sequence
    create_zip([json.dumps({"tasks": [{"taskType": "LUTZ"}]})])
    assert has_tasks(zip_path) is False

    # Success: meta and sequence
    create_zip([json.dumps({"tasks": [{"sequence": 1, "taskType": "LUTZ"}]})])
    assert has_tasks(zip_path) is True

    # Tolerant parsing: malformed line then valid line
    create_zip(
        ["{ invalid json", json.dumps({"tasks": [{"sequence": 1, "primaryModelName": "gpt-4o"}]})]
    )
    assert has_tasks(zip_path) is True

    # Check other meta fields
    create_zip([json.dumps({"tasks": [{"sequence": 2, "primaryModelReasoning": "logic"}]})])
    assert has_tasks(zip_path) is True
