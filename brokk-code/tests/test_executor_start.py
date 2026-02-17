import asyncio

import pytest

from brokk_code.executor import ExecutorManager


@pytest.mark.asyncio
async def test_executor_start_includes_jvm_flags(monkeypatch, tmp_path):
    # Arrange: stub _find_jar to return a dummy path
    dummy_jar = tmp_path / "brokk.jar"
    dummy_jar.write_text("dummy")
    captured_cmd = None

    async def fake_create_subprocess_exec(*cmd, stdout=None, stderr=None):
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

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    # Monkeypatch _find_jar and asyncio.create_subprocess_exec
    monkeypatch.setattr(ExecutorManager, "_find_jar", lambda self: dummy_jar)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path)

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

    async def fake_create_subprocess_exec(*cmd, stdout=None, stderr=None):
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

        class FakeProcess:
            def __init__(self):
                self.stdout = FakeStdout()
                self.returncode = None

            async def wait(self):
                return 0

            def terminate(self):
                pass

            def kill(self):
                pass

        return FakeProcess()

    monkeypatch.setattr(ExecutorManager, "_find_jar", lambda self: dummy_jar)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)

    manager = ExecutorManager(workspace_dir=tmp_path, vendor="OpenAI")
    await manager.start()

    assert captured_cmd is not None
    assert "--vendor" in captured_cmd
    idx = captured_cmd.index("--vendor")
    assert captured_cmd[idx + 1] == "OpenAI"

    await manager.stop()
