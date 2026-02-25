import json
import re
import tempfile
import tomllib
from pathlib import Path
from typing import Any

from brokk_code.zed_config import ExistingBrokkCodeEntryError, atomic_write_settings

_IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_SERVER_NAME = "brokk"


def _toml_key(key: str) -> str:
    if _IDENTIFIER_RE.match(key):
        return key
    return json.dumps(key)


def _toml_value(value: Any) -> str:
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return str(value)
    if isinstance(value, list):
        return f"[{', '.join(_toml_value(item) for item in value)}]"
    if isinstance(value, dict):
        return (
            "{ " + ", ".join(f"{_toml_key(k)} = {_toml_value(v)}" for k, v in value.items()) + " }"
        )
    if value is None:
        return "null"
    raise TypeError(f"Unsupported TOML value type: {type(value)!r}")


def _serialize_toml(data: dict[str, Any]) -> str:
    lines: list[str] = []

    for key, value in data.items():
        if not isinstance(value, dict):
            lines.append(f"{_toml_key(key)} = {_toml_value(value)}")

    for key, value in data.items():
        if not isinstance(value, dict):
            continue

        if key == "mcp_servers":
            for server_name, server_config in value.items():
                lines.append("")
                if not isinstance(server_config, dict):
                    raise ValueError(
                        "Expected every '[mcp_servers.<name>]' entry to be a TOML table"
                    )
                lines.append(f"[{_toml_key(key)}.{_toml_key(server_name)}]")
                for cfg_key, cfg_val in server_config.items():
                    lines.append(f"{_toml_key(cfg_key)} = {_toml_value(cfg_val)}")
            continue

        if value:
            lines.append("")
            lines.append(f"[{_toml_key(key)}]")
            for cfg_key, cfg_val in value.items():
                lines.append(f"{_toml_key(cfg_key)} = {_toml_value(cfg_val)}")
        else:
            lines.append("")
            lines.append(f"[{_toml_key(key)}]")

    return "\n".join(lines) + "\n"


def _atomic_write_toml(path: Path, text: str) -> None:
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


def _brokk_mcp_config() -> dict[str, Any]:
    return {
        "command": "jbang",
        "args": [
            "--java",
            "21",
            "-R",
            "-Djava.awt.headless=true -Dapple.awt.UIElement=true",
            "--enable-native-access=ALL-UNNAMED",
            "--main",
            "ai.brokk.mcpserver.BrokkExternalMcpServer",
            "brokk-headless@brokkai/brokk-releases",
        ],
        "type": "stdio",
    }


def configure_claude_code_mcp_settings(
    *, force: bool = False, settings_path: Path | None = None
) -> Path:
    path = settings_path or Path.home() / ".claude" / "settings.json"
    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        try:
            settings = json.loads(raw_text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Could not parse {path} as JSON: {exc}") from exc
        if not isinstance(settings, dict):
            raise ValueError(f"Expected a JSON object in {path}")
    else:
        settings = {}

    mcp_servers = settings.get("mcpServers")
    if mcp_servers is None:
        mcp_servers = {}
        settings["mcpServers"] = mcp_servers
    elif not isinstance(mcp_servers, dict):
        raise ValueError("Expected 'mcpServers' to be a JSON object")

    if _SERVER_NAME in mcp_servers and not force:
        raise ExistingBrokkCodeEntryError(
            f"mcpServers['{_SERVER_NAME}'] already exists; use --force to overwrite it"
        )

    server_config = _brokk_mcp_config() | {
        "env": {
            "MCP_TIMEOUT": "60000",
            "MCP_TOOL_TIMEOUT": "300000",
        },
    }
    mcp_servers[_SERVER_NAME] = server_config
    path.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_settings(path, settings)
    return path


def configure_codex_mcp_settings(*, force: bool = False, settings_path: Path | None = None) -> Path:
    path = settings_path or Path.home() / ".codex" / "config.toml"
    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        if raw_text.strip():
            try:
                settings = tomllib.loads(raw_text)
            except ValueError as exc:
                raise ValueError(f"Could not parse {path} as TOML: {exc}") from exc
        else:
            settings = {}
        if not isinstance(settings, dict):
            raise ValueError(f"Expected a TOML object in {path}")
    else:
        settings = {}

    mcp_servers = settings.get("mcp_servers")
    if mcp_servers is None:
        mcp_servers = {}
        settings["mcp_servers"] = mcp_servers
    elif not isinstance(mcp_servers, dict):
        raise ValueError("Expected 'mcp_servers' to be a TOML table")

    if _SERVER_NAME in mcp_servers and not force:
        raise ExistingBrokkCodeEntryError(
            f"mcp_servers['{_SERVER_NAME}'] already exists; use --force to overwrite it"
        )

    server_config = _brokk_mcp_config() | {
        "startup_timeout_sec": 60.0,
        "tool_timeout_sec": 300.0,
    }
    mcp_servers[_SERVER_NAME] = server_config

    path.parent.mkdir(parents=True, exist_ok=True)
    toml_text = _serialize_toml(settings)
    _atomic_write_toml(path, toml_text)
    return path
