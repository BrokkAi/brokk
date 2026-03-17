import asyncio
import sys

import pytest

from brokk_code.executor import ExecutorError, ExecutorManager


@pytest.mark.asyncio
async def test_executor_start_includes_jvm_flags(monkeypatch, tmp_path):
    # Arrange: explicit jar_path to force direct-Java mode
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd

        # Capture the command that was passed in
        captured_cmd = list(cmd)

        class FakeStdout:
            def __init__(self):
                self._lines = [
                    b"Some startup log\n",
                    b"Executor listening on http://127.0.0.1:12345\n",
                    b"",  # EOF
                ]
                self._idx = 0

            async def readline(self):
                # simulate async readline
                if self._idx < len(self._lines):
                    ln = self._lines[self._idx]
                    self._idx += 1
                    # small yield to ensure asyncio semantics
                    await asyncio.sleep(0)
                    return ln
                return b""

        class FakeStdin:
            def close(self):
                pass

            async def wait_closed(self):
                pass

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = FakeStdin() if stdin is not None else None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    # Monkeypatch asyncio.create_subprocess_exec
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    # Pass jar_path explicitly
    manager = ExecutorManager(workspace_dir=tmp_path, jar_path=dummy_jar)

    # Act: start (should read the listening line and complete)
    await manager.start()

    # Assert: captured_cmd was set and contains the JVM flags immediately after "java"
    assert captured_cmd is not None, "Subprocess exec was not called / cmd not captured"
    assert captured_cmd[0] == "java"

    # Find index of "-cp"
    try:
        cp_index = captured_cmd.index("-cp")
    except ValueError:
        pytest.fail("'-cp' not found in constructed command: %r" % captured_cmd)

    # Expect the two JVM flags to be immediately after "java" and before "-cp"
    # i.e., positions 1 and 2 relative to the list start (or equivalently, just before cp_index)
    assert cp_index >= 3, "expected JVM flags to appear before -cp"
    # locate flags positions
    flag1 = "-Djava.awt.headless=true"
    flag2 = "-Dapple.awt.UIElement=true"

    # They must both appear somewhere before '-cp'
    assert flag1 in captured_cmd[:cp_index], f"{flag1} missing in command: {captured_cmd}"
    assert flag2 in captured_cmd[:cp_index], f"{flag2} missing in command: {captured_cmd}"

    # And ensure their relative order is as requested (flag1 before flag2)
    idx1 = captured_cmd.index(flag1)
    idx2 = captured_cmd.index(flag2)
    assert idx1 < idx2 < cp_index, "JVM flags must appear in the correct order before -cp"

    # Cleanup
    await manager.stop()


@pytest.mark.asyncio
async def test_executor_start_includes_vendor_flag(monkeypatch, tmp_path):
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd
        captured_cmd = list(cmd)

        class FakeStdout:
            def __init__(self):
                self._lines = [
                    b"Executor listening on http://127.0.0.1:12345\n",
                    b"",
                ]
                self._idx = 0

            async def readline(self):
                if self._idx < len(self._lines):
                    ln = self._lines[self._idx]
                    self._idx += 1
                    await asyncio.sleep(0)
                    return ln
                return b""

        class FakeStdin:
            def close(self):
                pass

            async def wait_closed(self):
                pass

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = FakeStdin() if stdin is not None else None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path, jar_path=dummy_jar, vendor="OpenAI")
    await manager.start()

    assert captured_cmd is not None
    assert "--vendor" in captured_cmd
    idx = captured_cmd.index("--vendor")
    assert captured_cmd[idx + 1] == "OpenAI"

    await manager.stop()


