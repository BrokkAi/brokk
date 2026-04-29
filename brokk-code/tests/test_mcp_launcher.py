import os
import sys
from pathlib import Path

import pytest

from brokk_code import mcp_launcher
from brokk_code.runtime_utils import find_dev_jar


def test_resolve_mcp_workspace_dir_uses_git_toplevel(monkeypatch, tmp_path) -> None:
    repo_root = tmp_path / "repo"
    nested_dir = repo_root / "src" / "feature"
    nested_dir.mkdir(parents=True)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: repo_root.resolve())

    resolved = mcp_launcher.resolve_mcp_workspace_dir(nested_dir)

    assert resolved == repo_root.resolve()


def test_resolve_mcp_workspace_dir_keeps_non_git_dir(monkeypatch, tmp_path) -> None:
    non_project_dir = tmp_path / "scratch"
    non_project_dir.mkdir()
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    resolved = mcp_launcher.resolve_mcp_workspace_dir(non_project_dir)

    assert resolved == non_project_dir.resolve()


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

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

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

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: repo_root.resolve())

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

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="app": None)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
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


def test_run_mcp_server_falls_back_to_bundled_jbang_version(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="app": None)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version=None,
        )

    command = captured["command"]
    assert captured["cwd"] == tmp_path.resolve()
    assert captured["binary"] == "/usr/local/bin/jbang"
    assert isinstance(command, list)
    assert "--main" in command
    assert "ai.brokk.mcpserver.BrokkExternalMcpServer" in command
    assert any(f"brokk-{mcp_launcher.BUNDLED_EXECUTOR_VERSION}.jar" in arg for arg in command)


def test_run_mcp_server_reports_missing_runtime(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="app": None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "missing-runtime")
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
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


def test_run_mcp_server_appends_passthrough_args_with_separator_for_jbang(
    monkeypatch, tmp_path
) -> None:
    """Verify JBang launches include '--' before passthrough args."""
    captured: dict[str, object] = {}

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="app": None)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version=None,
            passthrough_args=["--help", "--verbose"],
        )

    command = captured["command"]
    assert isinstance(command, list)
    assert command[0] == "/usr/local/bin/jbang"
    # For JBang, we expect -- before passthrough args
    assert command[-3:] == ["--", "--help", "--verbose"]


def test_run_mcp_server_appends_passthrough_args_directly_for_java(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version=None,
            passthrough_args=["--help"],
        )

    command = captured["command"]
    assert isinstance(command, list)
    assert command[0] == "java"
    # For direct java, we expect no -- separator
    assert command[-1] == "--help"
    assert "--" not in command


# ---------------------------------------------------------------------------
# mcp-core launcher tests
# ---------------------------------------------------------------------------


def test_find_dev_jar_core_finds_jar_in_brokk_core_libs(tmp_path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "gradlew").write_text("")
    libs_dir = repo / "brokk-core" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    jar = libs_dir / "brokk-core-1.0.0.jar"
    jar.write_text("dummy")

    result = find_dev_jar(repo, subproject="brokk-core")

    assert result == jar


def test_find_dev_jar_core_walks_up_to_find_gradlew(tmp_path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "gradlew").write_text("")
    libs_dir = repo / "brokk-core" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    jar = libs_dir / "brokk-core-2.0.0.jar"
    jar.write_text("dummy")
    nested = repo / "sub" / "deep"
    nested.mkdir(parents=True)

    result = find_dev_jar(nested, subproject="brokk-core")

    assert result == jar


def test_find_dev_jar_core_picks_newest_jar(tmp_path) -> None:
    import time

    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "gradlew").write_text("")
    libs_dir = repo / "brokk-core" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    old_jar = libs_dir / "brokk-core-1.0.0.jar"
    old_jar.write_text("old")
    time.sleep(0.05)
    new_jar = libs_dir / "brokk-core-2.0.0.jar"
    new_jar.write_text("new")

    result = find_dev_jar(repo, subproject="brokk-core")

    assert result == new_jar


def test_find_dev_jar_core_excludes_classifier_jars(tmp_path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "gradlew").write_text("")
    libs_dir = repo / "brokk-core" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (libs_dir / "brokk-core-1.0.0-sources.jar").write_text("src")
    (libs_dir / "brokk-core-1.0.0-javadoc.jar").write_text("doc")
    (libs_dir / "brokk-core-1.0.0-plain.jar").write_text("plain")

    result = find_dev_jar(repo, subproject="brokk-core")

    assert result is None


def test_find_dev_jar_core_returns_none_when_no_gradlew(tmp_path) -> None:
    result = find_dev_jar(tmp_path, subproject="brokk-core")

    assert result is None


def test_find_dev_jar_core_finds_gradlew_bat(tmp_path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "gradlew.bat").write_text("")
    libs_dir = repo / "brokk-core" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    jar = libs_dir / "brokk-core-1.0.0.jar"
    jar.write_text("dummy")

    result = find_dev_jar(repo, subproject="brokk-core")

    assert result == jar


def test_build_direct_mcp_core_command(tmp_path) -> None:
    jar = tmp_path / "brokk-core.jar"

    command = mcp_launcher._build_direct_mcp_command(mcp_launcher._MCP_CORE_SERVER_MAIN_CLASS, jar)

    assert command[0] == "java"
    assert "-cp" in command
    assert str(jar) in command
    assert "ai.brokk.mcpserver.BrokkCoreMcpServer" in command
    assert "--enable-native-access=ALL-UNNAMED" in command


def test_build_jbang_mcp_core_command_with_version() -> None:
    command = mcp_launcher._build_jbang_mcp_command(
        mcp_launcher._MCP_CORE_SERVER_MAIN_CLASS,
        "brokk-core",
        jbang_binary="/usr/bin/jbang",
        executor_version="1.2.3",
    )

    assert command[0] == "/usr/bin/jbang"
    assert "--main" in command
    assert "ai.brokk.mcpserver.BrokkCoreMcpServer" in command
    assert any("brokk-core-1.2.3.jar" in arg for arg in command)


