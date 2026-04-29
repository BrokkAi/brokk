import hashlib
import io
import os
import sys
import tarfile
from pathlib import Path

import pytest

from brokk_code import bifrost_launcher, mcp_launcher, rust_acp_install


def test_run_bifrost_server_uses_override_and_execs_searchtools(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    binary = tmp_path / "bifrost"
    binary.write_text("stub")
    binary.chmod(0o755)

    def fake_chdir(path: Path) -> None:
        captured["cwd"] = path

    def fake_execvpe(binary_arg: str, command: list[str], env: dict[str, str]) -> None:
        captured["binary"] = binary_arg
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", fake_chdir)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(RuntimeError, match="stop"):
        bifrost_launcher.run_bifrost_server(
            workspace_dir=tmp_path,
            binary_override=binary,
        )

    assert captured["cwd"] == tmp_path.resolve()
    assert captured["binary"] == str(binary)
    assert captured["command"] == [
        str(binary),
        "--root",
        str(tmp_path.resolve()),
        "--server",
        "searchtools",
    ]


def test_run_bifrost_server_forwards_passthrough_args(monkeypatch, tmp_path) -> None:
    captured: dict[str, object] = {}

    binary = tmp_path / "bifrost"
    binary.write_text("stub")
    binary.chmod(0o755)

    def fake_execvpe(_b: str, command: list[str], _env: dict[str, str]) -> None:
        captured["command"] = command
        raise RuntimeError("stop")

    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(os, "chdir", lambda _p: None)
    monkeypatch.setattr(os, "execvpe", fake_execvpe)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(RuntimeError, match="stop"):
        bifrost_launcher.run_bifrost_server(
            workspace_dir=tmp_path,
            binary_override=binary,
            passthrough_args=["--debug", "--log-level", "trace"],
        )

    command = captured["command"]
    assert command[-3:] == ["--debug", "--log-level", "trace"]
    assert "--server" in command
    assert command[command.index("--server") + 1] == "searchtools"


def test_run_bifrost_server_reports_install_error(monkeypatch, tmp_path, capsys) -> None:
    def fake_resolve(**_kwargs: object) -> Path:
        raise rust_acp_install.BifrostInstallError("no asset for platform")

    monkeypatch.setattr(bifrost_launcher, "resolve_bifrost_binary", fake_resolve)
    monkeypatch.setattr(mcp_launcher, "git_toplevel_for", lambda _path: None)

    with pytest.raises(SystemExit) as excinfo:
        bifrost_launcher.run_bifrost_server(
            workspace_dir=tmp_path,
            binary_override=None,
        )

    assert excinfo.value.code == 1
    err = capsys.readouterr().err
    assert "no asset for platform" in err


def test_resolve_bifrost_binary_prefers_override(tmp_path) -> None:
    binary = tmp_path / "custom-bifrost"
    binary.write_text("")
    resolved = rust_acp_install.resolve_bifrost_binary(override=binary)
    assert resolved == binary


def test_resolve_bifrost_binary_uses_path_when_available(monkeypatch, tmp_path) -> None:
    found = tmp_path / "bifrost"
    found.write_text("")
    monkeypatch.setattr(rust_acp_install.shutil, "which", lambda _name: str(found))
    resolved = rust_acp_install.resolve_bifrost_binary(override=None)
    assert resolved == found


def test_resolve_bifrost_binary_uses_cache_without_download(monkeypatch, tmp_path) -> None:
    cache_root = tmp_path / "cache"

    def fake_cache_path(version: str) -> Path:
        triple_dir = cache_root / "bifrost" / version / "fake-triple"
        triple_dir.mkdir(parents=True, exist_ok=True)
        return triple_dir / "bifrost"

    monkeypatch.setattr(rust_acp_install.shutil, "which", lambda _n: None)
    monkeypatch.setattr(rust_acp_install, "_bifrost_cache_binary_path", fake_cache_path)

    cached = fake_cache_path(rust_acp_install.BUNDLED_BIFROST_VERSION)
    cached.write_text("stub")
    cached.chmod(0o755)

    def fail_download(_version: str) -> Path:
        raise AssertionError("must not download when cache hit is available")

    monkeypatch.setattr(rust_acp_install, "_download_bifrost", fail_download)

    resolved = rust_acp_install.resolve_bifrost_binary(override=None)
    assert resolved == cached


def test_bifrost_triple_rejects_intel_mac(monkeypatch) -> None:
    monkeypatch.setattr(rust_acp_install.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(rust_acp_install.platform, "machine", lambda: "x86_64")

    with pytest.raises(rust_acp_install.BifrostInstallError, match="Intel macOS"):
        rust_acp_install._bifrost_triple()


def test_bifrost_triple_maps_known_platforms(monkeypatch) -> None:
    cases = [
        ("Darwin", "arm64", "aarch64-apple-darwin"),
        ("Linux", "x86_64", "x86_64-unknown-linux-gnu"),
        ("Linux", "aarch64", "aarch64-unknown-linux-gnu"),
        ("Windows", "AMD64", "x86_64-pc-windows-msvc"),
        ("Windows", "ARM64", "aarch64-pc-windows-msvc"),
    ]
    for system, machine, expected in cases:
        monkeypatch.setattr(rust_acp_install.platform, "system", lambda s=system: s)
        monkeypatch.setattr(rust_acp_install.platform, "machine", lambda m=machine: m)
        assert rust_acp_install._bifrost_triple() == expected


def test_parse_sha256_handles_both_formats(tmp_path) -> None:
    digest = "a" * 64

    just_hash = tmp_path / "a.sha256"
    just_hash.write_text(digest + "\n")
    assert rust_acp_install._parse_sha256_file(just_hash, "a") == digest

    hash_with_name = tmp_path / "b.sha256"
    hash_with_name.write_text(f"{digest}  payload.tar.gz\n")
    assert rust_acp_install._parse_sha256_file(hash_with_name, "payload.tar.gz") == digest


def test_parse_sha256_rejects_malformed(tmp_path) -> None:
    bad = tmp_path / "bad.sha256"
    bad.write_text("not-a-hash\n")
    with pytest.raises(rust_acp_install.BifrostInstallError, match="Malformed sha256"):
        rust_acp_install._parse_sha256_file(bad, "asset")


def test_download_bifrost_extracts_and_caches(monkeypatch, tmp_path) -> None:
    """End-to-end exercise of _download_bifrost with a stubbed httpx layer."""
    cache_root = tmp_path / "cache"
    monkeypatch.setattr("brokk_code.rust_acp_install.get_global_cache_dir", lambda: cache_root)
    monkeypatch.setattr(rust_acp_install.platform, "system", lambda: "Linux")
    monkeypatch.setattr(rust_acp_install.platform, "machine", lambda: "x86_64")
    monkeypatch.setattr(sys, "platform", "linux")

    # Build a tar.gz containing a single 'bifrost' file at the root.
    archive_buffer = io.BytesIO()
    with tarfile.open(fileobj=archive_buffer, mode="w:gz") as tf:
        payload = b"#!/bin/sh\necho bifrost-stub\n"
        info = tarfile.TarInfo(name="bifrost")
        info.size = len(payload)
        info.mode = 0o755
        tf.addfile(info, io.BytesIO(payload))
    archive_bytes = archive_buffer.getvalue()
    archive_sha = hashlib.sha256(archive_bytes).hexdigest()

    def fake_http_download(url: str, dest: Path) -> None:
        if url.endswith(".sha256"):
            dest.write_text(f"{archive_sha}  bifrost.tar.gz\n")
        else:
            dest.write_bytes(archive_bytes)

    monkeypatch.setattr(rust_acp_install, "_http_download", fake_http_download)

    resolved = rust_acp_install._download_bifrost(rust_acp_install.BUNDLED_BIFROST_VERSION)

    assert resolved.exists()
    assert resolved.name == "bifrost"
    assert os.access(resolved, os.X_OK)
    assert (
        rust_acp_install.BUNDLED_BIFROST_VERSION in resolved.parts
        and "x86_64-unknown-linux-gnu" in resolved.parts
    )
