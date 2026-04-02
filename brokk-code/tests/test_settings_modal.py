# Settings modal integration tests
"""Tests for SettingsModalScreen."""

from pathlib import Path
from typing import Any, Dict

import pytest
from textual.widgets import Checkbox, Input, Select

from brokk_code.app import BrokkApp, SettingsModalScreen


class StubExecutor:
    """Minimal executor stub for settings modal tests."""

    def __init__(self, tmp_path: Path):
        self._workspace_dir = tmp_path
        self.last_saved_settings: Dict[str, Any] | None = None

    @property
    def workspace_dir(self) -> Path:
        return self._workspace_dir

    @workspace_dir.setter
    def workspace_dir(self, value: Path) -> None:
        self._workspace_dir = value

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    async def create_session(self, name: str = "") -> Dict[str, Any]:
        return {"id": "stub-session"}

    async def wait_ready(self, timeout: float = 10.0) -> None:
        pass

    async def check_alive(self) -> bool:
        return True

    async def get_settings(self) -> Dict[str, Any]:
        return {
            "buildDetails": {
                "buildLintCommand": "make lint",
                "buildLintEnabled": True,
                "testAllCommand": "make test",
                "testAllEnabled": True,
                "testSomeCommand": "make test-some",
                "testSomeEnabled": False,
                "afterTaskListCommand": "make clean",
                "exclusionPatterns": ["node_modules", "build"],
                "modules": [],
            },
            "projectSettings": {
                "codeAgentTestScope": "WORKSPACE",
                "runCommandTimeoutSeconds": 120,
                "testCommandTimeoutSeconds": 300,
                "autoUpdateLocalDependencies": True,
                "autoUpdateGitDependencies": False,
                "commitMessageFormat": "feat: {msg}",
                "dataRetentionPolicy": "MINIMAL",
            },
            "shellConfig": {"executable": "/bin/zsh", "args": ["-c"]},
            "issueProvider": {"type": "NONE"},
            "dataRetentionPolicy": "MINIMAL",
            "analyzerLanguages": {
                "configured": ["JAVA"],
                "detected": ["JAVA", "PYTHON"],
                "available": [
                    {"name": "Java", "internalName": "JAVA"},
                    {"name": "Python", "internalName": "PYTHON"},
                    {"name": "Go", "internalName": "GO"},
                ],
            },
        }

    async def get_model_config(self) -> Dict[str, Any]:
        return {}

    async def update_all_settings(self, data: Dict[str, Any]) -> Dict[str, Any]:
        self.last_saved_settings = data
        return {"status": "updated"}


class SettingsTestApp(BrokkApp):
    """Thin wrapper that skips executor startup."""

    CSS_PATH = str(Path(__file__).resolve().parent.parent / "brokk_code" / "styles" / "app.tcss")

    def __init__(self, stub: "StubExecutor"):
        super().__init__(executor=stub, workspace_dir=stub.workspace_dir)


@pytest.mark.asyncio
async def test_settings_modal_has_tabbed_content(tmp_path: Path):
    """SettingsModalScreen contains a TabbedContent widget."""
    stub = StubExecutor(tmp_path)
    app = SettingsTestApp(stub)
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        screen = app.screen
        tabs = screen.query_one("#settings-tabs")
        assert tabs is not None


@pytest.mark.asyncio
async def test_settings_modal_tab_labels(tmp_path: Path):
    """Verify expected tab panes exist."""
    stub = StubExecutor(tmp_path)
    app = SettingsTestApp(stub)
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        screen = app.screen
        screen.query_one("#settings-tab-ci")
        screen.query_one("#settings-tab-build")


@pytest.mark.asyncio
async def test_settings_modal_languages_section_exists(tmp_path: Path):
    """Verify the languages scroll container exists in the CI tab."""
    stub = StubExecutor(tmp_path)
    app = SettingsTestApp(stub)
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        screen = app.screen
        lang_scroll = screen.query_one("#settings-languages-scroll")
        assert lang_scroll is not None


@pytest.mark.asyncio
async def test_settings_modal_loads_data(tmp_path: Path):
    """Verify form fields are populated from executor settings data."""
    stub = StubExecutor(tmp_path)
    app = SettingsTestApp(stub)
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        # Allow the async worker to load settings
        await pilot.pause()

        screen = app.screen

        # Build details
        assert screen.query_one("#settings-build-lint-command", Input).value == "make lint"
        assert screen.query_one("#settings-build-lint-enabled", Checkbox).value is True
        assert screen.query_one("#settings-test-all-command", Input).value == "make test"
        assert screen.query_one("#settings-test-some-enabled", Checkbox).value is False
        assert screen.query_one("#settings-after-tasklist-command", Input).value == "make clean"

        # Project settings - timeout selects
        assert screen.query_one("#settings-run-timeout", Select).value == "120"
        assert screen.query_one("#settings-test-timeout", Select).value == "300"

        # Shell config
        assert screen.query_one("#settings-shell-executable", Input).value == "/bin/zsh"
        assert screen.query_one("#settings-shell-args", Input).value == "-c"


@pytest.mark.asyncio
async def test_settings_modal_saves_data(tmp_path: Path):
    """Verify save sends correct payload to executor."""
    stub = StubExecutor(tmp_path)
    app = SettingsTestApp(stub)
    async with app.run_test(size=(120, 40)) as pilot:
        app.push_screen(SettingsModalScreen())
        await pilot.pause()
        await pilot.pause()

        screen = app.screen

        # Change a value
        screen.query_one("#settings-build-lint-command", Input).value = "npm run lint"

        # Trigger save via button press
        screen.query_one("#settings-save").press()
        await pilot.pause()
        await pilot.pause()

        # Verify the save was called
        assert stub.last_saved_settings is not None
        build = stub.last_saved_settings["buildDetails"]
        assert build["buildLintCommand"] == "npm run lint"
        # Unchanged fields should still be present
        assert build["testAllCommand"] == "make test"
