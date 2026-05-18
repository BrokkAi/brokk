from pathlib import Path

import pytest

from brokk_code.rust_acp_install import (
    RustAcpInstallError,
    resolve_rust_paths,
)


def test_resolve_defaults_to_literal_binary_names() -> None:
    anvil, bifrost = resolve_rust_paths(anvil_override=None)
    assert anvil == Path("anvil")
    assert bifrost == Path("bifrost")


def test_override_path_used_verbatim(tmp_path) -> None:
    custom = tmp_path / "elsewhere" / "anvil-dev"
    custom.parent.mkdir(parents=True)
    custom.write_text("stub")

    anvil, bifrost = resolve_rust_paths(anvil_override=custom)

    assert anvil == custom
    assert bifrost == Path("bifrost")


def test_override_path_must_exist(tmp_path) -> None:
    with pytest.raises(RustAcpInstallError, match="anvil binary not found"):
        resolve_rust_paths(anvil_override=tmp_path / "does-not-exist")


def test_override_path_must_be_a_file(tmp_path) -> None:
    a_directory = tmp_path / "not-a-file"
    a_directory.mkdir()

    with pytest.raises(RustAcpInstallError, match="not a regular file"):
        resolve_rust_paths(anvil_override=a_directory)
