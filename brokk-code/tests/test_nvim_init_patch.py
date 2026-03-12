from brokk_code.nvim_init_patch import wire_nvim_plugin_setup


def test_wire_nvim_plugin_setup_missing_init(tmp_path) -> None:
    result = wire_nvim_plugin_setup(
        plugin_repo="olimorris/codecompanion.nvim",
        module_name="brokk.brokk_codecompanion",
        init_path=tmp_path / "init.lua",
    )
    assert result.status == "missing"


def test_wire_nvim_plugin_setup_patches_simple_opts_block(tmp_path) -> None:
    init_path = tmp_path / "init.lua"
    init_path.write_text(
        """require("lazy").setup({
  {
    "olimorris/codecompanion.nvim",
    opts = {},
  },
})
""",
        encoding="utf-8",
    )

    result = wire_nvim_plugin_setup(
        plugin_repo="olimorris/codecompanion.nvim",
        module_name="brokk.brokk_codecompanion",
        init_path=init_path,
    )
    assert result.status == "patched"
    text = init_path.read_text(encoding="utf-8")
    assert "opts = function()" in text
    assert 'require("brokk.brokk_codecompanion")' in text


def test_wire_nvim_plugin_setup_reports_already_configured(tmp_path) -> None:
    init_path = tmp_path / "init.lua"
    init_path.write_text(
        """require("lazy").setup({
  {
    "olimorris/codecompanion.nvim",
    opts = function()
      return require("brokk.brokk_codecompanion")
    end,
  },
})
""",
        encoding="utf-8",
    )

    result = wire_nvim_plugin_setup(
        plugin_repo="olimorris/codecompanion.nvim",
        module_name="brokk.brokk_codecompanion",
        init_path=init_path,
    )
    assert result.status == "already_configured"


def test_wire_nvim_plugin_setup_refuses_complex_opts_patch(tmp_path) -> None:
    init_path = tmp_path / "init.lua"
    init_path.write_text(
        """require("lazy").setup({
  {
    "olimorris/codecompanion.nvim",
    opts = {
      foo = true,
    },
  },
})
""",
        encoding="utf-8",
    )

    result = wire_nvim_plugin_setup(
        plugin_repo="olimorris/codecompanion.nvim",
        module_name="brokk.brokk_codecompanion",
        init_path=init_path,
    )
    assert result.status == "unsupported"
    assert result.detail is not None
