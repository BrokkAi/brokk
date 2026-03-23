# Settings modal test suite - verifies SettingsModalScreen behavior
import pytest

from brokk_code.app import BrokkApp, SettingsModalScreen
from brokk_code.executor import ExecutorManager


@pytest.fixture(autouse=True)
def _set_test_api_key(monkeypatch):
    monkeypatch.setenv("BROKK_API_KEY", "test-api-key")


MOCK_SETTINGS = {
    "buildDetails": {
        "buildLintCommand": "make lint",
        "buildLintEnabled": True,
        "testAllCommand": "make test",
        "testAllEnabled": True,
        "testSomeCommand": "make test TESTS={testfiles}",
        "testSomeEnabled": False,
        "afterTaskListCommand": "",
        "exclusionPatterns": ["build", "node_modules", "*.svg"],
        "environmentVariables": {"CC": "gcc", "JAVA_HOME": "/usr/lib/jvm"},
        "modules": [
            {
                "alias": "backend",
                "language": "Java",
                "relativePath": "backend/",
                "buildLintCommand": "./gradlew build",
                "testAllCommand": "./gradlew test",
                "testSomeCommand": "./gradlew test --tests {{#classes}}",
            },
            {
                "alias": "frontend",
                "language": "TypeScript",
                "relativePath": "frontend/",
                "buildLintCommand": "npm run lint",
                "testAllCommand": "npm test",
                "testSomeCommand": "npm test -- {{#files}}",
            },
        ],
    },
    "projectSettings": {
        "codeAgentTestScope": "ALL",
        "runCommandTimeoutSeconds": -1,
        "testCommandTimeoutSeconds": 120,
        "commitMessageFormat": "feat: {description}",
        "autoUpdateLocalDependencies": True,
        "autoUpdateGitDependencies": False,
    },
    "shellConfig": {
        "executable": "/bin/sh",
        "args": ["-c"],
    },
    "issueProvider": {
        "type": "NONE",
        "config": {},
    },
    "dataRetentionPolicy": "IMPROVE_BROKK",
}


class StubExecutor(ExecutorManager):
    """Minimal executor stub for settings modal tests."""

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
        return MOCK_SETTINGS

    async def get_model_config(self):
        return {}

    async def update_build_settings(self, data):
        return data

    async def update_project_settings(self, data):
        return data

    async def update_shell_config(self, data):
        return data

    async def update_issue_provider(self, data):
        return data

    async def update_data_retention(self, policy):
        return {"policy": policy}


@pytest.mark.asyncio
async def test_settings_modal_opens(tmp_path):
    """Verify that action_open_settings pushes a SettingsModalScreen."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        assert isinstance(app.screen, SettingsModalScreen)


@pytest.mark.asyncio
async def test_settings_modal_build_fields_display(tmp_path):
    """Verify build configuration fields are populated from executor settings."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import Checkbox, Input

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        build_lint = modal.query_one("#settings-build-lint-command", Input)
        assert build_lint.value == "make lint"

        test_all = modal.query_one("#settings-test-all-command", Input)
        assert test_all.value == "make test"

        test_some = modal.query_one("#settings-test-some-command", Input)
        assert test_some.value == "make test TESTS={testfiles}"

        test_some_enabled = modal.query_one("#settings-test-some-enabled", Checkbox)
        assert test_some_enabled.value is False


@pytest.mark.asyncio
async def test_settings_modal_escape_dismisses(tmp_path):
    """Verify pressing Escape dismisses the settings modal."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        assert isinstance(app.screen, SettingsModalScreen)

        await pilot.press("escape")
        await pilot.pause()
        assert not isinstance(app.screen, SettingsModalScreen)


@pytest.mark.asyncio
async def test_settings_modal_exclusion_patterns(tmp_path):
    """Verify exclusion patterns are displayed in the list."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import ListView

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        exclusion_list = modal.query_one("#settings-exclusion-list", ListView)
        assert len(exclusion_list.children) == 3


@pytest.mark.asyncio
async def test_settings_modal_commit_format(tmp_path):
    """Verify commit message format is populated in TextArea."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import TextArea

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        commit_format = modal.query_one("#settings-commit-format", TextArea)
        assert commit_format.text == "feat: {description}"


@pytest.mark.asyncio
async def test_settings_modal_env_vars(tmp_path):
    """Verify environment variables are displayed in the list."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import ListView

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        env_list = modal.query_one("#settings-env-var-list", ListView)
        assert len(env_list.children) == 2


