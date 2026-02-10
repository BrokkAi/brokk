import pytest
from pathlib import Path
from brokk_code.executor import ExecutorManager, ExecutorError


def test_add_context_text_validation_blank():
    """Verify add_context_text raises error for blank strings before network calls."""
    executor = ExecutorManager(workspace_dir=Path("."))

    with pytest.raises(ExecutorError, match="Text must not be blank"):
        # Use a non-async wrapper if needed, but executor.py shows it is async.
        # We can test the validation logic by running the coroutine.
        import asyncio

        asyncio.run(executor.add_context_text("   "))


def test_add_context_text_validation_too_large():
    """Verify add_context_text raises error for strings exceeding 1 MiB."""
    executor = ExecutorManager(workspace_dir=Path("."))

    # 1 MiB + 1 byte
    large_string = "A" * (1024 * 1024 + 1)

    with pytest.raises(ExecutorError, match="Text exceeds maximum size of 1 MiB"):
        import asyncio

        asyncio.run(executor.add_context_text(large_string))