def test_build_jbang_mcp_core_command_defaults_to_bundled_version() -> None:
    command = mcp_launcher._build_jbang_mcp_command(
        mcp_launcher._MCP_CORE_SERVER_MAIN_CLASS,
        "brokk-core",
        jbang_binary="jbang",
        executor_version=None,
    )

    assert any(f"brokk-core-{mcp_launcher.BUNDLED_EXECUTOR_VERSION}.jar" in arg for arg in command)


def test_run_mcp_core_server_execs_direct_java_with_explicit_jar(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk-core.jar"
    dummy_jar.write_text("dummy")

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_core_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version=None,
        )

    command = captured["command"]
    assert captured["binary"] == "java"
    assert str(dummy_jar) in command
    assert "ai.brokk.mcpserver.BrokkCoreMcpServer" in command


def test_run_mcp_core_server_falls_back_to_jbang(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="brokk-core": None)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_core_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version="0.99.0",
        )

    command = captured["command"]
    assert captured["binary"] == "/usr/local/bin/jbang"
    assert "ai.brokk.mcpserver.BrokkCoreMcpServer" in command
    assert any("brokk-core-0.99.0.jar" in arg for arg in command)


def test_run_mcp_core_server_passthrough_args_with_jbang_separator(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="brokk-core": None)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "jbang")

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_core_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version=None,
            passthrough_args=["--verbose"],
        )

    command = captured["command"]
    assert command[-2:] == ["--", "--verbose"]


def test_run_mcp_core_server_passthrough_args_direct_java_no_separator(
    monkeypatch, tmp_path
) -> None:
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk-core.jar"
    dummy_jar.write_text("dummy")

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_mcp_core_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version=None,
            passthrough_args=["--help"],
        )

    command = captured["command"]
    assert command[0] == "java"
    assert command[-1] == "--help"
    assert "--" not in command


def test_run_mcp_core_server_reports_missing_runtime(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", lambda _ws, subproject="brokk-core": None)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", lambda: "missing-runtime")
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(os, "execvpe", lambda *_args: (_ for _ in ()).throw(FileNotFoundError()))

    with pytest.raises(SystemExit) as exc:
        mcp_launcher.run_mcp_core_server(
            workspace_dir=tmp_path,
            jar_path=None,
            executor_version=None,
        )

    assert exc.value.code == 1
    assert "Unable to launch MCP core runtime" in capsys.readouterr().err


# ---------------------------------------------------------------------------
# ACP launcher: --jar must fully bypass jbang and the dev-jar fallback
# ---------------------------------------------------------------------------


def test_run_acp_server_with_jar_bypasses_jbang_and_dev_jar(monkeypatch, tmp_path) -> None:
    """`brokk acp --jar <path>` must launch `java -cp <path>` directly.

    With --jar provided, the launcher must not consult find_dev_jar, must not
    call ensure_jbang_ready, must not invoke a jbang binary, and must not
    construct any release-jar URL pointing at brokk-releases.
    """
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")

    def boom_dev_jar(*_args, **_kwargs) -> None:
        raise AssertionError("find_dev_jar must not be called when --jar is provided")

    def boom_jbang() -> str:
        raise AssertionError("ensure_jbang_ready must not be called when --jar is provided")

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(mcp_launcher, "find_dev_jar", boom_dev_jar)
    monkeypatch.setattr(mcp_launcher, "ensure_jbang_ready", boom_jbang)

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_acp_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version=None,
            passthrough_args=["--workspace-dir", str(tmp_path)],
        )

    command = captured["command"]
    assert isinstance(command, list)
    assert captured["binary"] == "java"
    assert command[0] == "java"
    assert "-cp" in command
    assert str(dummy_jar) in command
    assert "ai.brokk.acp.AcpServerMain" in command
    # No jbang token anywhere in the command (binary path or arg).
    assert not any("jbang" in part.lower() for part in command)
    # No release-jar URL constructed (that would point at the executor release).
    assert not any("brokk-releases" in part for part in command)
    # passthrough args appended without the JBang `--` separator.
    assert "--" not in command
    assert command[-2:] == ["--workspace-dir", str(tmp_path)]


def test_run_acp_server_passthrough_with_explicit_executor_version_still_uses_jar(
    monkeypatch, tmp_path
) -> None:
    """An explicit --executor-version must not override --jar back onto jbang."""
    captured: dict[str, object] = {}
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _path: None)
    monkeypatch.setattr(
        os,
        "execvpe",
        lambda binary, command, _env: (
            captured.update(binary=binary, command=command)
            or (_ for _ in ()).throw(RuntimeError("stop"))
        ),
    )
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)
    monkeypatch.setattr(
        mcp_launcher,
        "find_dev_jar",
        lambda *_a, **_k: (_ for _ in ()).throw(
            AssertionError("find_dev_jar must not be called when --jar is provided")
        ),
    )
    monkeypatch.setattr(
        mcp_launcher,
        "ensure_jbang_ready",
        lambda: (_ for _ in ()).throw(
            AssertionError("ensure_jbang_ready must not be called when --jar is provided")
        ),
    )

    with pytest.raises(RuntimeError, match="stop"):
        mcp_launcher.run_acp_server(
            workspace_dir=tmp_path,
            jar_path=dummy_jar,
            executor_version="9.9.9",
        )

    command = captured["command"]
    assert captured["binary"] == "java"
    assert str(dummy_jar) in command
    assert not any("9.9.9" in part for part in command)
    assert not any("brokk-releases" in part for part in command)
