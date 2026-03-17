from pathlib import Path

from brokk_code.runtime_paths import (
    resolve_go_executor_binary,
    resolve_go_mcp_binary,
)


def test_resolve_go_binaries_require_opt_in_for_auto_discovery(monkeypatch):
    monkeypatch.delenv("BROKK_USE_GO_RUNTIME", raising=False)
    monkeypatch.delenv("BROKK_GO_EXECUTOR", raising=False)
    monkeypatch.delenv("BROKK_GO_MCP", raising=False)
    monkeypatch.setattr("shutil.which", lambda name: "/tmp/fake-binary")

    assert resolve_go_executor_binary() is None
    assert resolve_go_mcp_binary() is None


def test_resolve_go_binaries_allow_auto_discovery_when_enabled(monkeypatch):
    monkeypatch.setenv("BROKK_USE_GO_RUNTIME", "1")
    monkeypatch.delenv("BROKK_GO_EXECUTOR", raising=False)
    monkeypatch.delenv("BROKK_GO_MCP", raising=False)
    monkeypatch.setattr("shutil.which", lambda name: "/tmp/fake-binary")

    assert resolve_go_executor_binary() == "/tmp/fake-binary"
    assert resolve_go_mcp_binary() == "/tmp/fake-binary"


def test_resolve_go_binaries_still_honor_explicit_env(monkeypatch, tmp_path):
    monkeypatch.delenv("BROKK_USE_GO_RUNTIME", raising=False)
    executor = tmp_path / "brokk-go-executor"
    mcp = tmp_path / "brokk-go-mcp"
    executor.write_text("binary")
    mcp.write_text("binary")
    monkeypatch.setenv("BROKK_GO_EXECUTOR", str(executor))
    monkeypatch.setenv("BROKK_GO_MCP", str(mcp))

    assert resolve_go_executor_binary() == str(executor)
    assert resolve_go_mcp_binary() == str(mcp)
