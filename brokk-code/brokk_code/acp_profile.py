from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ClientProfile:
    is_zed: bool = False
    supports_terminal: bool = False


def resolve_client_profile(client_capabilities: Any, client_info: Any) -> ClientProfile:
    client_name = ""
    if hasattr(client_info, "name"):
        client_name = str(client_info.name).lower()
    elif isinstance(client_info, dict):
        client_name = str(client_info.get("name", "")).lower()

    supports_terminal = False
    if hasattr(client_capabilities, "terminal"):
        supports_terminal = bool(client_capabilities.terminal)
    elif isinstance(client_capabilities, dict):
        supports_terminal = bool(client_capabilities.get("terminal"))

    return ClientProfile(is_zed="zed" in client_name, supports_terminal=supports_terminal)
