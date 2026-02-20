import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any


class ExistingBrokkCodeEntryError(Exception):
    """Raised when an IDE configuration already has a Brokk Code agent server entry."""


def _split_leading_json_prefix(text: str) -> tuple[str, str]:
    in_string = False
    escape = False
    in_line_comment = False
    in_block_comment = False

    idx = 0
    while idx < len(text):
        ch = text[idx]
        nxt = text[idx + 1] if idx + 1 < len(text) else ""

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            idx += 1
            continue

        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                idx += 2
                continue
            idx += 1
            continue

        if in_string:
            if escape:
                escape = False
                idx += 1
                continue
            if ch == "\\":
                escape = True
                idx += 1
                continue
            if ch == '"':
                in_string = False
            idx += 1
            continue

        if ch == '"' and not in_string:
            in_string = True
            idx += 1
            continue

        if ch == "/" and nxt == "/":
            in_line_comment = True
            idx += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            idx += 2
            continue

        if ch in "{[":
            return text[:idx], text[idx:]

        idx += 1

    return text, ""


def _strip_jsonc_comments(text: str) -> str:
    out: list[str] = []
    in_string = False
    escape = False
    in_line_comment = False
    in_block_comment = False

    idx = 0
    while idx < len(text):
        ch = text[idx]
        nxt = text[idx + 1] if idx + 1 < len(text) else ""

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
                out.append("\n")
            idx += 1
            continue

        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                idx += 2
                continue
            if ch == "\n":
                out.append("\n")
            idx += 1
            continue

        if in_string:
            out.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            idx += 1
            continue

        if ch == '"':
            in_string = True
            out.append(ch)
            idx += 1
            continue

        if ch == "/" and nxt == "/":
            in_line_comment = True
            idx += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            idx += 2
            continue

        out.append(ch)
        idx += 1

    return "".join(out)


def _remove_trailing_commas(text: str) -> str:
    out: list[str] = []
    in_string = False
    escape = False

    idx = 0
    while idx < len(text):
        ch = text[idx]

        if in_string:
            out.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            idx += 1
            continue

        if ch == '"':
            in_string = True
            out.append(ch)
            idx += 1
            continue

        if ch == ",":
            lookahead = idx + 1
            while lookahead < len(text) and text[lookahead].isspace():
                lookahead += 1
            if lookahead < len(text) and text[lookahead] in "}]":
                idx += 1
                continue

        out.append(ch)
        idx += 1

    return "".join(out)


def loads_json_or_jsonc(text: str) -> Any:
    """Loads JSON or JSONC (JSON with comments and trailing commas)."""
    stripped = text.strip()
    if not stripped:
        return {}

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        cleaned = _remove_trailing_commas(_strip_jsonc_comments(text))
        return json.loads(cleaned)


def _brokk_code_agent_server_config() -> dict[str, Any]:
    return {
        "favorite_config_option_values": {
            "reasoning": ["medium"],
            "mode": ["LUTZ"],
            "model": ["gpt-5.2"],
        },
        "type": "custom",
        "command": "brokk-code",
        "args": ["acp", "--ide", "zed"],
        "env": {},
    }


def atomic_write_settings(path: Path, settings: dict[str, Any]) -> None:
    """Atomically writes settings to a JSON file."""
    _atomic_write_settings_text(path, f"{json.dumps(settings, indent=2)}\n")


def _atomic_write_settings_text(path: Path, text: str) -> None:
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temp_file:
        temp_file.write(text)
        temp_path = Path(temp_file.name)

    if path.exists():
        temp_path.chmod(path.stat().st_mode)

    temp_path.replace(path)


def _atomic_write_zed_settings(path: Path, settings: dict[str, Any], *, prefix: str) -> None:
    serialized = f"{json.dumps(settings, indent=2)}\n"
    _atomic_write_settings_text(path, f"{prefix}{serialized}" if prefix else serialized)


def _default_zed_settings_path() -> Path:
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        if appdata:
            return Path(appdata) / "Zed" / "settings.json"
        return Path.home() / "AppData" / "Roaming" / "Zed" / "settings.json"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Zed" / "settings.json"
    return Path.home() / ".config" / "zed" / "settings.json"


def configure_zed_acp_settings(*, force: bool = False, settings_path: Path | None = None) -> Path:
    path = settings_path or _default_zed_settings_path()
    prefix = ""

    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        prefix, json_text = _split_leading_json_prefix(raw_text)
        if prefix and not prefix.endswith("\n"):
            prefix = f"{prefix}\n"
        try:
            parsed_json = loads_json_or_jsonc(json_text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Could not parse {path} as JSON/JSONC: {exc}") from exc
        if not isinstance(parsed_json, dict):
            raise ValueError(f"Expected a JSON object in {path}")
        settings = parsed_json
    else:
        settings = {}

    agent_servers = settings.get("agent_servers")
    if agent_servers is None:
        agent_servers = {}
        settings["agent_servers"] = agent_servers

    if not isinstance(agent_servers, dict):
        raise ValueError("Expected 'agent_servers' to be a JSON object")

    if "Brokk Code" in agent_servers and not force:
        raise ExistingBrokkCodeEntryError(
            "agent_servers['Brokk Code'] already exists; use --force to overwrite it"
        )

    agent_servers["Brokk Code"] = _brokk_code_agent_server_config()

    path.parent.mkdir(parents=True, exist_ok=True)
    _atomic_write_zed_settings(path, settings, prefix=prefix)
    return path
