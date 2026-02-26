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
        return MagicMock(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/fixed/jbang")

    path = install_jbang()

    assert path == "/fixed/jbang"
    # Verify both install script and trust commands were attempted
    assert any("jbang.dev" in str(c) for c in run_calls)

    # Verify both specific trust URLs were added
    trust_calls = [
        c[3]
        for c in run_calls
        if isinstance(c, list) and len(c) > 3 and c[1] == "trust" and c[2] == "add"
    ]
    assert "https://github.com/BrokkAi/brokk-releases" in trust_calls
    assert "https://github.com/BrokkAi/brokk-releases/releases/download/" in trust_calls


def test_install_jbang_fails_on_subprocess_error(monkeypatch):
    """Test that install_jbang raises ExecutorError if the script fails."""

    def fake_run(cmd, **kwargs):
        if any("jbang.dev" in str(arg) for arg in (cmd if isinstance(cmd, list) else [cmd])):
            return MagicMock(returncode=1, stderr="network error")
        return MagicMock(returncode=0)

    monkeypatch.setattr(subprocess, "run", fake_run)

    with pytest.raises(ExecutorError, match="jbang installer exited with code 1: network error"):
        install_jbang()


def test_install_jbang_tolerates_trust_failure(monkeypatch, caplog):
    """Test that install_jbang succeeds even if the trust command fails."""
    # Capture at INFO to see the "Installing jbang..." log, but we specifically check for WARNING
    caplog.set_level("INFO")

    def fake_run(cmd, **kwargs):
        if isinstance(cmd, list) and len(cmd) > 1 and cmd[1] == "trust":
            m = MagicMock()
            m.returncode = 1
            m.stderr = "trust failed"
            return m
        m = MagicMock()
        m.returncode = 0
        m.stdout = ""
        m.stderr = ""
        return m

    monkeypatch.setattr(subprocess, "run", fake_run)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/fixed/jbang")

    path = install_jbang()
    assert path == "/fixed/jbang"
    # It should log for both URLs if they fail
    assert "Failed to trust https://github.com/BrokkAi/brokk-releases: trust failed" in caplog.text
    assert (
        "Failed to trust https://github.com/BrokkAi/brokk-releases/releases/download/: trust failed"
        in caplog.text
    )


def test_install_jbang_timeout(monkeypatch):
    """Test that install_jbang raises ExecutorError on timeout."""

    def fake_run_timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(args[0], 120)

    monkeypatch.setattr(subprocess, "run", fake_run_timeout)

    with pytest.raises(ExecutorError, match="jbang installation timed out"):
        install_jbang()
