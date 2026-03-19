import time
from pathlib import Path

from brokk_code.runtime_utils import find_dev_jar


def test_find_dev_jar_finds_runnable_jar(tmp_path: Path):
    repo_root = tmp_path / "repo"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    runnable_jar = libs_dir / "brokk-1.0.0.jar"
    runnable_jar.write_text("dummy")

    assert find_dev_jar(repo_root) == runnable_jar


def test_find_dev_jar_returns_none_when_only_sources_and_javadoc_exist(tmp_path: Path):
    repo_root = tmp_path / "repo"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    (libs_dir / "brokk-1.0.0-sources.jar").write_text("sources")
    (libs_dir / "brokk-1.0.0-javadoc.jar").write_text("javadoc")

    assert find_dev_jar(repo_root) is None


def test_find_dev_jar_ignores_sources_and_javadoc_when_runnable_jar_exists(tmp_path: Path):
    repo_root = tmp_path / "repo"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    (libs_dir / "brokk-1.0.0-sources.jar").write_text("sources")
    (libs_dir / "brokk-1.0.0-javadoc.jar").write_text("javadoc")

    time.sleep(0.01)
    runnable_jar = libs_dir / "brokk-1.0.0.jar"
    runnable_jar.write_text("runnable")

    assert find_dev_jar(repo_root) == runnable_jar


def test_find_dev_jar_allows_shadow_and_all_jars(tmp_path: Path):
    repo_root = tmp_path / "repo"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    shadow_jar = libs_dir / "brokk-1.0.0-shadow.jar"
    shadow_jar.write_text("shadow")
    assert find_dev_jar(repo_root) == shadow_jar

    time.sleep(0.01)
    all_jar = libs_dir / "brokk-1.0.0-all.jar"
    all_jar.write_text("all")
    assert find_dev_jar(repo_root) == all_jar


def test_find_dev_jar_prefers_newest_mtime(tmp_path: Path):
    repo_root = tmp_path / "repo"
    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    old_jar = libs_dir / "brokk-1.0.0.jar"
    old_jar.write_text("old")

    time.sleep(0.01)

    new_jar = libs_dir / "brokk-1.1.0.jar"
    new_jar.write_text("new")

    assert find_dev_jar(repo_root) == new_jar


def test_find_dev_jar_returns_none_when_no_jar(tmp_path: Path):
    repo_root = tmp_path / "repo"
    repo_root.mkdir()
    (repo_root / "gradlew").write_text("")
    # No libs dir
    assert find_dev_jar(repo_root) is None


def test_find_dev_jar_searches_upward(tmp_path: Path):
    repo_root = tmp_path / "repo"
    nested = repo_root / "a" / "b" / "c"
    nested.mkdir(parents=True)

    libs_dir = repo_root / "app" / "build" / "libs"
    libs_dir.mkdir(parents=True)
    (repo_root / "gradlew").write_text("")

    runnable_jar = libs_dir / "brokk-1.0.0.jar"
    runnable_jar.write_text("dummy")

    assert find_dev_jar(nested) == runnable_jar