@pytest.mark.asyncio
async def test_executor_start_includes_exit_on_stdin_eof_flag_when_enabled(monkeypatch, tmp_path):
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd
        captured_cmd = list(cmd)

        class FakeStdout:
            def __init__(self):
                self._lines = [
                    b"Executor listening on http://127.0.0.1:12345\n",
                    b"",
                ]
                self._idx = 0

            async def readline(self):
                if self._idx < len(self._lines):
                    ln = self._lines[self._idx]
                    self._idx += 1
                    await asyncio.sleep(0)
                    return ln
                return b""

        class FakeStdin:
            def close(self):
                pass

            async def wait_closed(self):
                pass

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = FakeStdin() if stdin is not None else None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path, jar_path=dummy_jar, exit_on_stdin_eof=True)
    await manager.start()

    assert captured_cmd is not None
    assert "--exit-on-stdin-eof" in captured_cmd

    await manager.stop()


@pytest.mark.asyncio
async def test_executor_exits_when_stdin_closed(monkeypatch, tmp_path):
    """Regression: closing the subprocess stdin should allow the child to exit.

    This test simulates a real subprocess where closing the stdin pipe causes the
    child process to observe EOF and exit. We monkeypatch asyncio.create_subprocess_exec
    to return a fake process whose stdin.close() sets returncode to a non-None value,
    and then assert that ExecutorManager.check_alive() eventually becomes False.
    """
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        class FakeStdout:
            def __init__(self):
                self._lines = [
                    b"Executor listening on http://127.0.0.1:54321\n",
                    b"",
                ]
                self._idx = 0

            async def readline(self):
                if self._idx < len(self._lines):
                    ln = self._lines[self._idx]
                    self._idx += 1
                    await asyncio.sleep(0)
                    return ln
                return b""

        class FakeStdin:
            def __init__(self, process):
                self._closed = False
                self._process = process

            def close(self):
                self._closed = True
                self._process.returncode = 0

            async def wait_closed(self):
                await asyncio.sleep(0)

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.returncode = None
                self.stdin = None

            async def wait(self):
                while self.returncode is None:
                    await asyncio.sleep(0.01)
                return self.returncode

            def terminate(self):
                if self.returncode is None:
                    self.returncode = -15

            def kill(self):
                if self.returncode is None:
                    self.returncode = -9

        proc = FakeProcess()
        proc.stdin = FakeStdin(proc)
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path, jar_path=dummy_jar)

    await manager.start()
    assert manager.check_alive() is True

    # Simulate parent death: close stdin without calling stop()
    # Access _stdin directly (test-only access pattern; see executor.py docstring)
    if manager._stdin is None:
        pytest.skip("No stdin stream available; cannot test stdin-closure behavior")

    manager._stdin.close()
    wait_closed = getattr(manager._stdin, "wait_closed", None)
    if callable(wait_closed):
        await wait_closed()

    # Poll for process exit with conservative timeout to avoid flakes
    deadline = asyncio.get_event_loop().time() + 5.0
    while asyncio.get_event_loop().time() < deadline:
        if not manager.check_alive():
            break
        await asyncio.sleep(0.05)

    assert manager.check_alive() is False, "Executor process did not exit after stdin was closed"

    # Cleanup remaining resources
    await manager.stop()


@pytest.mark.asyncio
async def test_executor_start_uses_jbang_when_no_jar(monkeypatch, tmp_path):
    import brokk_code.executor as executor_module

    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd
        captured_cmd = list(cmd)

        class FakeStdout:
            async def readline(self):
                return b"Executor listening on http://127.0.0.1:12345\n"

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(executor_module, "resolve_jbang_binary", lambda: "/usr/local/bin/jbang")
    monkeypatch.setattr(ExecutorManager, "_find_dev_jar", lambda self: None)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path)
    await manager.start()

    assert captured_cmd is not None
    assert captured_cmd[0] == "/usr/local/bin/jbang"
    # Check for -R followed by the combined options string
    idx = captured_cmd.index("-R")
    opts = captured_cmd[idx + 1]
    assert "-Djava.awt.headless=true" in opts
    assert "-Dapple.awt.UIElement=true" in opts
    assert "--enable-native-access=ALL-UNNAMED" in opts

    assert "--java" in captured_cmd
    assert "21" in captured_cmd
    # Verify the native access flag is present in the jbang command
    assert any("--enable-native-access=ALL-UNNAMED" in arg for arg in captured_cmd)
    assert "--main" in captured_cmd
    assert "ai.brokk.executor.HeadlessExecutorMain" in captured_cmd
    assert any(
        f"brokk-{executor_module.BUNDLED_EXECUTOR_VERSION}.jar" in arg for arg in captured_cmd
    )
    assert "--workspace-dir" in captured_cmd
    assert "--auth-token" in captured_cmd

    await manager.stop()


