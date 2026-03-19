import os
from pathlib import Path

import pytest

from brokk_code import mcp_launcher


def test_run_mcp_server_execs_direct_java_with_explicit_jar(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        captured["env"] = env
        raise RuntimeError("stop")

    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version=None,
        )

    command = captured["command"]
    assert captured["cwd"] == tmp_path.resolve()
    assert captured["binary"] == "java"
    assert isinstance(command, list)
    assert "--enable-native-access=ALL-UNNAMED" in command
    assert str(dummy_jar) in command
    assert "ai.brokk.mcpserver.BrokkExternalMcpServer" in command


def test_run_mcp_server_prefers_local_dev_jar(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}
    repo_root = tmp_path / "repo"
    nested_workspace = repo_root / "src" / "feature"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    nested_workspace.mkdir(parents=True)
    (repo_root / ".git").mkdir()
    (repo_root / "gradlew").write_text("")
    local_jar = libs_dir / "brokk-1.2.3.jar"
    local_jar.write_text("dummy")

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=nested_workspace,
            jar_path=None,
            executor_version=None,
        )

    command = captured["command"]
    assert captured["cwd"] == repo_root.resolve()
    assert captured["binary"] == "java"
    assert isinstance(command, list)
    assert str(local_jar) in command


def test_run_mcp_server_falls_back_to_versioned_jbang(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _workspace_dir: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version="0.99.0",
        )

    command = captured["command"]
    assert captured["cwd"] == tmp_path.resolve()
    assert captured["binary"] == "/usr/local/bin/jbang"
    assert isinstance(command, list)
    assert "--main" in command
    assert "ai.brokk.mcpserver.BrokkExternalMcpServer" in command
    assert any("brokk-0.99.0.jar" in arg for arg in command)


def test_run_mcp_server_reports_missing_runtime(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _workspace_dir: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "missing-runtime")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", lambda *_args: (_ for _ in ()).throw(FileNotFoundError()))

    with pytest.raises(SystemExit) as exc:
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version=None,
        )

    assert exc.value.code == 1
    assert "Unable to launch MCP runtime" in capsys.readouterr().err
