import sys
from types import ModuleType
from typing import Any

import brokk_code.__main__ as main_module


def test_main_defaults_to_tui(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")

    class FakeApp:
        def __init__(self, **kwargs: Any):
            captured["kwargs"] = kwargs

        def run(self) -> None:
            captured["ran"] = True

    fake_app_module.BrokkApp = FakeApp
    monkeypatch.setitem(sys.modules, "brokk_code.app", fake_app_module)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk-code", "--workspace", str(tmp_path), "--session", "session-1"],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["session_id"] == "session-1"


def test_main_acp_routes_to_server(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    fake_acp_module = ModuleType("brokk_code.acp_server")

    async def fake_run_acp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    fake_acp_module.run_acp_server = fake_run_acp_server
    monkeypatch.setitem(sys.modules, "brokk_code.acp_server", fake_acp_module)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk-code", "acp", "--workspace", str(tmp_path), "--executor-stable"],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["executor_snapshot"] is False
    assert captured["kwargs"]["ide"] == "intellij"


def test_main_acp_routes_to_server_with_ide(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    fake_acp_module = ModuleType("brokk_code.acp_server")

    async def fake_run_acp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    fake_acp_module.run_acp_server = fake_run_acp_server
    monkeypatch.setitem(sys.modules, "brokk_code.acp_server", fake_acp_module)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk-code", "acp", "--workspace", str(tmp_path), "--ide", "zed"],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["ide"] == "zed"