@pytest.mark.asyncio
async def test_executor_start_installs_jbang_if_missing(monkeypatch, tmp_path):
    import brokk_code.executor as executor_module

    install_called = False

    def fake_install():
        nonlocal install_called
        install_called = True
        return "/tmp/jbang"

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        class FakeStdout:
            async def readline(self):
                return b"Executor listening on http://127.0.0.1:12345\n"

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(executor_module, "resolve_jbang_binary", lambda: None)
    monkeypatch.setattr(executor_module, "install_jbang", fake_install)
    monkeypatch.setattr(ExecutorManager, "_find_dev_jar", lambda self: None)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path)
    await manager.start()

    assert install_called is True
    await manager.stop()


@pytest.mark.asyncio
async def test_executor_start_propagates_install_failure(monkeypatch, tmp_path):
    import brokk_code.executor as executor_module

    def fake_install_fail():
        raise ExecutorError("jbang installation failed (mock)")

    monkeypatch.setattr(executor_module, "resolve_jbang_binary", lambda: None)
    monkeypatch.setattr(executor_module, "install_jbang", fake_install_fail)
    monkeypatch.setattr(ExecutorManager, "_find_dev_jar", lambda self: None)

    manager = ExecutorManager(workspace_dir=tmp_path)

    with pytest.raises(ExecutorError, match=r"jbang installation failed \(mock\)$"):
        await manager.start()

    assert manager.check_alive() is False


@pytest.mark.asyncio
async def test_executor_start_includes_brokk_api_key_flag(monkeypatch, tmp_path):
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd
        captured_cmd = list(cmd)

        class FakeStdout:
            async def readline(self):
                return b"Executor listening on http://127.0.0.1:12345\n"

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = MagicMock()
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

        return FakeProcess()

    from unittest.mock import MagicMock

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(
        workspace_dir=tmp_path, jar_path=dummy_jar, brokk_api_key="sk-test-123"
    )
    await manager.start()

    assert captured_cmd is not None
    assert "--brokk-api-key" in captured_cmd
    idx = captured_cmd.index("--brokk-api-key")
    assert captured_cmd[idx + 1] == "sk-test-123"

    await manager.stop()

@pytest.mark.asyncio
async def test_executor_start_prefers_go_binary_when_available(monkeypatch, tmp_path):
    go_binary = tmp_path / ("brokk-go-executor.exe" if sys.platform == "win32" else "brokk-go-executor")
    go_binary.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdin=None, stdout=None, stderr=None, cwd=None):
        nonlocal captured_cmd
        captured_cmd = list(cmd)

        class FakeStdout:
            def __init__(self):
                self._lines = [
                    b"Executor listening on http://127.0.0.1:12345\n",
                    b"",
                ]
                self._idx = 0

            async def readline(self):
                if self._idx < len(self._lines):
                    ln = self._lines[self._idx]
                    self._idx += 1
                    await asyncio.sleep(0)
                    return ln
                return b""

        class FakeStdin:
            def close(self):
                pass

            async def wait_closed(self):
                pass

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.stdin = FakeStdin() if stdin is not None else None
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)
    monkeypatch.setattr("brokk_code.executor.resolve_go_executor_binary", lambda: str(go_binary))

    manager = ExecutorManager(workspace_dir=tmp_path)
    await manager.start()

    assert captured_cmd is not None
    assert captured_cmd[0] == str(go_binary)
    assert "java" not in captured_cmd[0].lower()

    await manager.stop()
