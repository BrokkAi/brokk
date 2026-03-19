import subprocess
import sys
from contextlib import contextmanager
from types import ModuleType, SimpleNamespace
from typing import Any
from unittest.mock import patch

import pytest

import brokk_code.__main__ as main_module
import brokk_code.git_utils as git_utils_module


def _stub_install_warmup(monkeypatch) -> None:
    monkeypatch.setattr(main_module, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")
    monkeypatch.setattr(main_module, "_run_install_prefetch", lambda _commands: None)
    monkeypatch.setattr(
        main_module,
        "wire_nvim_plugin_setup",
        lambda **_kwargs: SimpleNamespace(status="unsupported", path=None, detail=None),
    )


def test_main_version_subcommand_prints_version(monkeypatch, capsys) -> None:
    """Verify `brokk version` prints the package version and exits cleanly."""
    from brokk_code import __version__
    monkeypatch.setattr(sys, "argv", ["brokk", "version"])

    from brokk_code.__main__ import main
    main()

    captured = capsys.readouterr()
    assert f"brokk {__version__}" in captured.out


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
        [
            "brokk",
            "--workspace",
            str(tmp_path),
            "--session",
            "session-1",
            "--vendor",
            "OpenAI",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["session_id"] == "session-1"
    assert captured["kwargs"]["vendor"] == "OpenAI"


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
        [
            "brokk",
            "acp",
            "--workspace",
            str(tmp_path),
            "--executor-stable",
            "--vendor",
            "Gemini",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["executor_snapshot"] is False
    assert captured["kwargs"]["vendor"] == "Gemini"


def test_main_mcp_routes_to_launcher(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    jar_path = tmp_path / "brokk.jar"
    jar_path.write_text("dummy")

    def fake_run_mcp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    monkeypatch.setattr(main_module, "run_mcp_server", fake_run_mcp_server)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "mcp",
            "--workspace",
            str(tmp_path),
            "--jar",
            str(jar_path),
            "--executor-version",
            "0.99.0",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["jar_path"] == jar_path.resolve()
    assert captured["kwargs"]["executor_version"] == "0.99.0"
    # Ensure no residual ide parameter is passed from CLI to run_acp_server
    assert "ide" not in captured["kwargs"]


def test_main_acp_accepts_legacy_ide_flag_but_ignores_it(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {}
    fake_acp_module = ModuleType("brokk_code.acp_server")

    async def fake_run_acp_server(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs

    fake_acp_module.run_acp_server = fake_run_acp_server
    monkeypatch.setitem(sys.modules, "brokk_code.acp_server", fake_acp_module)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "acp",
            "--workspace",
            str(tmp_path),
            "--executor-stable",
            "--vendor",
            "Gemini",
            "--ide",
            "zed",
        ],
    )

    main_module.main()

    # Still routes correctly
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["executor_snapshot"] is False
    assert captured["kwargs"]["vendor"] == "Gemini"
    # Critically: ide is not forwarded to run_acp_server
    assert "ide" not in captured["kwargs"]


def test_main_acp_rejects_extra_positional(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "acp", "zed", "--workspace", str(tmp_path)],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 2


def test_main_install_zed_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "zed" / "settings.json"

    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed", "--force"])

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Zed ACP integration" in output


def test_main_install_zed_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_zed_invalid_json_exits_nonzero(monkeypatch) -> None:
    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        raise ValueError("Could not parse as JSON/JSONC")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_intellij_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / "intellij-config"

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij", "--force"])

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured IntelliJ ACP integration" in output


def test_main_install_nvim_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_nvim_codecompanion_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "nvim" / "lua" / "brokk" / "brokk_codecompanion.lua"

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_codecompanion_acp_settings",
        fake_configure_nvim_codecompanion_acp_settings,
    )
    monkeypatch.setattr(
        sys, "argv", ["brokk", "install", "nvim", "--plugin", "codecompanion", "--force"]
    )

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Neovim CodeCompanion ACP adapter" in output


def test_main_install_nvim_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_nvim_codecompanion_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_codecompanion_acp_settings",
        fake_configure_nvim_codecompanion_acp_settings,
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "nvim", "--plugin", "codecompanion"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_neovim_with_plugin_codecompanion_routes_to_installer(
    monkeypatch, tmp_path, capsys
) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_nvim_codecompanion_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "nvim" / "lua" / "brokk" / "brokk_codecompanion.lua"

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_codecompanion_acp_settings",
        fake_configure_nvim_codecompanion_acp_settings,
    )
    monkeypatch.setattr(
        sys, "argv", ["brokk", "install", "neovim", "--plugin", "codecompanion", "--force"]
    )

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Neovim CodeCompanion ACP adapter" in output


def test_main_install_neovim_with_plugin_avante_routes_to_installer(
    monkeypatch, tmp_path, capsys
) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_nvim_avante_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "nvim" / "lua" / "brokk" / "brokk_avante.lua"

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_avante_acp_settings",
        fake_configure_nvim_avante_acp_settings,
    )
    monkeypatch.setattr(
        sys, "argv", ["brokk", "install", "neovim", "--plugin", "avante", "--force"]
    )

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Neovim Avante ACP provider" in output


def test_main_install_plugin_with_non_neovim_target_exits_nonzero(monkeypatch) -> None:
    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "install", "zed", "--plugin", "avante"],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_neovim_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}

    def fake_configure_nvim_codecompanion_acp_settings(*, force: bool = False, settings_path=None):
        captured["force"] = force
        return tmp_path / ".config" / "nvim" / "lua" / "brokk" / "brokk_codecompanion.lua"

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_codecompanion_acp_settings",
        fake_configure_nvim_codecompanion_acp_settings,
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "neovim", "--force"])

    main_module.main()

    output = capsys.readouterr().out
    assert captured["force"] is True
    assert "Configured Neovim CodeCompanion ACP adapter" in output


