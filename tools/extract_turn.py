#!/usr/bin/env python3
"""Extract request/response messages for a Brokk Code turn."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from collections.abc import Callable
from pathlib import Path


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Extract request/response messages for a Brokk Code turn."
    )
    parser.add_argument(
        "worktree",
        type=Path,
        help="Worktree directory or archived .zip produced by the harness.",
    )
    parser.add_argument(
        "turn",
        type=int,
        help="Turn number to extract (for example, 1 for 001).",
    )
    parser.add_argument(
        "--message",
        type=int,
        help="Sequence number to print in full instead of summarizing all messages.",
    )
    return parser


def _resolve_worktree_path(worktree: Path) -> Path:
    resolved = worktree.expanduser()
    zip_fallback = resolved.with_suffix(".zip")
    if zip_fallback.exists():
        return zip_fallback

    if resolved.exists():
        return resolved

    raise FileNotFoundError(f"worktree path does not exist: {resolved}")


def _is_code_turn_path(path: Path) -> bool:
    if ".brokk" not in path.parts or "llm-history" not in path.parts:
        return False
    if "summaries" in path.parts:
        return False
    return any(part.startswith("Code ") or " Code " in part for part in path.parts)


def _matches_code_turn_log(path_str: str, turn: int) -> bool:
    path = Path(path_str)
    if path.suffix != ".log" or not _is_code_turn_path(path):
        return False
    turn_token = f"{turn:03d}-"
    return path.name.startswith(turn_token) or f" {turn_token}" in path.name


def _matches_code_turn_request(path_str: str, turn: int) -> bool:
    path = Path(path_str)
    if path.suffix != ".json" or not _is_code_turn_path(path):
        return False
    if not path.name.endswith("request.json"):
        return False
    turn_token = f"{turn:03d}-"
    return path.name.startswith(turn_token) or f" {turn_token}" in path.name


def _read_single_matching_directory_file(
    worktree: Path,
    turn: int,
    matcher: Callable[[str, int], bool],
    label: str,
) -> str:
    matches = sorted(
        path
        for path in worktree.rglob("*")
        if path.is_file() and matcher(str(path.relative_to(worktree)), turn)
    )
    if not matches:
        raise FileNotFoundError(f"no matching {label} found for turn {turn:03d} in {worktree}")
    if len(matches) > 1:
        raise ValueError(
            f"multiple matching {label}s found for turn {turn:03d} in {worktree}: "
            + ", ".join(str(path) for path in matches)
        )
    return matches[0].read_text(encoding="utf-8")


def _read_single_matching_zip_file(
    archive_path: Path,
    turn: int,
    matcher: Callable[[str, int], bool],
    label: str,
) -> str:
    with zipfile.ZipFile(archive_path) as archive:
        matches = sorted(name for name in archive.namelist() if matcher(name, turn))
        if not matches:
            raise FileNotFoundError(
                f"no matching {label} found for turn {turn:03d} in {archive_path}"
            )
        if len(matches) > 1:
            raise ValueError(
                f"multiple matching {label}s found for turn {turn:03d} in {archive_path}: "
                + ", ".join(matches)
            )
        with archive.open(matches[0]) as member:
            return member.read().decode("utf-8")


def _read_turn_request_json(location: Path, turn: int) -> str:
    if location.is_dir():
        return _read_single_matching_directory_file(
            location,
            turn,
            _matches_code_turn_request,
            "Code request.json",
        )
    return _read_single_matching_zip_file(
        location,
        turn,
        _matches_code_turn_request,
        "Code request.json",
    )


def _read_turn_log(location: Path, turn: int) -> str:
    if location.is_dir():
        return _read_single_matching_directory_file(
            location,
            turn,
            _matches_code_turn_log,
            "Code .log file",
        )
    return _read_single_matching_zip_file(
        location,
        turn,
        _matches_code_turn_log,
        "Code .log file",
    )


def _content_to_text(content: object) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    parts.append(text)
                    continue
                nested_content = item.get("content")
                if isinstance(nested_content, str):
                    parts.append(nested_content)
                    continue
            parts.append(json.dumps(item, ensure_ascii=False))
        return "\n".join(parts)
    if isinstance(content, dict):
        text = content.get("text")
        if isinstance(text, str):
            return text
        nested_content = content.get("content")
        if isinstance(nested_content, str):
            return nested_content
    return json.dumps(content, ensure_ascii=False)


def _extract_request_messages(request_json_text: str) -> list[tuple[str, str]]:
    payload = json.loads(request_json_text)
    raw_messages = payload.get("messages")
    if not isinstance(raw_messages, list):
        raise ValueError("request JSON is missing a valid 'messages' array")

    messages: list[tuple[str, str]] = []
    for item in raw_messages:
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        if not isinstance(role, str):
            continue
        messages.append((role, _content_to_text(item.get("content", ""))))
    return messages


def _extract_response_sections(log_text: str, turn: int) -> tuple[str, str | None]:
    lines = log_text.splitlines()
    text_heading_index: int | None = None
    metadata_heading_index: int | None = None
    for index, line in enumerate(lines):
        if line == "## text":
            text_heading_index = index
        elif line == "## metadata":
            metadata_heading_index = index
    if text_heading_index is None:
        raise ValueError(f"missing '## text' section in extracted log for turn {turn:03d}")

    if metadata_heading_index is None or metadata_heading_index <= text_heading_index:
        ai_lines = lines[text_heading_index + 1 :]
        return "\n".join(ai_lines).rstrip("\n"), None

    ai_lines = lines[text_heading_index + 1 : metadata_heading_index]
    metadata_lines = lines[metadata_heading_index + 1 :]
    return (
        "\n".join(ai_lines).rstrip("\n"),
        "\n".join(metadata_lines).rstrip("\n"),
    )


def extract_turn_messages(worktree: Path, turn: int) -> tuple[list[tuple[str, str]], str | None]:
    if turn < 1:
        raise ValueError("turn must be a positive integer")

    location = _resolve_worktree_path(worktree)
    if not location.is_dir() and location.suffix != ".zip":
        raise ValueError(f"unsupported worktree location: {location}")

    request_json_text = _read_turn_request_json(location, turn)
    response_log_text = _read_turn_log(location, turn)

    messages = _extract_request_messages(request_json_text)
    ai_body, metadata_body = _extract_response_sections(response_log_text, turn)
    messages.append(("ai", ai_body))
    return messages, metadata_body


def _format_message(message_type: str, sequence: int, body: str, *, full: bool) -> str:
    lines = body.splitlines()
    if not full and len(lines) > 3:
        lines = [*lines[:3], "..."]
    block = [f"<message type={message_type} sequence={sequence}>", *lines, "</message>"]
    return "\n".join(block)


def main(argv: list[str] | None = None, stdout: object | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    output_stream = stdout if stdout is not None else sys.stdout

    try:
        messages, metadata_body = extract_turn_messages(args.worktree, args.turn)
    except (FileNotFoundError, ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        parser.error(str(exc))

    if args.message is not None:
        if args.message < 1 or args.message > len(messages):
            parser.error(f"--message must be between 1 and {len(messages)}")
        selected_type, selected_body = messages[args.message - 1]
        print(
            _format_message(selected_type, args.message, selected_body, full=True),
            file=output_stream,
        )
        return 0

    rendered = [
        _format_message(message_type, sequence, body, full=False)
        for sequence, (message_type, body) in enumerate(messages, start=1)
    ]
    if metadata_body is not None:
        rendered.extend(["## metadata", metadata_body])
    print("\n".join(rendered), file=output_stream)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
