from pathlib import Path


def test_cwd_is_isolated(tmp_path):
    """
    Verify that the current working directory is inside the test's tmp_path.
    This ensures that conftest.py's isolate_home fixture is working correctly.
    """
    cwd = Path.cwd().resolve()
    expected = (tmp_path / "cwd").resolve()

    assert cwd == expected

    # Verify that writing to a relative path stays within the isolated directory
    test_file = Path("isolated_test_file.txt")
    test_file.write_text("isolated content")

    assert test_file.exists()
    assert (expected / "isolated_test_file.txt").exists()
