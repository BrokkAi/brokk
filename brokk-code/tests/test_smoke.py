from pathlib import Path

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorManager


def test_executor_manager_init():
    """Verify ExecutorManager can be instantiated with default paths."""
    workspace = Path("/tmp/fake-workspace")
    executor = ExecutorManager(workspace_dir=workspace)
    assert executor.workspace_dir == workspace.resolve()
    assert executor.session_id is None
    assert executor._process is None


def test_app_importable():
    """Verify BrokkApp can be instantiated without starting the event loop."""
    # We don't call .run() or ._start_executor() to avoid side effects
    app = BrokkApp(workspace_dir=Path("."))
    assert app.current_model == "gpt-5.2"
    assert app.executor is not None


def test_version():
    from brokk_code import __version__

    assert __version__ == "0.1.0"
