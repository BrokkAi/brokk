import json
import zipfile
from pathlib import Path

from extract_turn import _extract_response_sections, extract_turn_messages


def _write_turn_files(root: Path) -> None:
    turn_dir = root / ".brokk" / "llm-history" / "session" / "Code Example"
    turn_dir.mkdir(parents=True)
    (turn_dir / "001-request.json").write_text(
        json.dumps({"messages": [{"role": "user", "content": "hello"}]}),
        encoding="utf-8",
    )
    (turn_dir / "001-response.log").write_text(
        """\
## text
hi
""",
        encoding="utf-8",
    )


def _write_archive_from_worktree(worktree: Path, archive_path: Path) -> None:
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, "w") as archive:
        for path in worktree.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(worktree))


def test_extract_response_sections_preserves_markdown_headings_in_text() -> None:
    log_text = """\
## reasoningContent
thinking

## text
## Findings
Keep this heading.

### Details
Keep this subheading too.

## toolExecutionRequests
[]

## metadata
{"elapsedMs": 123}
"""

    sections = _extract_response_sections(log_text, 1)

    assert sections["text"] == """\
## Findings
Keep this heading.

### Details
Keep this subheading too."""
    assert sections["toolExecutionRequests"] == "[]"
    assert sections["metadata"] == '{"elapsedMs": 123}'


def test_extract_turn_messages_accepts_json_worktree_reference_to_directory(
    tmp_path: Path,
) -> None:
    worktree = tmp_path / "worktree"
    _write_turn_files(worktree)
    reference = tmp_path / "input.json"
    reference.write_text(json.dumps({"worktree": "worktree"}), encoding="utf-8")

    payload = extract_turn_messages(reference, 1)

    assert payload["worktree"] == str(worktree)
    assert [message["type"] for message in payload["messages"]] == ["user", "assistant"]
    assert payload["messages"][1]["text"] == "hi"


def test_extract_turn_messages_accepts_json_worktree_reference_to_zip(
    tmp_path: Path,
) -> None:
    worktree = tmp_path / "worktree"
    _write_turn_files(worktree)
    archive_path = tmp_path / "archive.zip"
    _write_archive_from_worktree(worktree, archive_path)

    reference = tmp_path / "input.json"
    reference.write_text(json.dumps({"worktree": "archive.zip"}), encoding="utf-8")

    payload = extract_turn_messages(reference, 1)

    assert payload["worktree"] == str(archive_path)
    assert [message["type"] for message in payload["messages"]] == ["user", "assistant"]
    assert payload["messages"][1]["text"] == "hi"


def test_extract_turn_messages_falls_back_to_brokkbench_archive_for_direct_path(
    tmp_path: Path,
) -> None:
    source_worktree = tmp_path / "source-worktree"
    _write_turn_files(source_worktree)
    requested_worktree = tmp_path / "root" / "brokkbench" / "bitwarden" / "run-1"
    archive_path = tmp_path / "root" / "brokkbench-archive" / "bitwarden" / "run-1.zip"
    _write_archive_from_worktree(source_worktree, archive_path)

    payload = extract_turn_messages(requested_worktree, 1)

    assert payload["worktree"] == str(archive_path)
    assert [message["type"] for message in payload["messages"]] == ["user", "assistant"]
    assert payload["messages"][1]["text"] == "hi"


def test_extract_turn_messages_falls_back_to_brokkbench_archive_for_json_reference(
    tmp_path: Path,
) -> None:
    source_worktree = tmp_path / "source-worktree"
    _write_turn_files(source_worktree)
    requested_worktree = tmp_path / "root" / "brokkbench" / "bitwarden" / "run-1"
    archive_path = tmp_path / "root" / "brokkbench-archive" / "bitwarden" / "run-1.zip"
    _write_archive_from_worktree(source_worktree, archive_path)
    reference = tmp_path / "input.json"
    reference.write_text(json.dumps({"worktree": str(requested_worktree)}), encoding="utf-8")

    payload = extract_turn_messages(reference, 1)

    assert payload["worktree"] == str(archive_path)
    assert [message["type"] for message in payload["messages"]] == ["user", "assistant"]
    assert payload["messages"][1]["text"] == "hi"
