from pathlib import Path
from unittest.mock import MagicMock

from brokk_code.widgets.status_line import StatusLine


def test_status_line_rendering_compact_home_abbreviation(monkeypatch):
    # Mock home directory
    fake_home = Path("/home/user")
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    # Mock Path to return our controlled paths so is_relative_to works without resolve()
    original_path = Path

    def mock_path(p):
        return original_path(p)

    monkeypatch.setattr("brokk_code.widgets.status_line.Path", mock_path)

    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata

    # Case 1: Exactly home
    status.update_status(
        mode="LUTZ",
        model="gpt-4",
        reasoning="high",
        workspace="/home/user",
        branch="main",
    )
    # Expected format: {mode} • {model} ({reasoning}) • {workspace} • {branch}
    expected_home = "LUTZ • gpt-4 (high) • ~ • main"
    mock_metadata.update.assert_called_with(expected_home)

    # Case 2: Under home (verify partial update preserves previous values)
    status.update_status(
        workspace="/home/user/projects/brokk",
    )
    expected_sub = "LUTZ • gpt-4 (high) • ~/projects/brokk • main"
    mock_metadata.update.assert_called_with(expected_sub)

    # Case 3: Explicit update to "unknown" (verify explicit values overwrite previous values)
    status.update_status(
        mode="unknown",
        branch="unknown",
    )
    expected_unknown = "unknown • gpt-4 (high) • ~/projects/brokk • unknown"
    mock_metadata.update.assert_called_with(expected_unknown)

    # Case 4: Explicit update to empty string
    status.update_status(
        mode="",
    )
    expected_empty = " • gpt-4 (high) • ~/projects/brokk • unknown"
    mock_metadata.update.assert_called_with(expected_empty)


def test_status_line_rendering_compact_no_abbreviation(monkeypatch):
    fake_home = Path("/home/user")
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata

    # Workspace outside home should remain full path
    status.update_status(
        mode="LUTZ",
        model="gpt-4",
        reasoning="high",
        workspace="/var/www/project",
        branch="main",
    )

    # Format: {mode} • {model} ({reasoning}) • {workspace} • {branch}
    expected = "LUTZ • gpt-4 (high) • /var/www/project • main"
    mock_metadata.update.assert_called_with(expected)


def test_status_line_fragment_rendering():
    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata

    status.set_fragment_info("my-file.py", 1234)

    # Expected: {description} ({tokens} tokens)
    from brokk_code.token_format import format_token_count

    size_text = format_token_count(1234)
    expected = f"my-file.py ({size_text} tokens)"
    mock_metadata.update.assert_called_with(expected)


def test_status_line_rendering_windows_path_normalization(monkeypatch):
    # Mock a Windows home directory
    fake_home = Path("C:/Users/user")
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata

    # Case 1: Windows path outside home with backslashes
    status.update_status(
        mode="LUTZ",
        model="gpt-4",
        reasoning="high",
        workspace="D:\\projects\\external",
        branch="main",
    )
    # Note: Path("D:\\projects\\external").as_posix() or the fallback replace("\\", "/")
    # should result in forward slashes.
    expected_external = "LUTZ • gpt-4 (high) • D:/projects/external • main"
    mock_metadata.update.assert_called_with(expected_external)

    # Case 2: Windows path inside home with backslashes
    # We use forward slashes for the comparison in mock_path logic or let Path handle it
    status.update_status(
        workspace="C:\\Users\\user\\projects\\brokk",
    )
    # Expected: Home abbreviation + forward slashes
    expected_home_sub = "LUTZ • gpt-4 (high) • ~/projects/brokk • main"
    mock_metadata.update.assert_called_with(expected_home_sub)


import pytest
from textual.app import App, ComposeResult


class StatusLineTestApp(App):
    def compose(self) -> ComposeResult:
        yield StatusLine(id="status-line")


@pytest.mark.asyncio
async def test_status_line_no_timer_regression():
    """
    Ensure StatusLine does not contain legacy timer or progress widgets.
    The timer logic was moved to ChatPanel.
    """
    app = StatusLineTestApp()
    async with app.run_test() as pilot:
        status_line = app.query_one("#status-line", StatusLine)

        # 1. Assert legacy timer/progress widgets are absent
        legacy_selectors = ["#status-timer", "#status-timer-wrap", "#status-progress"]
        for selector in legacy_selectors:
            matches = status_line.query(selector)
            assert len(matches) == 0, f"Found legacy widget {selector} in StatusLine"

        # 2. Verify set_job_running is a safe no-op
        # This ensures that even if called (e.g. from ChatPanel), it doesn't crash
        status_line.set_job_running(True)
        status_line.set_job_running(False)
        await pilot.pause()