def test_main_install_neovim_with_plugin_codecompanion_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_nvim_codecompanion_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "configure_nvim_codecompanion_acp_settings",
        fake_configure_nvim_codecompanion_acp_settings,
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "neovim", "--plugin", "codecompanion"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_verbose_prints_prefetch_command(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setattr(main_module, "resolve_jbang_binary", lambda: None)
    prefetch_invoked: dict[str, bool] = {"called": False}

    def fake_run_install_prefetch(_commands: list[tuple[str, list[str]]]) -> None:
        prefetch_invoked["called"] = True

    monkeypatch.setattr(main_module, "_run_install_prefetch", fake_run_install_prefetch)

    def fake_configure_zed_acp_settings(*, force: bool = False, settings_path=None):
        return tmp_path / ".config" / "zed" / "settings.json"

    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "zed", "-v"])

    main_module.main()

    output = capsys.readouterr().out.strip().splitlines()
    assert any("Configured Zed ACP integration" in line for line in output)
    assert any("jbang" in line for line in output)
    assert any("--main" in line for line in output)
    assert prefetch_invoked["called"] is False


def test_main_install_intellij_conflict_exits_nonzero(monkeypatch) -> None:
    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        raise main_module.ExistingBrokkCodeEntryError("exists")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_intellij_invalid_json_exits_nonzero(monkeypatch) -> None:
    def fake_configure_intellij_acp_settings(*, force: bool = False, settings_path=None):
        raise ValueError("Could not parse as JSON")

    _stub_install_warmup(monkeypatch)
    monkeypatch.setattr(
        main_module, "configure_intellij_acp_settings", fake_configure_intellij_acp_settings
    )
    monkeypatch.setattr(sys, "argv", ["brokk", "install", "intellij"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_install_mcp_routes_to_installer(monkeypatch, tmp_path, capsys) -> None:
    captured: dict[str, Any] = {}
    prefetched: dict[str, Any] = {}

    def fake_configure_claude_code_mcp_settings(
        *, force: bool = False, settings_path=None, jbang_path=None
    ):
        captured["claude_force"] = force
        return tmp_path / "claude.json"

    def fake_configure_codex_mcp_settings(
        *, force: bool = False, settings_path=None, jbang_path=None
    ):
        captured["codex_force"] = force
        return tmp_path / "codex.toml"

    def fake_run_install_prefetch(commands):
        prefetched["commands"] = commands

    monkeypatch.setattr(
        main_module,
        "configure_claude_code_mcp_settings",
        fake_configure_claude_code_mcp_settings,
    )
    monkeypatch.setattr(
        main_module,
        "configure_codex_mcp_settings",
        fake_configure_codex_mcp_settings,
    )
    monkeypatch.setattr(main_module, "ensure_jbang_ready", lambda: "/usr/local/bin/jbang")
    monkeypatch.setattr(main_module, "_run_install_prefetch", fake_run_install_prefetch)
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "install", "mcp", "--force"],
    )

    main_module.main()

    output = capsys.readouterr().out
    assert captured["claude_force"] is True
    assert captured["codex_force"] is True
    assert "Configured Claude Code MCP integration" in output
    assert "Configured Codex MCP integration" in output
    assert "MCP runtime" in str(prefetched["commands"][0][0])


