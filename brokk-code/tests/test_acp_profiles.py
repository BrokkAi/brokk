from typing import Any

from brokk_code.acp_server import resolve_client_profile


class SimpleNamespace:
    def __init__(self, **kwargs: Any):
        self.__dict__.update(kwargs)


def test_resolve_profile_zed_by_name() -> None:
    client_info = SimpleNamespace(name="Zed", version="0.170.0")
    profile = resolve_client_profile(client_capabilities={}, client_info=client_info)

    assert profile.is_zed is True
    assert profile.tool_call_titles_only is False


def test_resolve_profile_zed_by_dict_name() -> None:
    client_info = {"name": "zed-preview"}
    profile = resolve_client_profile(client_capabilities={}, client_info=client_info)
    assert profile.is_zed is True


def test_resolve_profile_unknown_falls_back_to_intellij_behavior() -> None:
    # Unknown client info should default to the conservative "intellij" style profile
    profile = resolve_client_profile(client_capabilities={}, client_info=None)

    assert profile.is_zed is False
    assert profile.tool_call_titles_only is True


def test_resolve_profile_respects_terminal_capability() -> None:
    caps = {"terminal": True}
    profile = resolve_client_profile(client_capabilities=caps, client_info={})
    assert profile.supports_terminal is True

    caps_obj = SimpleNamespace(terminal=False)
    profile = resolve_client_profile(client_capabilities=caps_obj, client_info={})
    assert profile.supports_terminal is False