@pytest.mark.asyncio
async def test_settings_modal_auto_update_flags(tmp_path):
    """Verify auto-update dependency checkboxes reflect loaded settings."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import Checkbox

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        auto_local = modal.query_one("#settings-auto-update-local", Checkbox)
        assert auto_local.value is True

        auto_git = modal.query_one("#settings-auto-update-git", Checkbox)
        assert auto_git.value is False


@pytest.mark.asyncio
async def test_settings_modal_timeouts(tmp_path):
    """Verify timeout fields are populated from settings."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import Input

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        run_timeout = modal.query_one("#settings-run-timeout", Input)
        assert run_timeout.value == "-1"

        test_timeout = modal.query_one("#settings-test-timeout", Input)
        assert test_timeout.value == "120"


@pytest.mark.asyncio
async def test_settings_modal_modules_list(tmp_path):
    """Verify modules are displayed in the list with alias, language, path."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import ListView

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        modules_list = modal.query_one("#settings-modules-list", ListView)
        # MOCK_SETTINGS has 2 modules: backend and frontend
        assert len(modules_list.children) == 2


@pytest.mark.asyncio
async def test_settings_modal_shell_config(tmp_path):
    """Verify shell configuration fields are populated from settings."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import Input

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        shell_exec = modal.query_one("#settings-shell-executable", Input)
        assert shell_exec.value == "/bin/sh"

        shell_args = modal.query_one("#settings-shell-args", Input)
        assert shell_args.value == "-c"


@pytest.mark.asyncio
async def test_settings_modal_issue_provider_type_selection(tmp_path):
    """Verify selecting a provider type shows the correct configuration fields."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        from textual.widgets import Button

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        # Initially set to NONE - verify None section is visible
        none_section = modal.query_one("#settings-issue-none-section")
        github_section = modal.query_one("#settings-issue-github-section")
        jira_section = modal.query_one("#settings-issue-jira-section")

        assert "hidden" not in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" in jira_section.classes

        # Click GitHub button
        github_btn = modal.query_one("#settings-issue-github", Button)
        github_btn.press()
        await pilot.pause()

        # Verify GitHub section is now visible
        assert "hidden" in none_section.classes
        assert "hidden" not in github_section.classes
        assert "hidden" in jira_section.classes

        # Click Jira button
        jira_btn = modal.query_one("#settings-issue-jira", Button)
        jira_btn.press()
        await pilot.pause()

        # Verify Jira section is now visible
        assert "hidden" in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" not in jira_section.classes

        # Click None button to go back
        none_btn = modal.query_one("#settings-issue-none", Button)
        none_btn.press()
        await pilot.pause()

        # Verify None section is visible again
        assert "hidden" not in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" in jira_section.classes


@pytest.mark.asyncio
async def test_settings_modal_add_module(tmp_path):
    """Verify add module button opens ModuleEditModalScreen."""
    from brokk_code.app import ModuleEditModalScreen

    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True
        app.action_open_settings()
        await pilot.pause()
        await pilot.pause()

        modal = app.screen
        assert isinstance(modal, SettingsModalScreen)

        # Click the Add button for modules
        add_btn = modal.query_one("#settings-module-add")
        add_btn.press()
        await pilot.pause()

        # ModuleEditModalScreen should now be on top
        assert isinstance(app.screen, ModuleEditModalScreen)


@pytest.mark.asyncio
async def test_module_edit_modal_fields(tmp_path):
    """Verify ModuleEditModalScreen has all required input fields."""
    from brokk_code.app import ModuleEditModalScreen
    from textual.widgets import Input

    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)
    async with app.run_test() as pilot:
        app._executor_ready = True

        # Push the modal directly with initial values
        modal = ModuleEditModalScreen(
            "Edit Module",
            alias="test-alias",
            language="Python",
            relative_path="src/",
            build_lint_command="make lint",
            test_all_command="make test",
            test_some_command="make test {{#files}}",
        )
        app.push_screen(modal)
        await pilot.pause()

        # Verify all fields exist and have correct values
        alias_input = modal.query_one("#module-edit-alias", Input)
        assert alias_input.value == "test-alias"

        lang_input = modal.query_one("#module-edit-language", Input)
        assert lang_input.value == "Python"

        path_input = modal.query_one("#module-edit-path", Input)
        assert path_input.value == "src/"

        build_input = modal.query_one("#module-edit-build-cmd", Input)
        assert build_input.value == "make lint"

        test_all_input = modal.query_one("#module-edit-test-all-cmd", Input)
        assert test_all_input.value == "make test"

        test_some_input = modal.query_one("#module-edit-test-some-cmd", Input)
        assert test_some_input.value == "make test {{#files}}"
