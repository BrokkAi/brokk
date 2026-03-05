from unittest.mock import MagicMock

import pytest

from brokk_code.app import BrokkApp
from brokk_code.widgets.status_line import StatusLine


@pytest.mark.asyncio
async def test_model_settings_scoping_per_worktree(tmp_path):
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)

    # 1. Set values in worktree A
    app.current_model = "model-a"
    app.reasoning_level = "high"
    app.agent_mode = "CODE"
    app.auto_commit = False

    assert app.get_runtime(path_a).planner_model == "model-a"
    assert app.get_runtime(path_a).reasoning_level == "high"
    assert app.get_runtime(path_a).agent_mode == "CODE"
    assert app.get_runtime(path_a).auto_commit is False

    # 2. Switch to worktree B (should have baseline default values)
    app.current_worktree = path_b

    # Defaults (from BrokkApp baseline defaults)
    assert app.current_model != "model-a"
    assert app.reasoning_level != "high"
    assert app.agent_mode == "LUTZ"
    assert app.auto_commit == app._baseline_auto_commit

    # 3. Set values in worktree B
    app.current_model = "model-b"
    app.reasoning_level = "disable"

    assert app.get_runtime(path_b).planner_model == "model-b"
    assert app.get_runtime(path_b).reasoning_level == "disable"

    # 4. Switch back to A and verify restoration
    app.current_worktree = path_a
    assert app.current_model == "model-a"
    assert app.reasoning_level == "high"
    assert app.agent_mode == "CODE"
    assert app.auto_commit is False


@pytest.mark.asyncio
async def test_status_line_updates_on_worktree_switch(tmp_path):
    """Verify that _update_statusline correctly synchronizes state from the active runtime."""
    path_a = tmp_path / "a"
    path_b = tmp_path / "b"
    path_a.mkdir()
    path_b.mkdir()

    app = BrokkApp(workspace_dir=path_a)

    # Use a fake metadata object to capture updates without a full DOM/screen mount
    class FakeMetadata:
        def __init__(self):
            self.renderable = ""

        def update(self, text):
            self.renderable = text

    fake_meta = FakeMetadata()
    # Instantiate StatusLine manually to avoid ScreenStackError in unmounted test
    status_line = StatusLine()
    status_line._metadata = fake_meta
    # Inject it into app's query path for _update_statusline
    app._maybe_chat = lambda: MagicMock(
        query_one=lambda q, t: status_line if q == "#status-line" else MagicMock()
    )

    # 1. State for Worktree A
    app.current_model = "model-a"
    app.current_mode = "CODE"
    app._update_statusline()

    assert "model-a" in fake_meta.renderable
    assert "CODE" in fake_meta.renderable
    assert status_line._get_display_workspace(str(path_a)) in fake_meta.renderable

    # 2. Switch to Worktree B and verify sync
    app.current_worktree = path_b
    app.current_model = "model-b"
    app.current_mode = "LUTZ"
    app._update_statusline()

    assert "model-b" in fake_meta.renderable
    assert "LUTZ" in fake_meta.renderable
    assert status_line._get_display_workspace(str(path_b)) in fake_meta.renderable
