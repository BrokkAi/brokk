"""Utilities for resolving and installing uv/uvx."""

import logging
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

_UV_SETUP_LOCK_PATH: Optional[Path] = None
_UV_SETUP_LOCK_TIMEOUT_SECONDS = 120.0


class UvSetupError(Exception):
    """Raised when uv installation or resolution fails."""


def _is_pid_alive(pid: int) -> bool:
    """Best-effort process liveness check."""
    if pid <= 0:
        return False

    if sys.platform == "win32":
        import ctypes

        process_query_limited_information = 0x1000
        still_active = 259
        handle = ctypes.windll.kernel32.OpenProcess(process_query_limited_information, False, pid)
        if not handle:
            return False
        try:
            exit_code = ctypes.c_ulong()
            ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
            return bool(ok) and exit_code.value == still_active
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)

    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def resolve_uv_binary() -> Optional[str]:
    """Finds the uv binary on the system."""
    uv_path = shutil.which("uv")
    if uv_path:
        return uv_path

    home = Path.home()
    if sys.platform == "win32":
        candidates = [
            home / ".local" / "bin" / "uv.exe",
            home / ".cargo" / "bin" / "uv.exe",
        ]
    else:
        candidates = [
            home / ".local" / "bin" / "uv",
            home / ".cargo" / "bin" / "uv",
            Path("/opt/homebrew/bin/uv"),
            Path("/usr/local/bin/uv"),
        ]

    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    return None


def _uv_setup_lock():
    """File-based lock with PID stale detection for uv installation."""
    import contextlib

    @contextlib.contextmanager
    def _lock():
        lock_path = _UV_SETUP_LOCK_PATH or (Path.home() / ".brokk" / "uv-setup.lock")
        lock_path.parent.mkdir(parents=True, exist_ok=True)

        deadline = time.monotonic() + _UV_SETUP_LOCK_TIMEOUT_SECONDS
        acquired = False

        while not acquired:
            try:
                fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                try:
                    os.write(fd, str(os.getpid()).encode())
                finally:
                    os.close(fd)
                acquired = True
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise UvSetupError("Could not acquire uv setup lock")
                try:
                    pid_text = lock_path.read_text(encoding="utf-8").strip()
                    pid = int(pid_text)
                    if _is_pid_alive(pid):
                        time.sleep(0.5)
                    else:
                        lock_path.unlink(missing_ok=True)
                except Exception:
                    time.sleep(0.5)

        try:
            yield
        finally:
            lock_path.unlink(missing_ok=True)

    return _lock()


def ensure_uv_ready() -> str:
    """Ensures uv is installed. Returns the uv binary path.

    Idempotent and concurrency-safe.
    """
    # Fast path
    uv_path = resolve_uv_binary()
    if uv_path:
        return uv_path

    # Slow path: acquire lock and install
    with _uv_setup_lock():
        # Double-check after acquiring lock
        uv_path = resolve_uv_binary()
        if uv_path:
            return uv_path

        logger.info("Installing uv...")
        try:
            if sys.platform == "win32":
                cmd = [
                    "powershell",
                    "-ExecutionPolicy",
                    "ByPass",
                    "-Command",
                    "irm https://astral.sh/uv/install.ps1 | iex",
                ]
            else:
                cmd = ["bash", "-c", "curl -LsSf https://astral.sh/uv/install.sh | sh"]

            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120.0,
            )
            if proc.returncode != 0:
                stderr_hint = f": {proc.stderr.strip()}" if proc.stderr else ""
                raise UvSetupError(f"uv installer exited with code {proc.returncode}{stderr_hint}")
        except subprocess.TimeoutExpired:
            raise UvSetupError("uv installation timed out after 2 minutes")
        except UvSetupError:
            raise
        except Exception as e:
            raise UvSetupError(f"Failed to run uv installer: {e}")

        uv_path = resolve_uv_binary()
        if not uv_path:
            raise UvSetupError(
                "uv was installed but could not be found. You may need to restart your terminal."
            )

        return uv_path
