import pytest

from brokk_code.app import BrokkApp, SettingsModalScreen
from brokk_code.executor import ExecutorManager
from textual.widgets import Input, Checkbox, ListView, TextArea


@pytest.fixture(autouse=True)
def _set_test_api_key(monkeypatch):
    monkeypatch.setenv("BROKK_API_KEY", "test-api-key")


class StubExecutor(ExecutorManager):
    def __init__(self, workspace_dir):
        super().__init__(workspace_dir=workspace_dir)
        self._settings_data = None

    async def start(self):
        pass

    async def stop(self):
        pass

    async def create_session(self, name: str = "TUI Session") -> str:
        self.session_id = "session-1"
        return self.session_id

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        return True

    def check_alive(self) -> bool:
        return True

    async def get_health_live(self):
        return {"version": "test", "protocolVersion": "1", "execId": "test-id"}

    def _default_settings(self):
        return {
            "buildDetails": {
                "buildLintCommand": "make lint",
                "buildLintEnabled": True,
                "testAllCommand": "make test",
                "testAllEnabled": True,
                "testSomeCommand": "make test-some",
                "testSomeEnabled": False,
                "afterTaskListCommand": "echo done",
                "exclusionPatterns": [],
                "environmentVariables": {},
                "modules": [],
            },
            "projectSettings": {
                "codeAgentTestScope": "ALL",
                "runCommandTimeoutSeconds": 30,
                "testCommandTimeoutSeconds": 60,
                "commitMessageFormat": "",
                "autoUpdateLocalDependencies": False,
                "autoUpdateGitDependencies": False,
                "dataRetentionPolicy": "IMPROVE_BROKK",
            },
            "shellConfig": {"executable": "/bin/sh", "args": ["-c"]},
            "issueProvider": {"type": "NONE", "config": {}},
        }

    async def get_settings(self):
        if self._settings_data is not None:
            return self._settings_data
        return self._default_settings()

    async def update_build_settings(self, data):
        return data

    async def update_project_settings(self, data):
        return data

    async def update_data_retention(self, policy):
        return {"policy": policy}

    async def update_shell_config(self, data):
        return data

    async def update_issue_provider(self, data):
        return data

    async def get_model_config(self):
        return {}


@pytest.mark.asyncio
async def test_settings_command_opens_modal(tmp_path):
    """Verifies that the /settings command opens the SettingsModalScreen."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)


@pytest.mark.asyncio
async def test_settings_modal_displays_build_fields(tmp_path):
    """Verifies that build configuration fields are displayed with correct values."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        build_lint_cmd = app.screen.query_one("#settings-build-lint-command", Input)
        assert build_lint_cmd.value == "make lint"

        build_lint_enabled = app.screen.query_one("#settings-build-lint-enabled", Checkbox)
        assert build_lint_enabled.value is True

        test_all_cmd = app.screen.query_one("#settings-test-all-command", Input)
        assert test_all_cmd.value == "make test"

        test_all_enabled = app.screen.query_one("#settings-test-all-enabled", Checkbox)
        assert test_all_enabled.value is True

        test_some_cmd = app.screen.query_one("#settings-test-some-command", Input)
        assert test_some_cmd.value == "make test-some"

        test_some_enabled = app.screen.query_one("#settings-test-some-enabled", Checkbox)
        assert test_some_enabled.value is False

        after_tasklist_cmd = app.screen.query_one("#settings-after-tasklist-command", Input)
        assert after_tasklist_cmd.value == "echo done"

        run_timeout = app.screen.query_one("#settings-run-timeout", Input)
        assert run_timeout.value == "30"

        test_timeout = app.screen.query_one("#settings-test-timeout", Input)
        assert test_timeout.value == "60"


@pytest.mark.asyncio
async def test_settings_escape_dismisses_modal(tmp_path):
    """Verifies that pressing Escape dismisses the SettingsModalScreen."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        await pilot.press("escape")
        await pilot.pause()

        assert not isinstance(app.screen, SettingsModalScreen)


@pytest.mark.asyncio
async def test_settings_modal_displays_exclusion_patterns(tmp_path):
    """Verifies that exclusion patterns are displayed in the list."""
    stub = StubExecutor(tmp_path)
    stub._settings_data = stub._default_settings()
    stub._settings_data["buildDetails"]["exclusionPatterns"] = ["node_modules", "build", "*.svg"]
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        assert len(app.screen._exclusion_patterns) == 3
        assert "node_modules" in app.screen._exclusion_patterns
        assert "build" in app.screen._exclusion_patterns
        assert "*.svg" in app.screen._exclusion_patterns


@pytest.mark.asyncio
async def test_settings_modal_exclusion_pattern_removal(tmp_path):
    """Verifies that exclusion patterns can be removed."""
    stub = StubExecutor(tmp_path)
    stub._settings_data = stub._default_settings()
    stub._settings_data["buildDetails"]["exclusionPatterns"] = ["node_modules", "build"]
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        assert len(app.screen._exclusion_patterns) == 2

        exclusion_list = app.screen.query_one("#settings-exclusion-list", ListView)
        exclusion_list.index = 0
        await pilot.pause()

        app.screen._exclusion_patterns.remove("build")
        app.screen._refresh_exclusion_list()
        await pilot.pause()

        assert len(app.screen._exclusion_patterns) == 1
        assert "node_modules" in app.screen._exclusion_patterns
        assert "build" not in app.screen._exclusion_patterns


@pytest.mark.asyncio
async def test_settings_modal_commit_format_populated(tmp_path):
    """Verifies that commit message format is populated with current value."""
    stub = StubExecutor(tmp_path)
    stub._settings_data = stub._default_settings()
    stub._settings_data["projectSettings"]["commitMessageFormat"] = "feat: {description}\n\nBody here"
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        commit_format = app.screen.query_one("#settings-commit-format", TextArea)
        assert commit_format.text == "feat: {description}\n\nBody here"


@pytest.mark.asyncio
async def test_settings_modal_env_vars_displayed(tmp_path):
    """Verifies that environment variables are displayed as key=value pairs."""
    stub = StubExecutor(tmp_path)
    stub._settings_data = stub._default_settings()
    stub._settings_data["buildDetails"]["environmentVariables"] = {
        "JAVA_HOME": "/usr/lib/jvm/java-21",
        "PATH_EXTRA": "/custom/bin",
    }
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        assert len(app.screen._env_vars) == 2
        assert app.screen._env_vars["JAVA_HOME"] == "/usr/lib/jvm/java-21"
        assert app.screen._env_vars["PATH_EXTRA"] == "/custom/bin"


@pytest.mark.asyncio
async def test_settings_modal_auto_update_flags(tmp_path):
    """Verifies that auto-update dependency flags are displayed correctly."""
    stub = StubExecutor(tmp_path)
    stub._settings_data = stub._default_settings()
    stub._settings_data["projectSettings"]["autoUpdateLocalDependencies"] = True
    stub._settings_data["projectSettings"]["autoUpdateGitDependencies"] = False
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app._handle_command("/settings")
        await pilot.pause()
        await pilot.pause()

        assert isinstance(app.screen, SettingsModalScreen)

        auto_local = app.screen.query_one("#settings-auto-update-local", Checkbox)
        auto_git = app.screen.query_one("#settings-auto-update-git", Checkbox)

        assert auto_local.value is True
        assert auto_git.value is False
