#!/usr/bin/env python3
"""Extract request/response messages for a Brokk Code turn."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import Any


_RESPONSE_SECTION_NAMES = frozenset(
    {
        "reasoningContent",
        "text",
        "toolExecutionRequests",
        "metadata",
    }
)


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
        help="Sequence number to print in full instead of returning all messages.",
    )
    parser.add_argument(
        "--tagged",
        action="store_true",
        help="Render a human-readable tagged view instead of JSON.",
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


def _matches_code_turn_tools(path_str: str, turn: int) -> bool:
    path = Path(path_str)
    if path.suffix != ".jsonl" or not _is_code_turn_path(path):
        return False
    if not path.name.endswith("tools.jsonl"):
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


def _read_matching_directory_files(
    worktree: Path,
    turn: int,
    matcher: Callable[[str, int], bool],
) -> list[str]:
    matches = sorted(
        path
        for path in worktree.rglob("*")
        if path.is_file() and matcher(str(path.relative_to(worktree)), turn)
    )
    return [path.read_text(encoding="utf-8") for path in matches]


def _read_matching_zip_files(
    archive_path: Path,
    turn: int,
    matcher: Callable[[str, int], bool],
) -> list[str]:
    contents: list[str] = []
    with zipfile.ZipFile(archive_path) as archive:
        matches = sorted(name for name in archive.namelist() if matcher(name, turn))
        for match in matches:
            with archive.open(match) as member:
                contents.append(member.read().decode("utf-8"))
    return contents


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


def _read_turn_tools_jsonl(location: Path, turn: int) -> list[str]:
    if location.is_dir():
        return _read_matching_directory_files(location, turn, _matches_code_turn_tools)
    return _read_matching_zip_files(location, turn, _matches_code_turn_tools)


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


def _non_empty_string(value: object) -> str | None:
    return value if isinstance(value, str) and value else None


def _normalize_tool_execution_request(raw: object) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError(f"invalid tool execution request: {raw!r}")

    normalized: dict[str, Any] = {}
    tool_id = _non_empty_string(raw.get("id"))
    tool_name = _non_empty_string(raw.get("name"))
    arguments = raw.get("arguments")

    if tool_id is not None:
        normalized["id"] = tool_id
    if tool_name is not None:
        normalized["name"] = tool_name
    if isinstance(arguments, str):
        normalized["arguments"] = arguments
    elif arguments is not None:
        normalized["arguments"] = json.dumps(arguments, ensure_ascii=False)
    else:
        normalized["arguments"] = "{}"
    return normalized


def _normalize_tool_result(
    *,
    text: str,
    tool_name: str | None = None,
    tool_id: str | None = None,
) -> dict[str, Any]:
    message: dict[str, Any] = {
        "type": "tool_result",
        "text": text,
    }
    if tool_name:
        message["toolName"] = tool_name
    if tool_id:
        message["toolId"] = tool_id
    return message


def _normalize_request_message(item: object) -> dict[str, Any] | None:
    if not isinstance(item, dict):
        return None

    role = item.get("role")
    if not isinstance(role, str):
        return None

    if role == "system":
        return {"type": "system", "text": _content_to_text(item.get("content", ""))}

    if role == "user":
        return {"type": "user", "text": _content_to_text(item.get("content", ""))}

    if role == "assistant":
        message: dict[str, Any] = {
            "type": "assistant",
            "text": _content_to_text(item.get("content", "")),
        }
        reasoning = _non_empty_string(item.get("reasoningContent"))
        if reasoning is not None:
            message["reasoning"] = reasoning

        raw_tool_requests: list[dict[str, Any]] = []
        function_call = item.get("functionCall") or item.get("function_call")
        if isinstance(function_call, dict):
            raw_tool_requests.append(
                {
                    "name": function_call.get("name"),
                    "arguments": function_call.get("arguments"),
                }
            )

        tool_calls = item.get("toolCalls") or item.get("tool_calls")
        if isinstance(tool_calls, list):
            for tool_call in tool_calls:
                if not isinstance(tool_call, dict):
                    continue
                function = tool_call.get("function")
                if isinstance(function, dict):
                    raw_tool_requests.append(
                        {
                            "id": tool_call.get("id"),
                            "name": function.get("name"),
                            "arguments": function.get("arguments"),
                        }
                    )

        tool_execution_requests = [
            _normalize_tool_execution_request(request)
            for request in raw_tool_requests
        ]
        if tool_execution_requests:
            message["toolExecutionRequests"] = tool_execution_requests
        return message

    if role in {"tool", "function"}:
        tool_name = _non_empty_string(item.get("name")) or _non_empty_string(item.get("toolName"))
        tool_id = _non_empty_string(item.get("tool_call_id")) or _non_empty_string(
            item.get("toolCallId")
        )
        text = _content_to_text(item.get("content", ""))
        return _normalize_tool_result(text=text, tool_name=tool_name, tool_id=tool_id)

    return None


def _extract_request_messages(request_json_text: str) -> list[dict[str, Any]]:
    payload = json.loads(request_json_text)
    raw_messages = payload.get("messages")
    if not isinstance(raw_messages, list):
        raise ValueError("request JSON is missing a valid 'messages' array")

    messages: list[dict[str, Any]] = []
    for item in raw_messages:
        normalized = _normalize_request_message(item)
        if normalized is not None:
            messages.append(normalized)
    return messages


def _extract_response_sections(log_text: str, turn: int) -> dict[str, str]:
    lines = log_text.splitlines()
    sections: dict[str, list[str]] = {}
    current_section: str | None = None

    for line in lines:
        if line.startswith("## ") and line[3:].strip() in _RESPONSE_SECTION_NAMES:
            current_section = line[3:].strip()
            sections.setdefault(current_section, [])
            continue
        if current_section is not None:
            sections[current_section].append(line)

    if "text" not in sections:
        raise ValueError(f"missing '## text' section in extracted log for turn {turn:03d}")

    return {name: "\n".join(body).rstrip("\n") for name, body in sections.items()}


def _parse_json_section(raw: str, label: str) -> Any:
    if not raw.strip():
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON in '{label}' section: {exc}") from exc


def _extract_tools_jsonl_messages(tools_jsonl_texts: list[str]) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for text in tools_jsonl_texts:
        for line in text.splitlines():
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON in tools.jsonl line: {exc}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"invalid tools.jsonl record: {record!r}")
            result_text = record.get("resultText")
            if not isinstance(result_text, str):
                raise ValueError(f"tools.jsonl record missing resultText: {record!r}")
            messages.append(
                _normalize_tool_result(
                    text=result_text,
                    tool_name=_non_empty_string(record.get("toolName")),
                    tool_id=_non_empty_string(record.get("toolId"))
                    or _non_empty_string(record.get("toolCallId")),
                )
            )
    return messages


def extract_turn_messages(worktree: Path, turn: int) -> dict[str, Any]:
    if turn < 1:
        raise ValueError("turn must be a positive integer")

    location = _resolve_worktree_path(worktree)
    if not location.is_dir() and location.suffix != ".zip":
        raise ValueError(f"unsupported worktree location: {location}")

    request_json_text = _read_turn_request_json(location, turn)
    response_log_text = _read_turn_log(location, turn)
    tools_jsonl_texts = _read_turn_tools_jsonl(location, turn)

    messages = _extract_request_messages(request_json_text)
    response_sections = _extract_response_sections(response_log_text, turn)

    final_assistant: dict[str, Any] = {
        "type": "assistant",
        "text": response_sections["text"],
    }
    reasoning = response_sections.get("reasoningContent")
    if reasoning:
        final_assistant["reasoning"] = reasoning

    tool_execution_requests = _parse_json_section(
        response_sections.get("toolExecutionRequests", ""),
        "toolExecutionRequests",
    )
    if tool_execution_requests:
        if not isinstance(tool_execution_requests, list):
            raise ValueError("toolExecutionRequests section must contain a JSON array")
        final_assistant["toolExecutionRequests"] = [
            _normalize_tool_execution_request(request) for request in tool_execution_requests
        ]

    metadata = _parse_json_section(response_sections.get("metadata", ""), "metadata")
    if metadata is not None and not isinstance(metadata, dict):
        raise ValueError("metadata section must contain a JSON object")

    messages.append(final_assistant)
    messages.extend(_extract_tools_jsonl_messages(tools_jsonl_texts))

    for index, message in enumerate(messages, start=1):
        message["sequence"] = index

    result: dict[str, Any] = {
        "worktree": str(location),
        "turn": turn,
        "messages": messages,
    }
    if metadata:
        result["metadata"] = metadata
    return result


def _format_labeled_json(label: str, value: Any) -> list[str]:
    rendered = json.dumps(value, ensure_ascii=False, indent=2).splitlines()
    return [f"[{label}]", *rendered]


def _format_message(message: dict[str, Any], *, full: bool) -> str:
    sequence = message["sequence"]
    message_type = message["type"]
    lines = str(message.get("text", "")).splitlines()
    if not full and len(lines) > 3:
        lines = [*lines[:3], "..."]

    block = [f"<message type={message_type} sequence={sequence}>"]
    if message_type == "tool_result":
        tool_name = message.get("toolName")
        tool_id = message.get("toolId")
        if tool_name is not None:
            block.append(f"toolName: {tool_name}")
        if tool_id is not None:
            block.append(f"toolId: {tool_id}")

    block.extend(lines)
    if "reasoning" in message:
        block.extend(["[reasoning]", *str(message["reasoning"]).splitlines()])
    if "toolExecutionRequests" in message:
        block.extend(
            _format_labeled_json("toolExecutionRequests", message["toolExecutionRequests"])
        )
    block.append("</message>")
    return "\n".join(block)


def _render_tagged(payload: dict[str, Any], *, selected_sequence: int | None = None) -> str:
    messages = payload["messages"]
    rendered = []
    for message in messages:
        if selected_sequence is not None and message["sequence"] != selected_sequence:
            continue
        rendered.append(_format_message(message, full=selected_sequence is not None))

    if selected_sequence is None and "metadata" in payload:
        rendered.extend(
            ["## metadata", json.dumps(payload["metadata"], ensure_ascii=False, indent=2)]
        )
    return "\n".join(rendered)


def main(argv: list[str] | None = None, stdout: object | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    output_stream = stdout if stdout is not None else sys.stdout

    try:
        payload = extract_turn_messages(args.worktree, args.turn)
    except (FileNotFoundError, ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        parser.error(str(exc))

    messages = payload["messages"]
    if args.message is not None:
        if args.message < 1 or args.message > len(messages):
            parser.error(f"--message must be between 1 and {len(messages)}")
        payload = {**payload, "messages": [messages[args.message - 1]]}

    if args.tagged:
        print(
            _render_tagged(payload, selected_sequence=args.message),
            file=output_stream,
        )
    else:
        print(
            json.dumps(payload, ensure_ascii=False, indent=2),
            file=output_stream,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