def test_main_uses_git_repo_root_for_nested_workspace(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")
    repo_root = tmp_path / "repo"
    nested_workspace = repo_root / "src" / "feature"
    nested_workspace.mkdir(parents=True)
    (repo_root / ".git").mkdir()

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
        [
            "brokk",
            "--workspace",
            str(nested_workspace),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == repo_root.resolve()


def test_main_keeps_workspace_when_not_in_git_repo(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    fake_app_module = ModuleType("brokk_code.app")
    nested_workspace = tmp_path / "workspace" / "src"
    nested_workspace.mkdir(parents=True)

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
        [
            "brokk",
            "--workspace",
            str(nested_workspace),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == nested_workspace.resolve()


def test_main_resume_routes_correctly(monkeypatch, tmp_path) -> None:
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
        [
            "brokk",
            "resume",
            "session-xyz",
            "--workspace",
            str(tmp_path),
            "--vendor",
            "Anthropic",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["session_id"] == "session-xyz"
    assert captured["kwargs"]["resume_session"] is False
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["vendor"] == "Anthropic"


def test_main_sessions_routes_correctly(monkeypatch, tmp_path) -> None:
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
        [
            "brokk",
            "sessions",
            "--workspace",
            str(tmp_path),
            "--vendor",
            "OpenAI",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    # pick_session must be True for the sessions command
    assert captured["kwargs"]["pick_session"] is True
    # workspace_dir should be resolved
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    # vendor should be passed through
    assert captured["kwargs"]["vendor"] == "OpenAI"
    # sessions command overrides session_id and resume_session
    assert captured["kwargs"]["session_id"] is None
    assert captured["kwargs"]["resume_session"] is False


def test_main_sessions_rejects_positional_args(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "sessions", "unexpected-arg", "--workspace", str(tmp_path)],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 2


def test_main_issue_create_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    temp_workspace = tmp_path / "temp-create"
    temp_workspace.mkdir()

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    @contextmanager
    def fake_temp_checkout(**kwargs: Any):
        captured["checkout_kwargs"] = kwargs
        yield temp_workspace

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(main_module, "_temporary_issue_repo_checkout", fake_temp_checkout)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_123",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
            "--planner-model",
            "custom-model",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["checkout_kwargs"]["repo_owner"] == "acme"
    assert captured["checkout_kwargs"]["repo_name"] == "tools"
    assert captured["checkout_kwargs"]["github_token"] == "ghp_123"
    assert captured["checkout_kwargs"]["action_label"] == "Issue create"
    assert captured["kwargs"]["workspace_dir"] == temp_workspace
    assert captured["kwargs"]["task_input"] == "Broken build"
    assert captured["kwargs"]["mode"] == "ISSUE_WRITER"
    assert captured["kwargs"]["planner_model"] == "custom-model"
    assert captured["kwargs"]["tags"]["github_token"] == "ghp_123"
    assert captured["kwargs"]["tags"]["repo_owner"] == "acme"
    assert captured["kwargs"]["tags"]["repo_name"] == "tools"


def test_main_issue_create_missing_prompt_exits_nonzero(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "--github-token",
            "ghp_123",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_validation_missing_token(monkeypatch, capsys) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "issue", "create", "test", "--repo-owner", "o", "--repo-name", "r"],
    )
    # Ensure no env var leaks in
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1
    assert "Error: --github-token is required for issue create" in capsys.readouterr().err


def test_main_issue_solve_validation_invalid_owner(monkeypatch, capsys) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "1",
            "--github-token",
            "t",
            "--repo-owner",
            "invalid/owner",
            "--repo-name",
            "r",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1
    err = capsys.readouterr().err
    assert "Error: Invalid --repo-owner 'invalid/owner'" in err
    assert "^[A-Za-z0-9_.-]+$" in err


def test_main_issue_create_respects_env_github_token(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    temp_workspace = tmp_path / "temp-create-env"
    temp_workspace.mkdir()

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    @contextmanager
    def fake_temp_checkout(**kwargs: Any):
        captured["checkout_kwargs"] = kwargs
        yield temp_workspace

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(main_module, "_temporary_issue_repo_checkout", fake_temp_checkout)
    monkeypatch.setenv("GITHUB_TOKEN", "env-token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["tags"]["github_token"] == "env-token"
    assert captured["checkout_kwargs"]["github_token"] == "env-token"
    assert captured["kwargs"]["workspace_dir"] == temp_workspace
    assert captured["kwargs"]["planner_model"] == "gemini-3-flash-preview"
    assert captured["kwargs"]["planner_reasoning_level"] == "disable"
    assert captured["kwargs"]["verbose"] is False


def test_main_issue_create_verbose_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    temp_workspace = tmp_path / "temp-create-verbose"
    temp_workspace.mkdir()

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    @contextmanager
    def fake_temp_checkout(**kwargs: Any):
        captured["checkout_kwargs"] = kwargs
        yield temp_workspace

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(main_module, "_temporary_issue_repo_checkout", fake_temp_checkout)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "Broken build",
            "-v",
            "--github-token",
            "ghp_verbose",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["checkout_kwargs"]["action_label"] == "Issue create"
    assert captured["kwargs"]["workspace_dir"] == temp_workspace
    assert captured["kwargs"]["verbose"] is True


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_creates_session_before_wait_ready(
    mock_executor_class, tmp_path
) -> None:
    """Verifies that run_headless_job creates a session before polling for readiness.

    This ordering is required because the Java executor's /health/ready endpoint
    only returns 200 OK after a session has been created.
    """
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    async def mock_start():
        call_order.append("start")

    async def mock_create_session(name: str = ""):
        call_order.append("create_session")
        return "session-123"

    async def mock_wait_ready(timeout: float = 30.0):
        call_order.append("wait_ready")
        return True

    async def mock_submit_job(**kwargs):
        call_order.append("submit_job")
        return "job-456"

    async def mock_stream_events(job_id: str):
        # Yield a terminal state event to end the job
        yield {"type": "STATE_CHANGE", "state": "COMPLETED"}

    async def mock_stop():
        call_order.append("stop")

    mock_manager.start = AsyncMock(side_effect=mock_start)
    mock_manager.create_session = AsyncMock(side_effect=mock_create_session)
    mock_manager.wait_ready = AsyncMock(side_effect=mock_wait_ready)
    mock_manager.submit_job = AsyncMock(side_effect=mock_submit_job)
    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock(side_effect=mock_stop)

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Test task",
        planner_model="test-model",
        mode="LUTZ",
        tags={},
    )

    # Verify the critical ordering: create_session MUST come before wait_ready
    assert "start" in call_order
    assert "create_session" in call_order
    assert "wait_ready" in call_order
    assert "submit_job" in call_order

    start_idx = call_order.index("start")
    create_session_idx = call_order.index("create_session")
    wait_ready_idx = call_order.index("wait_ready")
    submit_job_idx = call_order.index("submit_job")

    # The critical assertion: session must be created before waiting for readiness
    assert start_idx < create_session_idx, "start() must be called before create_session()"
    assert create_session_idx < wait_ready_idx, (
        "create_session() must be called before wait_ready()"
    )
    assert wait_ready_idx < submit_job_idx, "wait_ready() must be called before submit_job()"


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_reports_failed_terminal_state(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "STATE_CHANGE", "state": "RUNNING"}
        yield {"type": "ERROR", "message": "GitHub API returned 403"}
        yield {"type": "STATE_CHANGE", "state": "FAILED"}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "Error event: GitHub API returned 403" in captured.err
    assert "ISSUE_WRITER job ended with state FAILED." in captured.err
    assert "Last error: GitHub API returned 403" in captured.err
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_reports_stage_on_submit_failure(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    from brokk_code.executor import ExecutorError

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(side_effect=ExecutorError("401 Unauthorized"))
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert (
        "Executor error during ISSUE_WRITER job (submitting job): 401 Unauthorized" in captured.err
    )
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_uses_nested_event_data_for_errors_and_quiet_notifications(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": ""}}
        yield {"type": "NOTIFICATION", "data": {"level": "WARN", "message": "rate limit near"}}
        yield {"type": "LLM_TOKEN", "data": {"token": "hello"}}
        yield {"type": "ERROR", "data": {"message": "executor boom"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "FAILED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "[INFO] None" not in captured.out
    assert "[WARN] rate limit near" in captured.out
    assert "hello" not in captured.out
    assert "Error event: executor boom" in captured.err
    assert "Unknown error event" not in captured.err


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_verbose_shows_full_event_output(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "planning"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "RUNNING"}}
        yield {"type": "LLM_TOKEN", "data": {"token": "hello"}}
        yield {"type": "COMMAND_RESULT", "data": {"command": "gh issue create", "output": "ok"}}
        yield {"type": "TOOL_OUTPUT", "data": {"text": "tool text"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
        verbose=True,
    )

    captured = capsys.readouterr()
    assert "[INFO] planning" in captured.out
    assert "Job state: RUNNING" in captured.out
    assert "hello" in captured.out
    assert "[COMMAND_RESULT]" in captured.out
    assert "[TOOL_OUTPUT]" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_exits_nonzero_on_error_event_without_failed_state(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "NOTIFICATION", "data": {"level": "INFO", "message": "Job started"}}
        yield {"type": "ERROR", "data": {"message": "parseIssueResponse: invalid JSON"}}
        # Stream ends without a terminal FAILED/CANCELLED state event.

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_headless_job(
            workspace_dir=tmp_path,
            task_input="Create issue",
            planner_model="test-model",
            mode="ISSUE_WRITER",
            tags={},
        )

    captured = capsys.readouterr()
    assert exc.value.code == 1
    assert "Job finished." not in captured.out
    assert "ISSUE_WRITER job ended with errors (last observed state: UNKNOWN)." in captured.err
    assert "Last error: parseIssueResponse: invalid JSON" in captured.err


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_suppressed_tokens(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {"type": "LLM_TOKEN", "data": {"token": "Created issue: "}}
        yield {
            "type": "LLM_TOKEN",
            "data": {"token": "https://github.com/brokkai/brokk/issues/123"},
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/123" in captured.out
    assert "Job submitted:" not in captured.out
    assert "Job finished." not in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_issue_writer_notification(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "NOTIFICATION",
            "data": {
                "level": "INFO",
                "message": "ISSUE_WRITER: issue created I_kwDOXYZ https://github.com/brokkai/brokk/issues/456",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/456" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_tool_output_result_text(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "TOOL_OUTPUT",
            "data": {
                "resultText": "Created: https://github.com/brokkai/brokk/issues/789",
                "name": "createGitHubIssue",
                "status": "SUCCESS",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/789" in captured.out


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_headless_job_prints_issue_created_link_from_structured_issue_created_event(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        assert job_id == "job-456"
        yield {
            "type": "ISSUE_CREATED",
            "data": {
                "issueId": "#987",
                "issueUrl": "https://github.com/brokkai/brokk/issues/987",
                "repoOwner": "brokkai",
                "repoName": "brokk",
            },
        }
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    await main_module.run_headless_job(
        workspace_dir=tmp_path,
        task_input="Create issue",
        planner_model="test-model",
        mode="ISSUE_WRITER",
        tags={},
    )

    captured = capsys.readouterr()
    assert "Issue created: https://github.com/brokkai/brokk/issues/987" in captured.out


def test_main_issue_solve_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}
    temp_workspace = tmp_path / "temp-copy"
    temp_workspace.mkdir()

    async def fake_run_headless_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    @contextmanager
    def fake_temp_workspace(**kwargs: Any):
        captured["temp_workspace_input"] = kwargs
        yield temp_workspace

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(main_module, "_temporary_issue_repo_checkout", fake_temp_workspace)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_solve",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
            "--skip-verification",
            "--max-issue-fix-attempts",
            "7",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["temp_workspace_input"]["repo_owner"] == "acme"
    assert captured["temp_workspace_input"]["repo_name"] == "tools"
    assert captured["temp_workspace_input"]["github_token"] == "ghp_solve"
    assert captured["temp_workspace_input"]["action_label"] == "Issue solve"
    assert captured["kwargs"]["mode"] == "ISSUE"
    assert captured["kwargs"]["workspace_dir"] == temp_workspace
    assert captured["kwargs"]["task_input"] == "Resolve GitHub Issue #123"
    assert captured["kwargs"]["tags"]["issue_number"] == "123"
    assert captured["kwargs"]["tags"]["github_token"] == "ghp_solve"
    assert captured["kwargs"]["skip_verification"] is True
    assert captured["kwargs"]["max_issue_fix_attempts"] == 7


def test_main_issue_solve_temp_workspace_cleanup_on_keyboard_interrupt(
    monkeypatch, tmp_path
) -> None:
    captured: dict[str, Any] = {"cleaned": False}
    temp_workspace = tmp_path / "temp-copy"
    temp_workspace.mkdir()

    async def fake_run_headless_job(**kwargs: Any) -> None:
        raise KeyboardInterrupt

    @contextmanager
    def fake_temp_workspace(**kwargs: Any):
        captured["temp_workspace_input"] = kwargs
        try:
            yield temp_workspace
        finally:
            captured["cleaned"] = True

    monkeypatch.setattr(main_module, "run_headless_job", fake_run_headless_job)
    monkeypatch.setattr(main_module, "_temporary_issue_repo_checkout", fake_temp_workspace)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_solve",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(KeyboardInterrupt):
        main_module.main()

    assert captured["temp_workspace_input"]["repo_owner"] == "acme"
    assert captured["cleaned"] is True


def test_main_issue_solve_missing_number_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--repo-owner",
            "acme",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_solve_missing_github_token_exits_nonzero(monkeypatch) -> None:
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_solve_missing_repo_owner_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_solve_missing_repo_name_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--repo-owner",
            "acme",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_solve_invalid_repo_owner_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--repo-owner",
            "invalid/owner",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_solve_invalid_repo_name_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "solve",
            "--issue-number",
            "123",
            "--repo-owner",
            "acme",
            "--repo-name",
            "invalid/repo",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_missing_github_token_exits_nonzero(monkeypatch) -> None:
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "new issue",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_missing_repo_owner_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "new issue",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_missing_repo_name_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "new issue",
            "--repo-owner",
            "acme",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_invalid_repo_owner_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "new issue",
            "--repo-owner",
            "invalid/owner",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_issue_create_invalid_repo_name_exits_nonzero(monkeypatch) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "issue",
            "create",
            "new issue",
            "--repo-owner",
            "acme",
            "--repo-name",
            "invalid/repo",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_pr_create_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_create(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_pr_create", fake_run_pr_create)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "create",
            "--workspace",
            str(tmp_path),
            "--title",
            "My PR Title",
            "--body",
            "PR description here",
            "--base",
            "main",
            "--head",
            "feature-branch",
            "--github-token",
            "ghp_test123",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["title"] == "My PR Title"
    assert captured["kwargs"]["body"] == "PR description here"
    assert captured["kwargs"]["base_branch"] == "main"
    assert captured["kwargs"]["head_branch"] == "feature-branch"
    assert captured["kwargs"]["github_token"] == "ghp_test123"


def test_main_pr_create_omitted_title_body_routes_correctly(monkeypatch, tmp_path) -> None:
    """Verify that omitting title/body still routes to run_pr_create (suggest path)."""
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_create(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_pr_create", fake_run_pr_create)
    monkeypatch.setenv("GITHUB_TOKEN", "ghp_test")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "create",
            "--workspace",
            str(tmp_path),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["title"] is None
    assert captured["kwargs"]["body"] is None


def test_main_pr_create_missing_subcommand_exits_nonzero(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        ["brokk", "pr", "--workspace", str(tmp_path)],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_create_with_explicit_title_body(mock_executor_class, tmp_path) -> None:
    """Verifies run_pr_create skips suggest when title and body are provided."""
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    async def mock_start():
        call_order.append("start")

    async def mock_create_session(name: str = ""):
        call_order.append("create_session")
        return "session-123"

    async def mock_wait_ready(timeout: float = 30.0):
        call_order.append("wait_ready")
        return True

    async def mock_pr_suggest(**kwargs):
        call_order.append("pr_suggest")
        return {"title": "Suggested", "description": "Suggested desc"}

    async def mock_pr_create(**kwargs):
        call_order.append(f"pr_create:title={kwargs.get('title')}")
        return {"url": "https://github.com/test/repo/pull/42"}

    async def mock_stop():
        call_order.append("stop")

    mock_manager.start = AsyncMock(side_effect=mock_start)
    mock_manager.create_session = AsyncMock(side_effect=mock_create_session)
    mock_manager.wait_ready = AsyncMock(side_effect=mock_wait_ready)
    mock_manager.pr_suggest = AsyncMock(side_effect=mock_pr_suggest)
    mock_manager.pr_create = AsyncMock(side_effect=mock_pr_create)
    mock_manager.stop = AsyncMock(side_effect=mock_stop)

    await main_module.run_pr_create(
        workspace_dir=tmp_path,
        title="Explicit Title",
        body="Explicit Body",
        github_token="ghp_test",
    )

    # pr_suggest should NOT be called when title and body are explicit
    assert "pr_suggest" not in call_order
    assert "pr_create:title=Explicit Title" in call_order
    assert "stop" in call_order


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_create_suggests_when_title_missing(mock_executor_class, tmp_path) -> None:
    """Verifies run_pr_create calls suggest when title is missing."""
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)

    async def mock_pr_suggest(**kwargs):
        call_order.append("pr_suggest")
        return {"title": "Suggested Title", "description": "Suggested Desc"}

    async def mock_pr_create(**kwargs):
        call_order.append(f"pr_create:title={kwargs.get('title')}")
        return {"url": "https://github.com/test/repo/pull/99"}

    mock_manager.pr_suggest = AsyncMock(side_effect=mock_pr_suggest)
    mock_manager.pr_create = AsyncMock(side_effect=mock_pr_create)
    mock_manager.stop = AsyncMock()

    await main_module.run_pr_create(
        workspace_dir=tmp_path,
        title=None,
        body="Explicit Body",
        github_token="ghp_test",
    )

    assert "pr_suggest" in call_order
    # Title should come from suggestion, body was explicit
    assert "pr_create:title=Suggested Title" in call_order


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_create_suggests_when_both_title_and_body_omitted(
    mock_executor_class, tmp_path, capsys
) -> None:
    """Verifies run_pr_create calls suggest with branches and token when both fields omitted."""
    from unittest.mock import AsyncMock

    captured_suggest_args: dict[str, Any] = {}
    captured_create_args: dict[str, Any] = {}
    mock_manager = mock_executor_class.return_value

    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)

    async def mock_pr_suggest(**kwargs):
        captured_suggest_args.update(kwargs)
        return {"title": "Auto-generated Title", "description": "Auto-generated Body"}

    async def mock_pr_create(**kwargs):
        captured_create_args.update(kwargs)
        return {"url": "https://github.com/org/repo/pull/777"}

    mock_manager.pr_suggest = AsyncMock(side_effect=mock_pr_suggest)
    mock_manager.pr_create = AsyncMock(side_effect=mock_pr_create)
    mock_manager.stop = AsyncMock()

    await main_module.run_pr_create(
        workspace_dir=tmp_path,
        title=None,
        body=None,
        base_branch="main",
        head_branch="feature-xyz",
        github_token="ghp_both_omitted_token",
    )

    # Verify pr_suggest was called with resolved branches and token
    mock_manager.pr_suggest.assert_called_once()
    assert captured_suggest_args["source_branch"] == "feature-xyz"
    assert captured_suggest_args["target_branch"] == "main"
    assert captured_suggest_args["github_token"] == "ghp_both_omitted_token"

    # Verify pr_create received the suggested title and description
    mock_manager.pr_create.assert_called_once()
    assert captured_create_args["title"] == "Auto-generated Title"
    assert captured_create_args["body"] == "Auto-generated Body"
    assert captured_create_args["source_branch"] == "feature-xyz"
    assert captured_create_args["target_branch"] == "main"
    assert captured_create_args["github_token"] == "ghp_both_omitted_token"

    # Verify PR URL is printed
    captured = capsys.readouterr()
    assert "https://github.com/org/repo/pull/777" in captured.out

    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_create_executor_error_exits_nonzero(
    mock_executor_class, tmp_path, capsys
) -> None:
    """Verifies run_pr_create exits non-zero on executor error."""
    from unittest.mock import AsyncMock

    from brokk_code.executor import ExecutorError

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.pr_create = AsyncMock(side_effect=ExecutorError("GitHub API error"))
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_pr_create(
            workspace_dir=tmp_path,
            title="Test",
            body="Test body",
            github_token="ghp_test",
        )

    assert exc.value.code == 1
    captured = capsys.readouterr()
    assert "Executor error" in captured.err
    assert "GitHub API error" in captured.err
    mock_manager.stop.assert_awaited_once()


def test_main_commit_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_commit(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_commit", fake_run_commit)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "commit",
            "Fix the bug",
            "--workspace",
            str(tmp_path),
            "--vendor",
            "Anthropic",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["message"] == "Fix the bug"
    assert captured["kwargs"]["vendor"] == "Anthropic"


def test_main_commit_no_message_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_commit(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_commit", fake_run_commit)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "commit",
            "--workspace",
            str(tmp_path),
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["message"] is None


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_commit_calls_lifecycle_in_order(mock_executor_class, tmp_path) -> None:
    """Verifies that run_commit follows the correct lifecycle order."""
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    async def mock_start():
        call_order.append("start")

    async def mock_create_session(name: str = ""):
        call_order.append("create_session")
        return "session-123"

    async def mock_wait_ready(timeout: float = 30.0):
        call_order.append("wait_ready")
        return True

    async def mock_commit_context(message=None):
        call_order.append(f"commit_context:{message}")
        return {"commitId": "abc123", "firstLine": "Test commit"}

    async def mock_stop():
        call_order.append("stop")

    mock_manager.start = AsyncMock(side_effect=mock_start)
    mock_manager.create_session = AsyncMock(side_effect=mock_create_session)
    mock_manager.wait_ready = AsyncMock(side_effect=mock_wait_ready)
    mock_manager.commit_context = AsyncMock(side_effect=mock_commit_context)
    mock_manager.stop = AsyncMock(side_effect=mock_stop)

    await main_module.run_commit(
        workspace_dir=tmp_path,
        message="My commit message",
    )

    assert "start" in call_order
    assert "create_session" in call_order
    assert "wait_ready" in call_order
    assert "commit_context:My commit message" in call_order
    assert "stop" in call_order

    start_idx = call_order.index("start")
    create_session_idx = call_order.index("create_session")
    wait_ready_idx = call_order.index("wait_ready")
    commit_idx = call_order.index("commit_context:My commit message")
    stop_idx = call_order.index("stop")

    assert start_idx < create_session_idx
    assert create_session_idx < wait_ready_idx
    assert wait_ready_idx < commit_idx
    assert commit_idx < stop_idx


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_commit_no_changes(mock_executor_class, tmp_path, capsys) -> None:
    """Verifies that run_commit handles no changes case."""
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.commit_context = AsyncMock(return_value={"status": "no_changes"})
    mock_manager.stop = AsyncMock()

    await main_module.run_commit(workspace_dir=tmp_path, message=None)

    captured = capsys.readouterr()
    assert "No uncommitted changes" in captured.out
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_commit_success_output(mock_executor_class, tmp_path, capsys) -> None:
    """Verifies that run_commit prints commit info on success."""
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.commit_context = AsyncMock(
        return_value={"commitId": "abc1234567890", "firstLine": "Fix parser bug"}
    )
    mock_manager.stop = AsyncMock()

    await main_module.run_commit(workspace_dir=tmp_path, message="Fix parser bug")

    captured = capsys.readouterr()
    assert "abc1234" in captured.out
    assert "Fix parser bug" in captured.out
    mock_manager.stop.assert_awaited_once()


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_commit_executor_error_exits_nonzero(
    mock_executor_class, tmp_path, capsys
) -> None:
    """Verifies that run_commit exits non-zero on executor error."""
    from unittest.mock import AsyncMock

    from brokk_code.executor import ExecutorError

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.commit_context = AsyncMock(side_effect=ExecutorError("Git error"))
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_commit(workspace_dir=tmp_path, message="test")

    assert exc.value.code == 1
    captured = capsys.readouterr()
    assert "Executor error" in captured.err
    assert "Git error" in captured.err
    mock_manager.stop.assert_awaited_once()


def test_main_pr_review_routes_correctly(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_test",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
            "--planner-model",
            "custom-model",
            "--verbose",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["workspace_dir"] == tmp_path.resolve()
    assert captured["kwargs"]["pr_number"] == 42
    assert captured["kwargs"]["github_token"] == "ghp_test"
    assert captured["kwargs"]["repo_owner"] == "acme"
    assert captured["kwargs"]["repo_name"] == "tools"
    assert captured["kwargs"]["planner_model"] == "custom-model"
    assert captured["kwargs"]["verbose"] is True


def test_main_pr_review_missing_pr_number_exits_nonzero(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--github-token",
            "ghp_test",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code != 0


def test_main_pr_review_missing_github_token_exits_nonzero(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_pr_review_missing_repo_owner_without_inference_exits_nonzero(
    monkeypatch, tmp_path
) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(main_module, "infer_github_repo_from_remote", lambda _: (None, None))
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--repo-name",
            "tools",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_pr_review_missing_repo_name_without_inference_exits_nonzero(
    monkeypatch, tmp_path
) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "token")
    monkeypatch.setattr(main_module, "infer_github_repo_from_remote", lambda _: (None, None))
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--repo-owner",
            "acme",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main()

    assert exc.value.code == 1


def test_main_pr_review_infers_repo_from_https_remote(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "https://github.com/inferred-owner/inferred-repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_test",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["repo_owner"] == "inferred-owner"
    assert captured["kwargs"]["repo_name"] == "inferred-repo"


def test_main_pr_review_infers_repo_from_ssh_remote(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "git@github.com:ssh-owner/ssh-repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_test",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["repo_owner"] == "ssh-owner"
    assert captured["kwargs"]["repo_name"] == "ssh-repo"


def test_main_pr_review_explicit_params_override_inference(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "https://github.com/inferred-owner/inferred-repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_test",
            "--repo-owner",
            "explicit-owner",
            "--repo-name",
            "explicit-repo",
        ],
    )

    main_module.main()

    assert captured["ran"] is True
    assert captured["kwargs"]["repo_owner"] == "explicit-owner"
    assert captured["kwargs"]["repo_name"] == "explicit-repo"


def test_main_pr_review_respects_env_github_token(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setenv("GITHUB_TOKEN", "env-token")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["github_token"] == "env-token"


def test_main_pr_review_uses_default_planner_model(monkeypatch, tmp_path) -> None:
    captured: dict[str, Any] = {"ran": False}

    async def fake_run_pr_review_job(**kwargs: Any) -> None:
        captured["kwargs"] = kwargs
        captured["ran"] = True

    monkeypatch.setattr(main_module, "run_pr_review_job", fake_run_pr_review_job)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "pr",
            "review",
            "--pr-number",
            "42",
            "--workspace",
            str(tmp_path),
            "--github-token",
            "ghp_test",
            "--repo-owner",
            "acme",
            "--repo-name",
            "tools",
        ],
    )

    main_module.main()

    assert captured["kwargs"]["planner_model"] == "gpt-5.1"


def test_infer_github_repo_from_remote_https_format(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "https://github.com/test-owner/test-repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner == "test-owner"
    assert repo == "test-repo"


def test_infer_github_repo_from_remote_https_no_git_suffix(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "https://github.com/test-owner/test-repo\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner == "test-owner"
    assert repo == "test-repo"


def test_infer_github_repo_from_remote_ssh_format(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "git@github.com:ssh-owner/ssh-repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner == "ssh-owner"
    assert repo == "ssh-repo"


def test_infer_github_repo_from_remote_ssh_no_git_suffix(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "git@github.com:ssh-owner/ssh-repo\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner == "ssh-owner"
    assert repo == "ssh-repo"


def test_infer_github_repo_from_remote_non_github_returns_none(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = "https://gitlab.com/owner/repo.git\n"
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner is None
    assert repo is None


def test_infer_github_repo_from_remote_git_failure_returns_none(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 1
            stdout = ""
            stderr = "fatal: not a git repository"

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner is None
    assert repo is None


def test_infer_github_repo_from_remote_empty_stdout_returns_none(monkeypatch, tmp_path) -> None:
    def fake_subprocess_run(cmd, **kwargs):
        class FakeResult:
            returncode = 0
            stdout = ""
            stderr = ""

        return FakeResult()

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    owner, repo = git_utils_module.infer_github_repo_from_remote(tmp_path)

    assert owner is None
    assert repo is None


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_review_job_calls_submit_pr_review_job(mock_executor_class, tmp_path) -> None:
    from unittest.mock import AsyncMock

    call_order: list[str] = []
    mock_manager = mock_executor_class.return_value

    async def mock_start():
        call_order.append("start")

    async def mock_create_session(name: str = ""):
        call_order.append(f"create_session:{name}")
        return "session-123"

    async def mock_wait_ready(timeout: float = 30.0):
        call_order.append("wait_ready")
        return True

    async def mock_submit_pr_review_job(**kwargs):
        call_order.append(f"submit_pr_review_job:{kwargs}")
        return "pr-review-job-456"

    async def mock_stream_events(job_id: str):
        yield {"type": "STATE_CHANGE", "data": {"state": "COMPLETED"}}

    async def mock_stop():
        call_order.append("stop")

    mock_manager.start = AsyncMock(side_effect=mock_start)
    mock_manager.create_session = AsyncMock(side_effect=mock_create_session)
    mock_manager.wait_ready = AsyncMock(side_effect=mock_wait_ready)
    mock_manager.submit_pr_review_job = AsyncMock(side_effect=mock_submit_pr_review_job)
    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock(side_effect=mock_stop)

    await main_module.run_pr_review_job(
        workspace_dir=tmp_path,
        pr_number=42,
        github_token="ghp_test",
        repo_owner="test-owner",
        repo_name="test-repo",
        planner_model="gpt-4",
    )

    assert "start" in call_order
    assert any("create_session:PR Review #42" in c for c in call_order)
    assert "wait_ready" in call_order
    assert any("submit_pr_review_job" in c for c in call_order)

    submit_call = [c for c in call_order if "submit_pr_review_job" in c][0]
    assert "planner_model" in submit_call
    assert "gpt-4" in submit_call
    assert "ghp_test" in submit_call
    assert "test-owner" in submit_call
    assert "test-repo" in submit_call
    assert "42" in submit_call


@pytest.mark.asyncio
@patch("brokk_code.executor.ExecutorManager")
async def test_run_pr_review_job_exits_nonzero_on_failed_state(
    mock_executor_class, tmp_path, capsys
) -> None:
    from unittest.mock import AsyncMock

    mock_manager = mock_executor_class.return_value
    mock_manager.start = AsyncMock()
    mock_manager.create_session = AsyncMock(return_value="session-123")
    mock_manager.wait_ready = AsyncMock(return_value=True)
    mock_manager.submit_pr_review_job = AsyncMock(return_value="job-456")

    async def mock_stream_events(job_id: str):
        yield {"type": "ERROR", "data": {"message": "GitHub API error"}}
        yield {"type": "STATE_CHANGE", "data": {"state": "FAILED"}}

    mock_manager.stream_events = mock_stream_events
    mock_manager.stop = AsyncMock()

    with pytest.raises(SystemExit) as exc:
        await main_module.run_pr_review_job(
            workspace_dir=tmp_path,
            pr_number=42,
            github_token="ghp_test",
            repo_owner="test-owner",
            repo_name="test-repo",
            planner_model="gpt-4",
        )

    assert exc.value.code == 1
    captured = capsys.readouterr()
    assert "PR review job ended with state FAILED" in captured.err


def test_install_calls_ensure_jbang_ready(monkeypatch, tmp_path) -> None:
    """install command calls ensure_jbang_ready() directly (not _resolve_jbang_for_install)."""
    ensure_called = {"n": 0}

    def fake_ensure_jbang_ready() -> str:
        ensure_called["n"] += 1
        return "/usr/bin/jbang"

    def fake_configure_zed_acp_settings(*, force=False):
        return tmp_path / "zed.json"

    monkeypatch.setattr(main_module, "ensure_jbang_ready", fake_ensure_jbang_ready)
    monkeypatch.setattr(main_module, "_run_install_prefetch", lambda _commands: None)
    monkeypatch.setattr(main_module, "configure_zed_acp_settings", fake_configure_zed_acp_settings)
    monkeypatch.setattr(
        main_module,
        "_build_install_prefetch_commands",
        lambda **kwargs: [],
    )
    monkeypatch.setattr(main_module, "sys", __import__("sys"))
    monkeypatch.setattr(
        __import__("sys"),
        "argv",
        ["brokk", "install", "zed"],
    )

    main_module.main()

    assert ensure_called["n"] == 1
