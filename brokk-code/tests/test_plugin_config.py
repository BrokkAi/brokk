import json
from pathlib import Path

import pytest

from brokk_code import __version__
from brokk_code.plugin_config import install_plugin


def test_install_plugin_creates_directory_structure(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    assert root == tmp_path / "brokk"
    assert root.is_dir()
    assert (root / ".claude-plugin").is_dir()
    assert (root / ".mcp.json").is_file()
    assert (root / ".claude-plugin" / "plugin.json").is_file()


def test_install_plugin_manifest_is_valid_json_with_required_fields(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    manifest = json.loads((root / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))
    assert manifest["name"] == "brokk"
    assert "description" in manifest
    assert manifest["version"] == __version__
    assert manifest["author"] == {"name": "Brokk AI"}
    assert isinstance(manifest["keywords"], list)
    assert len(manifest["keywords"]) > 0


def test_install_plugin_mcp_config_uses_default_uvx(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    mcp = json.loads((root / ".mcp.json").read_text(encoding="utf-8"))
    assert "mcpServers" in mcp
    assert mcp["mcpServers"]["brokk"]["command"] == "uvx"
    assert mcp["mcpServers"]["brokk"]["args"] == ["brokk", "mcp-core"]


def test_install_plugin_mcp_config_uses_custom_uvx(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk", uvx_command="/usr/local/bin/uvx")

    mcp = json.loads((root / ".mcp.json").read_text(encoding="utf-8"))
    assert mcp["mcpServers"]["brokk"]["command"] == "/usr/local/bin/uvx"
    assert mcp["mcpServers"]["brokk"]["args"] == ["brokk", "mcp-core"]


def test_install_plugin_creates_all_skill_directories(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    expected_skills = [
        "workspace",
        "code-navigation",
        "code-reading",
        "codebase-search",
        "git-exploration",
        "structured-data",
    ]
    for skill_name in expected_skills:
        skill_dir = root / skill_name
        assert skill_dir.is_dir(), f"Missing skill directory: {skill_name}"
        skill_file = skill_dir / "SKILL.md"
        assert skill_file.is_file(), f"Missing SKILL.md in: {skill_name}"


def test_install_plugin_skill_files_have_valid_frontmatter(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    for skill_dir in root.iterdir():
        if not skill_dir.is_dir() or skill_dir.name.startswith("."):
            continue
        skill_file = skill_dir / "SKILL.md"
        content = skill_file.read_text(encoding="utf-8")
        assert content.startswith("---"), f"{skill_dir.name}/SKILL.md missing YAML frontmatter"
        assert "name:" in content, f"{skill_dir.name}/SKILL.md missing name field"
        assert "description:" in content, f"{skill_dir.name}/SKILL.md missing description field"


def test_install_plugin_skill_content_mentions_tools(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    nav = (root / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchSymbols" in nav
    assert "scanUsages" in nav

    reading = (root / "code-reading" / "SKILL.md").read_text(encoding="utf-8")
    assert "getClassSources" in reading
    assert "getMethodSources" in reading

    search = (root / "codebase-search" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchFileContents" in search

    git = (root / "git-exploration" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchGitCommitMessages" in git

    data = (root / "structured-data" / "SKILL.md").read_text(encoding="utf-8")
    assert "jq" in data
    assert "xmlSelect" in data


def test_install_plugin_idempotent(tmp_path) -> None:
    """Re-installing does not corrupt existing files."""
    plugin_dir = tmp_path / "brokk"

    root1 = install_plugin(plugin_path=plugin_dir)
    manifest1 = (root1 / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp1 = (root1 / ".mcp.json").read_text(encoding="utf-8")
    skill1 = (root1 / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")

    root2 = install_plugin(plugin_path=plugin_dir)
    manifest2 = (root2 / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp2 = (root2 / ".mcp.json").read_text(encoding="utf-8")
    skill2 = (root2 / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")

    assert root1 == root2
    assert manifest1 == manifest2
    assert mcp1 == mcp2
    assert skill1 == skill2
    # Verify JSON is still valid after second pass
    json.loads(manifest2)
    json.loads(mcp2)


def test_install_plugin_default_path_under_home(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))
    # On Windows Path.home() may not respect HOME env, so we also
    # monkeypatch Path.home if needed
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))

    root = install_plugin()

    assert root == tmp_path / ".claude" / "plugins" / "brokk"
    assert root.is_dir()


def test_install_plugin_json_files_end_with_newline(tmp_path) -> None:
    root = install_plugin(plugin_path=tmp_path / "brokk")

    manifest_text = (root / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp_text = (root / ".mcp.json").read_text(encoding="utf-8")
    assert manifest_text.endswith("\n")
    assert mcp_text.endswith("\n")
