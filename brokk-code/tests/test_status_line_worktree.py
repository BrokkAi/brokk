from unittest.mock import MagicMock

from brokk_code.widgets.status_line import StatusLine


def test_status_line_worktree_rendering():
    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata

    # Test with worktree name
    status.update_status(
        mode="LUTZ",
        model="gpt-4",
        reasoning="high",
        workspace="/work",
        branch="main",
        worktree="feature-x",
    )
    call_text = mock_metadata.update.call_args[0][0]
    assert "wt:feature-x" in call_text

    # Test with empty worktree name — wt: must NOT appear
    status.update_status(worktree="")
    call_text = mock_metadata.update.call_args[0][0]
    assert "wt:" not in call_text

    # Test with None worktree name — no change to field (stays cleared)
    status.update_status(worktree=None)
    call_text = mock_metadata.update.call_args[0][0]
    assert "wt:" not in call_text
