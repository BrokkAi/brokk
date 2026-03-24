# Settings modal integration tests
"""Tests for SettingsModalScreen."""

from pathlib import Path
from typing import Any, Dict, List

import pytest

from brokk_code.app import BrokkApp, SettingsModalScreen


class StubExecutor:
    """Minimal executor stub for settings modal tests."""

    def __init__(self, tmp_path: Path):
        self._workspace_dir = tmp_path

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
                "lintCommand": "",
                "testAllCommand": "",
                "testSomeCommand": "",
                "afterTaskListCommand": "",
                "codeAgentTestScope": "ALL",
                "runCommandTimeoutSeconds": 30,
                "testCommandTimeoutSeconds": 60,
                "lintCommandEnabled": True,
                "testAllCommandEnabled": True,
                "testSomeCommandEnabled": True,
            },
            "projectSettings": {
                "exclusionPatterns": [],
                "environmentVariables": {},
                "autoUpdateLocalDependencies": False,
                "autoUpdateGitDependencies": False,
            },
            "shellConfig": {"executable": "/bin/sh", "args": "-c"},
            "issueProvider": {"type": "NONE"},
            "dataRetentionPolicy": "UNSET",
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

    async def update_build_settings(self, data: Dict[str, Any]) -> Dict[str, Any]:
        return data

    async def update_project_settings(self, data: Dict[str, Any]) -> Dict[str, Any]:
        return data

    async def update_shell_config(self, data: Dict[str, Any]) -> Dict[str, Any]:
        return data

    async def update_issue_provider(self, data: Dict[str, Any]) -> Dict[str, Any]:
        return data

    async def update_data_retention(self, policy: str) -> Dict[str, Any]:
        return {"policy": policy}

    async def update_analyzer_languages(self, languages: List[str]) -> Dict[str, Any]:
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
