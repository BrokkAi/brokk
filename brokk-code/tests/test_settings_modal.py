import asyncio
from unittest.mock import AsyncMock, patch

import pytest

from brokk_code.app import BrokkApp, ModuleEditModalScreen, SettingsModalScreen
from brokk_code.executor import ExecutorManager


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
                "testSomeCommand": "",
                "testSomeEnabled": False,
                "afterTaskListCommand": "",
                "exclusionPatterns": ["build", "node_modules"],
                "environmentVariables": {"JAVA_HOME": "/usr/lib/jvm/java-21"},
                "modules": [
                    {
                        "alias": "backend",
                        "language": "Java",
                        "relativePath": "backend/",
                        "buildLintCommand": "./gradlew build",
                        "testAllCommand": "./gradlew test",
                        "testSomeCommand": "",
                    },
                    {
                        "alias": "frontend",
                        "language": "TypeScript",
                        "relativePath": "frontend/",
                        "buildLintCommand": "npm run lint",
                        "testAllCommand": "npm test",
                        "testSomeCommand": "",
                    },
                ],
            },
            "projectSettings": {
                "codeAgentTestScope": "ALL",
                "commitMessageFormat": "",
                "runCommandTimeoutSeconds": -1,
                "testCommandTimeoutSeconds": -1,
                "autoUpdateLocalDependencies": False,
                "autoUpdateGitDependencies": False,
            },
            "shellConfig": {"executable": "/bin/bash", "args": ["-lc"]},
            "issueProvider": {"type": "NONE", "config": {}},
            "dataRetentionPolicy": "IMPROVE_BROKK",
        }

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

    async def get_context(self):
        return {"branch": "main", "usedTokens": 0, "maxTokens": 100000, "fragments": []}

    async def get_tasklist(self):
        return {"tasks": []}

    async def get_model_config(self):
        return {}


@pytest.mark.asyncio
async def test_modules_displayed_from_loaded_settings(tmp_path):
    """Verify modules from loaded settings appear in the modules ListView."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        # Allow async _load_settings to complete
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)

        # Check that modules were loaded
        assert len(screen._modules) == 2
        assert screen._modules[0]["alias"] == "backend"
        assert screen._modules[0]["language"] == "Java"
        assert screen._modules[0]["relativePath"] == "backend/"
        assert screen._modules[1]["alias"] == "frontend"
        assert screen._modules[1]["language"] == "TypeScript"


@pytest.mark.asyncio
async def test_add_module_appends_to_list(tmp_path):
    """Verify that adding a module appends it to the internal list and refreshes the ListView."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)
        assert len(screen._modules) == 2

        # Directly simulate what _add_module's callback does
        new_module = {
            "alias": "shared",
            "language": "Kotlin",
            "relativePath": "shared/",
            "buildLintCommand": "./gradlew :shared:build",
            "testAllCommand": "./gradlew :shared:test",
            "testSomeCommand": "",
        }
        screen._modules.append(new_module)
        screen._refresh_modules_list()
        await pilot.pause()

        assert len(screen._modules) == 3
        assert screen._modules[2]["alias"] == "shared"


@pytest.mark.asyncio
async def test_remove_module_deletes_selected(tmp_path):
    """Verify removing a module deletes it from the list."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)
        assert len(screen._modules) == 2

        from textual.widgets import ListView

        modules_list = screen.query_one("#settings-modules-list", ListView)
        modules_list.index = 0
        screen._remove_selected_module()
        await pilot.pause()

        assert len(screen._modules) == 1
        assert screen._modules[0]["alias"] == "frontend"


@pytest.mark.asyncio
async def test_shell_config_displayed(tmp_path):
    """Verify shell configuration fields are populated from loaded settings."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)

        from textual.widgets import Input

        shell_exec = screen.query_one("#settings-shell-executable", Input)
        shell_args = screen.query_one("#settings-shell-args", Input)

        assert shell_exec.value == "/bin/bash"
        assert shell_args.value == "-lc"


