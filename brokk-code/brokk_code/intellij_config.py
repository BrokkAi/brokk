import json
from pathlib import Path

from brokk_code.rust_acp_install import RustAcpPaths
from brokk_code.zed_config import (
    ExistingBrokkCodeEntryError,
    atomic_write_settings,
    loads_json_or_jsonc,
)


def configure_intellij_acp_settings(
    *,
    force: bool = False,
    settings_path: Path | None = None,
    uvx_command: str = "uvx",
    native: bool = False,
    rust_paths: RustAcpPaths | None = None,
) -> Path:
    """Configures IntelliJ for ACP mode."""
    path = settings_path or Path.home() / ".jetbrains" / "acp.json"

    if path.exists():
        raw_text = path.read_text(encoding="utf-8")
        try:
            settings = loads_json_or_jsonc(raw_text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Could not parse {path} as JSON/JSONC: {exc}") from exc
        if not isinstance(settings, dict):
            raise ValueError(f"Expected a JSON object in {path}")
    else:
        settings = {}

    for key in ["default_mcp_settings", "agent_servers"]:
        if key not in settings:
            settings[key] = {}
        elif not isinstance(settings[key], dict):
            raise ValueError(f"Expected '{key}' to be a JSON object")

    agent_servers = settings["agent_servers"]

    if "Brokk Code" in agent_servers and not force:
        raise ExistingBrokkCodeEntryError(
            "agent_servers['Brokk Code'] already exists; use --force to overwrite it"
        )

    if rust_paths is not None:
        rust_args: list[str] = [
            "--default-model",
            rust_paths.model,
            "--bifrost-binary",
            str(rust_paths.bifrost),
        ]
        if rust_paths.endpoint_url:
            rust_args += ["--endpoint-url", rust_paths.endpoint_url]
        if rust_paths.api_key:
            rust_args += ["--api-key", rust_paths.api_key]
        agent_servers["Brokk Code"] = {
            "command": str(rust_paths.brokk_acp),
            "args": rust_args,
            "env": {},
        }
    else:
        agent_servers["Brokk Code"] = {
            "command": uvx_command,
            "args": ["brokk", "acp-native" if native else "acp"],
            "env": {},
        }

    path.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_settings(path, settings)
    return path
