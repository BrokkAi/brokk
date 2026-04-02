import subprocess
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from brokk_code.executor import (
    ExecutorError,
    _is_jbang_trusted,
    ensure_jbang_ready,
    resolve_jbang_binary,
)


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


# --- _is_jbang_trusted tests ---


def test_is_jbang_trusted_both_present(monkeypatch, tmp_path):
    """Returns True when both brokk URLs are in trusted-sources.json."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    trusted_file = jbang_dir / "trusted-sources.json"
    trusted_file.write_text(
        '{"trustedSources": ["https://github.com/BrokkAi/brokk-releases", '
        '"https://github.com/BrokkAi/brokk-releases/releases/download/"]}'
    )
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    assert _is_jbang_trusted() is True


def test_is_jbang_trusted_missing_file(monkeypatch, tmp_path):
    """Returns False when trusted-sources.json does not exist."""
    fake_home = tmp_path / "home"
    fake_home.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    assert _is_jbang_trusted() is False


def test_is_jbang_trusted_malformed_json(monkeypatch, tmp_path):
    """Returns False when trusted-sources.json contains invalid JSON."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    (jbang_dir / "trusted-sources.json").write_text("not valid json{{{")
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    assert _is_jbang_trusted() is False


def test_is_jbang_trusted_only_one_url(monkeypatch, tmp_path):
    """Returns False when only one of the two brokk URLs is trusted."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    (jbang_dir / "trusted-sources.json").write_text(
        '{"trustedSources": ["https://github.com/BrokkAi/brokk-releases"]}'
    )
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    assert _is_jbang_trusted() is False


# --- ensure_jbang_ready tests ---


def test_ensure_jbang_ready_fast_path(monkeypatch, tmp_path):
    """Fast path: jbang present + trusted → returns immediately, no subprocess."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    (jbang_dir / "trusted-sources.json").write_text(
        '{"trustedSources": ["https://github.com/BrokkAi/brokk-releases", '
        '"https://github.com/BrokkAi/brokk-releases/releases/download/"]}'
    )
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/usr/bin/jbang")

    run_calls = []
    monkeypatch.setattr(
        subprocess, "run", lambda *a, **kw: run_calls.append(a) or MagicMock(returncode=0)
    )

    result = ensure_jbang_ready()

    assert result == "/usr/bin/jbang"
    assert run_calls == [], "No subprocess should run on fast path"


def test_ensure_jbang_ready_install_path(monkeypatch, tmp_path):
    """Install path: jbang missing → calls install script + trust commands."""
    fake_home = tmp_path / "home"
    fake_home.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    call_count = {"n": 0}

    def fake_resolve():
        # First call (before install) returns None, subsequent calls return path
        call_count["n"] += 1
        return None if call_count["n"] <= 2 else "/installed/jbang"

    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", fake_resolve)

    run_calls = []

    def fake_run(cmd, **kwargs):
        run_calls.append(cmd)
        return MagicMock(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    result = ensure_jbang_ready()

    assert result == "/installed/jbang"
    # Install script was called
    assert any("jbang.dev" in str(c) for c in run_calls)
    # Trust commands were called for both URLs
    trust_urls = [
        c[3]
        for c in run_calls
        if isinstance(c, list) and len(c) > 3 and c[1] == "trust" and c[2] == "add"
    ]
    assert "https://github.com/BrokkAi/brokk-releases" in trust_urls
    assert "https://github.com/BrokkAi/brokk-releases/releases/download/" in trust_urls


def test_ensure_jbang_ready_trust_path(monkeypatch, tmp_path):
    """Trust path: jbang present but not trusted → skips install, runs trust only."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    # No trusted-sources.json → not trusted
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/usr/bin/jbang")

    run_calls = []

    def fake_run(cmd, **kwargs):
        run_calls.append(cmd)
        return MagicMock(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    result = ensure_jbang_ready()

    assert result == "/usr/bin/jbang"
    # Install script was NOT called
    assert not any("jbang.dev" in str(c) for c in run_calls)
    # Trust commands were called for both URLs
    trust_urls = [
        c[3]
        for c in run_calls
        if isinstance(c, list) and len(c) > 3 and c[1] == "trust" and c[2] == "add"
    ]
    assert "https://github.com/BrokkAi/brokk-releases" in trust_urls
    assert "https://github.com/BrokkAi/brokk-releases/releases/download/" in trust_urls


def test_ensure_jbang_ready_concurrency(monkeypatch, tmp_path):
    """Concurrency: two threads calling simultaneously — only one does setup work."""
    fake_home = tmp_path / "home"
    fake_home.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    setup_count = {"n": 0}
    setup_lock = threading.Lock()

    call_count = {"n": 0}

    def fake_resolve():
        with setup_lock:
            call_count["n"] += 1
        # Before any setup: return None; after first setup done: return path
        return None if setup_count["n"] == 0 else "/installed/jbang"

    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", fake_resolve)

    def fake_run(cmd, **kwargs):
        if any("jbang.dev" in str(arg) for arg in (cmd if isinstance(cmd, list) else [cmd])):
            with setup_lock:
                setup_count["n"] += 1
            time.sleep(0.05)  # Simulate some work
        return MagicMock(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    results = {}
    errors = {}

    def run_thread(tid):
        try:
            results[tid] = ensure_jbang_ready()
        except Exception as e:
            errors[tid] = e

    threads = [threading.Thread(target=run_thread, args=(i,)) for i in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)

    assert not errors, f"Threads raised errors: {errors}"
    # At most one install script invocation (the second thread sees the lock)
    assert setup_count["n"] <= 1


def test_ensure_jbang_ready_stale_lock(monkeypatch, tmp_path):
    """Stale lock: lock file exists with a dead PID → stale detection, proceeds normally."""

    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    # Write a lock file with a PID that doesn't exist
    lock_path = jbang_dir / "brokk-setup.lock"
    lock_path.write_text("999999999")  # Extremely unlikely to be a live PID

    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/usr/bin/jbang")
    monkeypatch.setattr(
        subprocess, "run", lambda *a, **kw: MagicMock(returncode=0, stdout="", stderr="")
    )

    # Should succeed despite stale lock
    result = ensure_jbang_ready()
    assert result == "/usr/bin/jbang"


def test_ensure_jbang_ready_lock_timeout(monkeypatch, tmp_path):
    """Lock timeout: lock held by live PID for > timeout → raises ExecutorError."""
    import os as _os

    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)

    # Write a lock file with our own PID (which is definitely alive)
    lock_path = jbang_dir / "brokk-setup.lock"
    lock_path.write_text(str(_os.getpid()))

    # Set a very short timeout
    monkeypatch.setattr("brokk_code.executor._JBANG_SETUP_LOCK_TIMEOUT_SECONDS", 0.1)
    monkeypatch.setattr("brokk_code.executor._JBANG_SETUP_LOCK_PATH", lock_path)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: None)

    with pytest.raises(ExecutorError, match="Could not acquire jbang setup lock"):
        ensure_jbang_ready()


def test_ensure_jbang_ready_tolerates_trust_failure(monkeypatch, tmp_path, caplog):
    """Trust failure is logged as warning but doesn't raise."""
    fake_home = tmp_path / "home"
    jbang_dir = fake_home / ".jbang"
    jbang_dir.mkdir(parents=True)
    monkeypatch.setattr(Path, "home", lambda: fake_home)
    monkeypatch.setattr("brokk_code.executor.resolve_jbang_binary", lambda: "/usr/bin/jbang")

    caplog.set_level("WARNING")

    def fake_run(cmd, **kwargs):
        if isinstance(cmd, list) and len(cmd) > 1 and cmd[1] == "trust":
            return MagicMock(returncode=1, stdout="", stderr="already trusted")
        return MagicMock(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    result = ensure_jbang_ready()
    assert result == "/usr/bin/jbang"
    assert "Failed to trust" in caplog.text
