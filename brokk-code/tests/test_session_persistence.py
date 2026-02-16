import json
from pathlib import Path
import time

from brokk_code.session_persistence import (
    get_session_zip_path,
    get_state_dir,
    load_last_session_id,
    save_last_session_id,
    list_session_zips,
    recover_last_session_if_present,
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


def test_list_session_zips_empty(tmp_path):
    workspace = tmp_path
    # no .brokk/sessions directory
    lst = list_session_zips(workspace)
    assert isinstance(lst, list)
    assert lst == []


def test_list_session_zips_with_files(tmp_path):
    workspace = tmp_path
    sessions_dir = get_state_dir(workspace) / "sessions"
    sessions_dir.mkdir(parents=True)
    # Create two valid UUID-like filenames and one invalid
    valid1 = sessions_dir / "11111111-1111-1111-1111-111111111111.zip"
    valid2 = sessions_dir / "22222222-2222-2222-2222-222222222222.zip"
    invalid = sessions_dir / "not-a-uuid.zip"
    valid1.write_bytes(b"one")
    # Sleep to ensure mtime ordering difference
    time.sleep(0.01)
    valid2.write_bytes(b"two")
    invalid.write_bytes(b"bad")

    results = list_session_zips(workspace)
    # Should only include the two valid ones
    ids = [r["session_id"] for r in results]
    assert "11111111-1111-1111-1111-111111111111" in ids
    assert "22222222-2222-2222-2222-222222222222" in ids
    assert all("not-a-uuid" not in r["zip_path"].name for r in results)

    # Ensure returned metadata present
    for r in results:
        assert "zip_path" in r and "modified_time" in r and "size" in r

    # Ensure sort order: newest first (valid2 should be newer)
    assert results[0]["session_id"] == "22222222-2222-2222-2222-222222222222"


def test_recover_last_session_if_present(tmp_path):
    workspace = tmp_path
    sessions_dir = get_state_dir(workspace) / "sessions"
    sessions_dir.mkdir(parents=True)
    sid = "33333333-3333-3333-3333-333333333333"
    zip_path = sessions_dir / f"{sid}.zip"
    zip_path.write_bytes(b"x")
    # Save last_session pointing to existing id
    save_last_session_id(workspace, sid)
    assert recover_last_session_if_present(workspace) == sid

    # Now point last_session to missing id
    save_last_session_id(workspace, "deadbeef-dead-beef-dead-beefdeadbeef")
    assert recover_last_session_if_present(workspace) is None
