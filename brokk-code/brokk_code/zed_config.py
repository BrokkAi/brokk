import json
from pathlib import Path
from typing import Any


class ExistingBrokkCodeEntryError(Exception):
    """Raised when Zed already has a Brokk Code agent server entry."""


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


def configure_zed_acp_settings(*, force: bool = False, settings_path: Path | None = None) -> Path:
    path = settings_path or Path.home() / ".config" / "zed" / "settings.json"

    if path.exists():
        parsed_json = json.loads(path.read_text(encoding="utf-8"))
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
    path.write_text(f"{json.dumps(settings, indent=2)}\n", encoding="utf-8")
    return path
