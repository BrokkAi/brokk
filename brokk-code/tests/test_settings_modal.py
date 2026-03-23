import pytest

from brokk_code.app import BrokkApp, SettingsModalScreen
from brokk_code.executor import ExecutorManager
from textual.widgets import Input, Checkbox


@pytest.fixture(autouse=True)
def _set_test_api_key(monkeypatch):
    monkeypatch.setenv("BROKK_API_KEY", "test-api-key")


class StubExecutor(ExecutorManager):
    def __init__(self, workspace_dir):
        super().__init__(workspace_dir=workspace_dir)

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

    async def get_settings(self):
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
            },
            "shellConfig": {"executable": "/bin/sh", "args": ["-c"]},
            "issueProvider": {"type": "NONE", "config": {}},
            "dataRetentionPolicy": "IMPROVE_BROKK",
        }

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

        # Verify build/lint command
        build_lint_cmd = app.screen.query_one("#settings-build-lint-command", Input)
        assert build_lint_cmd.value == "make lint"

        build_lint_enabled = app.screen.query_one("#settings-build-lint-enabled", Checkbox)
        assert build_lint_enabled.value is True

        # Verify test all command
        test_all_cmd = app.screen.query_one("#settings-test-all-command", Input)
        assert test_all_cmd.value == "make test"

        test_all_enabled = app.screen.query_one("#settings-test-all-enabled", Checkbox)
        assert test_all_enabled.value is True

        # Verify test some command
        test_some_cmd = app.screen.query_one("#settings-test-some-command", Input)
        assert test_some_cmd.value == "make test-some"

        test_some_enabled = app.screen.query_one("#settings-test-some-enabled", Checkbox)
        assert test_some_enabled.value is False

        # Verify after tasklist command
        after_tasklist_cmd = app.screen.query_one("#settings-after-tasklist-command", Input)
        assert after_tasklist_cmd.value == "echo done"

        # Verify timeouts
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
