import json
from pathlib import Path
from unittest.mock import patch

import pytest

from brokk_code import __version__
from brokk_code.plugin_config import install_plugin
from brokk_code.zed_config import ExistingBrokkCodeEntryError


def _plugin_root(marketplace_root: Path) -> Path:
    return marketplace_root / "plugins" / "brokk"


def test_install_plugin_creates_directory_structure(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    assert root == tmp_path / "brokk-marketplace"
    assert root.is_dir()
    assert (root / ".claude-plugin" / "marketplace.json").is_file()
    assert plugin.is_dir()
    assert (plugin / ".claude-plugin").is_dir()
    assert (plugin / ".mcp.json").is_file()
    assert (plugin / ".claude-plugin" / "plugin.json").is_file()


def test_install_plugin_marketplace_manifest(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")

    manifest = json.loads(
        (root / ".claude-plugin" / "marketplace.json").read_text(encoding="utf-8")
    )
    assert manifest["name"] == "brokk-local"
    assert manifest["owner"] == {"name": "Brokk AI"}
    assert len(manifest["plugins"]) == 1
    assert manifest["plugins"][0]["name"] == "brokk"
    assert manifest["plugins"][0]["source"] == "./plugins/brokk"


def test_install_plugin_manifest_is_valid_json_with_required_fields(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    manifest = json.loads((plugin / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))
    assert manifest["name"] == "brokk"
    assert "description" in manifest
    assert manifest["version"] == __version__
    assert manifest["author"] == {"name": "Brokk AI"}
    assert isinstance(manifest["keywords"], list)
    assert len(manifest["keywords"]) > 0


def test_install_plugin_mcp_config_uses_default_uvx(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    mcp = json.loads((plugin / ".mcp.json").read_text(encoding="utf-8"))
    assert mcp["brokk"]["command"] == "uvx"
    assert mcp["brokk"]["args"] == ["brokk", "mcp-core"]


def test_install_plugin_mcp_config_uses_custom_uvx(tmp_path) -> None:
    root, _ = install_plugin(
        marketplace_path=tmp_path / "brokk-marketplace",
        uvx_command="/usr/local/bin/uvx",
    )
    plugin = _plugin_root(root)

    mcp = json.loads((plugin / ".mcp.json").read_text(encoding="utf-8"))
    assert mcp["brokk"]["command"] == "/usr/local/bin/uvx"
    assert mcp["brokk"]["args"] == ["brokk", "mcp-core"]


def test_install_plugin_creates_all_skill_directories(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    expected_skills = [
        "workspace",
        "code-navigation",
        "code-reading",
        "codebase-search",
        "git-exploration",
        "structured-data",
    ]
    for skill_name in expected_skills:
        skill_dir = plugin / "skills" / skill_name
        assert skill_dir.is_dir(), f"Missing skill directory: {skill_name}"
        skill_file = skill_dir / "SKILL.md"
        assert skill_file.is_file(), f"Missing SKILL.md in: {skill_name}"


def test_install_plugin_skill_files_have_valid_frontmatter(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    for skill_dir in (plugin / "skills").iterdir():
        if not skill_dir.is_dir():
            continue
        skill_file = skill_dir / "SKILL.md"
        content = skill_file.read_text(encoding="utf-8")
        assert content.startswith("---"), f"{skill_dir.name}/SKILL.md missing YAML frontmatter"
        assert "name:" in content, f"{skill_dir.name}/SKILL.md missing name field"
        assert "description:" in content, f"{skill_dir.name}/SKILL.md missing description field"


def test_install_plugin_skill_content_mentions_tools(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    nav = (plugin / "skills" / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchSymbols" in nav
    assert "scanUsages" in nav

    reading = (plugin / "skills" / "code-reading" / "SKILL.md").read_text(encoding="utf-8")
    assert "getClassSources" in reading
    assert "getMethodSources" in reading

    search = (plugin / "skills" / "codebase-search" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchFileContents" in search

    git = (plugin / "skills" / "git-exploration" / "SKILL.md").read_text(encoding="utf-8")
    assert "searchGitCommitMessages" in git

    data = (plugin / "skills" / "structured-data" / "SKILL.md").read_text(encoding="utf-8")
    assert "jq" in data
    assert "xmlSelect" in data


def test_install_plugin_idempotent(tmp_path) -> None:
    """Re-installing with force=True does not corrupt existing files."""
    marketplace_dir = tmp_path / "brokk-marketplace"

    root1, was_reinstall1 = install_plugin(marketplace_path=marketplace_dir)
    plugin1 = _plugin_root(root1)
    manifest1 = (plugin1 / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp1 = (plugin1 / ".mcp.json").read_text(encoding="utf-8")
    skill1 = (plugin1 / "skills" / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")

    root2, was_reinstall2 = install_plugin(marketplace_path=marketplace_dir, force=True)
    plugin2 = _plugin_root(root2)
    manifest2 = (plugin2 / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp2 = (plugin2 / ".mcp.json").read_text(encoding="utf-8")
    skill2 = (plugin2 / "skills" / "code-navigation" / "SKILL.md").read_text(encoding="utf-8")

    assert not was_reinstall1
    assert was_reinstall2
    assert root1 == root2
    assert manifest1 == manifest2
    assert mcp1 == mcp2
    assert skill1 == skill2
    # Verify JSON is still valid after second pass
    json.loads(manifest2)
    json.loads(mcp2)


def test_install_plugin_default_path_under_home(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))

    root, _ = install_plugin()

    assert root == tmp_path / ".claude" / "plugins" / "brokk-marketplace"
    assert root.is_dir()


def test_install_plugin_json_files_end_with_newline(tmp_path) -> None:
    root, _ = install_plugin(marketplace_path=tmp_path / "brokk-marketplace")
    plugin = _plugin_root(root)

    manifest_text = (plugin / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    mcp_text = (plugin / ".mcp.json").read_text(encoding="utf-8")
    assert manifest_text.endswith("\n")
    assert mcp_text.endswith("\n")


def test_install_plugin_refuses_overwrite_without_force(tmp_path) -> None:
    """Reinstall without --force raises ExistingBrokkCodeEntryError."""
    marketplace_dir = tmp_path / "brokk-marketplace"
    install_plugin(marketplace_path=marketplace_dir)

    with pytest.raises(ExistingBrokkCodeEntryError, match="already exists"):
        install_plugin(marketplace_path=marketplace_dir)


def test_install_plugin_preserves_user_edits_without_force(tmp_path) -> None:
    """User-modified files under the plugin dir are not clobbered without --force."""
    marketplace_dir = tmp_path / "brokk-marketplace"
    install_plugin(marketplace_path=marketplace_dir)

    skill_file = _plugin_root(marketplace_dir) / "skills" / "workspace" / "SKILL.md"
    skill_file.write_text("user-customized content", encoding="utf-8")

    with pytest.raises(ExistingBrokkCodeEntryError):
        install_plugin(marketplace_path=marketplace_dir)

    assert skill_file.read_text(encoding="utf-8") == "user-customized content"


def test_install_plugin_calls_claude_cli_when_available(tmp_path) -> None:
    """When claude binary is found, marketplace add and plugin install are called."""
    marketplace_dir = tmp_path / "brokk-marketplace"

    with (
        patch("shutil.which", return_value="/usr/bin/claude"),
        patch("subprocess.run") as mock_run,
    ):
        install_plugin(marketplace_path=marketplace_dir)

    calls = mock_run.call_args_list
    assert len(calls) == 2
    # First call: marketplace add
    assert calls[0].args[0] == [
        "/usr/bin/claude",
        "plugin",
        "marketplace",
        "add",
        str(marketplace_dir),
    ]
    # Second call: plugin install
    assert calls[1].args[0] == [
        "/usr/bin/claude",
        "plugin",
        "install",
        "brokk@brokk-local",
    ]
