from pathlib import Path
from unittest.mock import MagicMock

from brokk_code.widgets.status_line import StatusLine


def test_status_line_rendering_compact_home_abbreviation(monkeypatch):
    # Mock home directory
    fake_home = Path("/home/user")
    monkeypatch.setattr(Path, "home", lambda: fake_home)

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

    # Case 2: Under home
    status.update_status(
        workspace="/home/user/projects/brokk",
    )
    expected_sub = "LUTZ • gpt-4 (high) • ~/projects/brokk • main"
    mock_metadata.update.assert_called_with(expected_sub)


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
