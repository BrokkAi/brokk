import json
from pathlib import Path
from brokk_code.session_persistence import (
    get_state_dir,
    get_session_zip_path,
    save_last_session_id,
    load_last_session_id,
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
