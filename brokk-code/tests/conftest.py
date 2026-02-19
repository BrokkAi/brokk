from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def isolate_home(tmp_path, monkeypatch):
    """Prevent tests from reading/writing real ~/.brokk state and current directory."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Isolate CWD to prevent accidental writes to the repo root
    cwd = tmp_path / "cwd"
    cwd.mkdir(parents=True, exist_ok=True)
    monkeypatch.chdir(cwd)
