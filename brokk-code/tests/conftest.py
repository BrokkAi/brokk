from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def isolate_home(tmp_path, monkeypatch):
    """Prevent tests from reading/writing real ~/.brokk state."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
