import sys
import types
from typing import Any

import pytest


def test_main_sessions_routes_to_tui_with_picker(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    import brokk_code.__main__ as cli

    # Avoid any git-root inference; keep workspace stable.
    monkeypatch.setattr(cli, "resolve_workspace_dir", lambda p: p)

    created: dict[str, Any] = {}

    class FakeExecutor:
        def check_alive(self) -> bool:
            return False

    class FakeApp:
        def __init__(self, **kwargs: Any) -> None:
            created.update(kwargs)
            self.executor = FakeExecutor()

        def run(self) -> None:
            return

    # main() imports BrokkApp from brokk_code.app at runtime.
    import brokk_code.app as app_module

    monkeypatch.setattr(app_module, "BrokkApp", FakeApp)

    # main() imports session_persistence after app.run(); stub it to keep the test hermetic.
    stub_session_persistence = types.ModuleType("brokk_code.session_persistence")
    stub_session_persistence.load_last_session_id = lambda _workspace: None
    stub_session_persistence.get_session_zip_resume_path = (
        lambda workspace, sid: tmp_path / f"{sid}.zip"
    )
    stub_session_persistence.has_tasks = lambda _zip_path: False
    monkeypatch.setitem(sys.modules, "brokk_code.session_persistence", stub_session_persistence)

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "sessions",
            "--workspace",
            str(tmp_path),
        ],
    )

    cli.main()

    assert created["pick_session"] is True
    assert created["session_id"] is None
    assert created["resume_session"] is False


def test_main_sessions_rejects_positional_args(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    import brokk_code.__main__ as cli

    monkeypatch.setattr(cli, "resolve_workspace_dir", lambda p: p)

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "brokk",
            "sessions",
            "extra-arg",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code != 0
