from pathlib import Path

import pytest

from brokk_code.rust_acp_install import (
    RustAcpInstallError,
    resolve_rust_paths,
)


def _stub_executable(parent_dir: Path, name: str) -> Path:
    parent_dir.mkdir(parents=True, exist_ok=True)
    binary = parent_dir / name
    binary.write_text("#!/bin/sh\nexit 0\n")
    binary.chmod(0o755)
    return binary


def test_resolve_uses_path_when_no_override(monkeypatch, tmp_path) -> None:
    bin_dir = tmp_path / "stub-bin"
    bin_dir.mkdir()
    brokk_acp = _stub_executable(bin_dir, "brokk-acp")
    bifrost = _stub_executable(bin_dir, "bifrost")
    monkeypatch.setenv("PATH", str(bin_dir))

    resolved_brokk_acp, resolved_bifrost = resolve_rust_paths(brokk_acp_override=None)

    assert resolved_brokk_acp == brokk_acp.resolve()
    assert resolved_bifrost == bifrost.resolve()


def test_override_skips_path_lookup_for_brokk_acp(monkeypatch, tmp_path) -> None:
    bin_dir = tmp_path / "stub-bin"
    bin_dir.mkdir()
    bifrost = _stub_executable(bin_dir, "bifrost")
    monkeypatch.setenv("PATH", str(bin_dir))

    custom_brokk_acp = _stub_executable(tmp_path / "elsewhere", "brokk-acp-dev")

    resolved_brokk_acp, resolved_bifrost = resolve_rust_paths(
        brokk_acp_override=custom_brokk_acp
    )

    assert resolved_brokk_acp == custom_brokk_acp.resolve()
    assert resolved_bifrost == bifrost.resolve()


def test_missing_brokk_acp_on_path_errors(monkeypatch, tmp_path) -> None:
    bin_dir = tmp_path / "empty"
    bin_dir.mkdir()
    monkeypatch.setenv("PATH", str(bin_dir))

    with pytest.raises(RustAcpInstallError, match="'brokk-acp' not found on PATH"):
        resolve_rust_paths(brokk_acp_override=None)


def test_missing_bifrost_on_path_errors(monkeypatch, tmp_path) -> None:
    # brokk-acp on PATH, bifrost is not.
    bin_dir = tmp_path / "stub-bin"
    bin_dir.mkdir()
    _stub_executable(bin_dir, "brokk-acp")
    monkeypatch.setenv("PATH", str(bin_dir))

    with pytest.raises(RustAcpInstallError, match="'bifrost' not found on PATH"):
        resolve_rust_paths(brokk_acp_override=None)


def test_override_path_must_exist(tmp_path) -> None:
    with pytest.raises(RustAcpInstallError, match="brokk-acp binary not found"):
        resolve_rust_paths(brokk_acp_override=tmp_path / "does-not-exist")


def test_override_path_must_be_a_file(monkeypatch, tmp_path) -> None:
    bin_dir = tmp_path / "stub-bin"
    bin_dir.mkdir()
    _stub_executable(bin_dir, "bifrost")
    monkeypatch.setenv("PATH", str(bin_dir))

    a_directory = tmp_path / "not-a-file"
    a_directory.mkdir()

    with pytest.raises(RustAcpInstallError, match="not a regular file"):
        resolve_rust_paths(brokk_acp_override=a_directory)