@pytest.mark.asyncio
async def test_module_edit_modal_prefilled(tmp_path):
    """Verify ModuleEditModalScreen is pre-filled with the given module values."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app.push_screen(
            ModuleEditModalScreen(
                "Edit Module",
                alias="backend",
                language="Java",
                relative_path="backend/",
                build_lint_command="./gradlew build",
                test_all_command="./gradlew test",
                test_some_command="",
            )
        )
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, ModuleEditModalScreen)

        from textual.widgets import Input

        assert screen.query_one("#module-edit-alias", Input).value == "backend"
        assert screen.query_one("#module-edit-language", Input).value == "Java"
        assert screen.query_one("#module-edit-path", Input).value == "backend/"
        assert screen.query_one("#module-edit-build-cmd", Input).value == "./gradlew build"
        assert screen.query_one("#module-edit-test-all-cmd", Input).value == "./gradlew test"
        assert screen.query_one("#module-edit-test-some-cmd", Input).value == ""


@pytest.mark.asyncio
async def test_module_move_up_down(tmp_path):
    """Verify modules can be reordered with move up/down."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)
        assert screen._modules[0]["alias"] == "backend"
        assert screen._modules[1]["alias"] == "frontend"

        from textual.widgets import ListView

        modules_list = screen.query_one("#settings-modules-list", ListView)
        modules_list.index = 0

        # Move first module down
        screen._move_module(1)
        await pilot.pause()

        assert screen._modules[0]["alias"] == "frontend"
        assert screen._modules[1]["alias"] == "backend"


@pytest.mark.asyncio
async def test_issue_provider_type_selection_toggles_sections(tmp_path):
    """Verify selecting an issue provider type shows/hides the correct config sections."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)

        # Initial state should be NONE (from stub get_settings)
        assert screen._issue_provider_type == "NONE"
        none_section = screen.query_one("#settings-issue-none-section")
        github_section = screen.query_one("#settings-issue-github-section")
        jira_section = screen.query_one("#settings-issue-jira-section")

        # None section visible, others hidden
        assert "hidden" not in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" in jira_section.classes

        # Select GitHub
        screen._set_issue_provider_type("GITHUB")
        await pilot.pause()

        assert screen._issue_provider_type == "GITHUB"
        assert "hidden" in none_section.classes
        assert "hidden" not in github_section.classes
        assert "hidden" in jira_section.classes

        # Select Jira
        screen._set_issue_provider_type("JIRA")
        await pilot.pause()

        assert screen._issue_provider_type == "JIRA"
        assert "hidden" in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" not in jira_section.classes

        # Select None again
        screen._set_issue_provider_type("NONE")
        await pilot.pause()

        assert screen._issue_provider_type == "NONE"
        assert "hidden" not in none_section.classes
        assert "hidden" in github_section.classes
        assert "hidden" in jira_section.classes


@pytest.mark.asyncio
async def test_github_override_checkbox_toggles_fields(tmp_path):
    """Verify GitHub override checkbox shows/hides the owner/repo/host fields."""
    stub = StubExecutor(tmp_path)
    app = BrokkApp(executor=stub, workspace_dir=tmp_path)

    async with app.run_test() as pilot:
        app._executor_ready = True
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await asyncio.sleep(0.5)
        await pilot.pause()

        screen = app.screen
        assert isinstance(screen, SettingsModalScreen)

        # Switch to GitHub provider
        screen._set_issue_provider_type("GITHUB")
        await pilot.pause()

        github_fields = screen.query_one("#settings-github-fields")

        # Initially override is off, fields hidden
        assert screen._github_override is False
        assert "hidden" in github_fields.classes

        # Enable override
        screen._toggle_github_override(True)
        await pilot.pause()

        assert screen._github_override is True
        assert "hidden" not in github_fields.classes

        # Disable override
        screen._toggle_github_override(False)
        await pilot.pause()

        assert screen._github_override is False
        assert "hidden" in github_fields.classes
