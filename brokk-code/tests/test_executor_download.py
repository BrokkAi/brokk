import subprocess
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from brokk_code.executor import ExecutorError, install_jbang, resolve_jbang_binary


def test_resolve_jbang_binary_path_mock(monkeypatch, tmp_path):
    """Test jbang resolution when it exists on PATH."""
    jbang_bin = tmp_path / "jbang"
    jbang_bin.write_text("stub")
    jbang_bin.chmod(0o755)

    monkeypatch.setattr("shutil.which", lambda x: str(jbang_bin) if x == "jbang" else None)

    assert resolve_jbang_binary() == str(jbang_bin)


def test_resolve_jbang_binary_common_locations(monkeypatch, tmp_path):
    """Test jbang resolution in common installation directories."""
    monkeypatch.setattr("shutil.which", lambda x: None)

    fake_home = tmp_path / "home"
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    jbang_bin = fake_home / ".jbang" / "bin" / "jbang"
    jbang_bin.parent.mkdir(parents=True)
    jbang_bin.write_text("stub")

    assert resolve_jbang_binary() == str(jbang_bin)


def test_install_jbang_success(monkeypatch):
    """Test successful jbang installation and catalog trust."""
    run_calls = []

    def fake_run(cmd, **kwargs):
        run_calls.append(cmd)
        return MagicMock(returncode=0)

    monkeypatch.setattr(subprocess, "run", fake_run)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/fixed/jbang")

    path = install_jbang()

    assert path == "/fixed/jbang"
    # Verify both install script and trust command were attempted
    assert any("jbang.dev" in str(c) for c in run_calls)
    assert any(
        any("trust" in str(arg) for arg in c) and any("brokk-releases" in str(arg) for arg in c)
        for c in run_calls
    )


def test_install_jbang_fails_on_subprocess_error(monkeypatch):
    """Test that install_jbang raises ExecutorError if the script fails."""

    def fake_run(cmd, **kwargs):
        if "jbang.dev" in str(cmd):
            return MagicMock(returncode=1, stderr="network error")
        return MagicMock(returncode=0)

    monkeypatch.setattr(subprocess, "run", fake_run)

    with pytest.raises(ExecutorError, match="jbang installer exited with code 1"):
        install_jbang()


def test_install_jbang_timeout(monkeypatch):
    """Test that install_jbang raises ExecutorError on timeout."""

    def fake_run_timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(args[0], 120)

    monkeypatch.setattr(subprocess, "run", fake_run_timeout)

    with pytest.raises(ExecutorError, match="jbang installation timed out"):
        install_jbang()
