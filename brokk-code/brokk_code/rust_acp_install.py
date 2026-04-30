"""Resolve paths to the Rust ACP server (`brokk-acp`) and `bifrost`.

For the editor-config path (`resolve_rust_paths`), brokk-code does not install
or locate either binary; it writes the literal names into the config and relies
on the editor inheriting a PATH that finds them at agent-launch time. Pass
`--brokk-acp-binary PATH` to override `brokk-acp`.

For the `brokk bifrost` MCP subcommand, `resolve_bifrost_binary` prefers (in
order): explicit override, an entry on `$PATH` whose `--version` output matches
`BUNDLED_BIFROST_VERSION`, then a downloaded-and-cached release binary pinned
to that same version. Cached binaries live under
`get_global_cache_dir() / "bifrost" / <version>`. A `$PATH` binary whose version
does not match (or does not respond to `--version`) is skipped and the bundled
release is used instead.
"""

from __future__ import annotations

import contextlib
import hashlib
import logging
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import httpx

from brokk_code.settings import get_global_cache_dir

logger = logging.getLogger(__name__)

BUNDLED_BIFROST_VERSION = "0.1.2"

_BIFROST_RELEASE_URL = "https://github.com/BrokkAi/bifrost/releases/download"
_BIFROST_DOWNLOAD_TIMEOUT_SECONDS = 300.0
_BIFROST_LOCK_TIMEOUT_SECONDS = 600.0
_BIFROST_VERSION_PROBE_TIMEOUT_SECONDS = 5.0


class RustAcpInstallError(Exception):
    """Raised when an explicitly-provided override path is invalid."""


class BifrostInstallError(Exception):
    """Raised when bifrost cannot be resolved or downloaded."""


@dataclass
class RustAcpPaths:
    brokk_acp: Path
    bifrost: Path
    model: str
    endpoint_url: str | None = None
    api_key: str | None = None


def resolve_rust_paths(*, brokk_acp_override: Path | None) -> tuple[Path, Path]:
    """Return the paths (or literal binary names) to write into the editor config.

    bifrost is always the literal `bifrost`; brokk-acp is the override if given,
    otherwise the literal `brokk-acp`. Override path is validated to exist.
    """
    brokk_acp = (
        _validate_existing_file(brokk_acp_override, "brokk-acp")
        if brokk_acp_override is not None
        else Path("brokk-acp")
    )
    bifrost = Path("bifrost")
    return brokk_acp, bifrost


def _validate_existing_file(path: Path, name: str) -> Path:
    if not path.exists():
        raise RustAcpInstallError(f"{name} binary not found at {path}.")
    if not path.is_file():
        raise RustAcpInstallError(f"{name} path {path} is not a regular file.")
    return path


# ---------------------------------------------------------------------------
# Bifrost binary acquisition
# ---------------------------------------------------------------------------


def resolve_bifrost_binary(
    *,
    version: str = BUNDLED_BIFROST_VERSION,
    override: Path | None = None,
) -> Path:
    """Resolve the bifrost binary path.

    Order: override > $PATH > cached release > download release.
    """
    if override is not None:
        return _validate_existing_file(override, "bifrost")

    on_path = shutil.which("bifrost")
    if on_path:
        on_path_p = Path(on_path)
        if _bifrost_version_matches(on_path_p, version):
            return on_path_p
        logger.info(
            "Ignoring bifrost on $PATH at %s: version does not match bundled %s",
            on_path_p,
            version,
        )

    binary_path = _bifrost_cache_binary_path(version)
    if binary_path.exists() and os.access(binary_path, os.X_OK):
        return binary_path

    return _download_bifrost(version)


def _bifrost_version_matches(binary_path: Path, expected_version: str) -> bool:
    """Return True iff `<binary_path> --version` prints `bifrost <expected_version>`.

    Falls back to False on any error (timeout, non-zero exit, unparseable output).
    """
    try:
        proc = subprocess.run(
            [str(binary_path), "--version"],
            capture_output=True,
            text=True,
            timeout=_BIFROST_VERSION_PROBE_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.debug("Failed to probe %s --version: %s", binary_path, exc)
        return False

    if proc.returncode != 0:
        logger.debug(
            "%s --version exited %d; stderr=%r",
            binary_path,
            proc.returncode,
            proc.stderr,
        )
        return False

    lines = (proc.stdout or "").strip().splitlines()
    if not lines:
        return False
    tokens = lines[0].split()
    return len(tokens) >= 2 and tokens[0] == "bifrost" and tokens[1] == expected_version


def _bifrost_triple() -> str:
    system = platform.system()
    machine = platform.machine().lower()
    if system == "Darwin":
        if machine in ("arm64", "aarch64"):
            return "aarch64-apple-darwin"
        raise BifrostInstallError(
            f"bifrost v{BUNDLED_BIFROST_VERSION} does not ship an Intel macOS "
            f"(x86_64-apple-darwin) binary. Detected machine: {machine}. "
            "Install bifrost manually or run on an arm64 mac."
        )
    if system == "Linux":
        if machine in ("x86_64", "amd64"):
            return "x86_64-unknown-linux-gnu"
        if machine in ("aarch64", "arm64"):
            return "aarch64-unknown-linux-gnu"
        raise BifrostInstallError(f"Unsupported Linux machine: {machine}")
    if system == "Windows":
        if machine in ("amd64", "x86_64"):
            return "x86_64-pc-windows-msvc"
        if machine in ("arm64", "aarch64"):
            return "aarch64-pc-windows-msvc"
        raise BifrostInstallError(f"Unsupported Windows machine: {machine}")
    raise BifrostInstallError(f"Unsupported system: {system} {machine}")


def _bifrost_archive_extension(triple: str) -> str:
    return ".zip" if "windows" in triple else ".tar.gz"


def _bifrost_binary_filename() -> str:
    return "bifrost.exe" if sys.platform == "win32" else "bifrost"


def _bifrost_cache_binary_path(version: str) -> Path:
    return (
        get_global_cache_dir()
        / "bifrost"
        / version
        / _bifrost_triple()
        / _bifrost_binary_filename()
    )


def _bifrost_archive_url(version: str, asset_name: str) -> str:
    return f"{_BIFROST_RELEASE_URL}/v{version}/{asset_name}"


@contextlib.contextmanager
def _bifrost_download_lock(version: str) -> Iterator[None]:
    """File-based lock to serialize concurrent downloads of the same version."""
    lock_path = get_global_cache_dir() / "bifrost" / f"{version}.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)

    deadline = time.monotonic() + _BIFROST_LOCK_TIMEOUT_SECONDS
    acquired = False
    while not acquired:
        try:
            fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            try:
                os.write(fd, str(os.getpid()).encode())
            finally:
                os.close(fd)
            acquired = True
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise BifrostInstallError(
                    f"Could not acquire bifrost {version} download lock at {lock_path}"
                )
            time.sleep(0.5)

    try:
        yield
    finally:
        lock_path.unlink(missing_ok=True)


def _download_bifrost(version: str) -> Path:
    triple = _bifrost_triple()
    extension = _bifrost_archive_extension(triple)
    asset_name = f"bifrost-v{version}-{triple}{extension}"
    archive_url = _bifrost_archive_url(version, asset_name)
    sha256_url = f"{archive_url}.sha256"

    target = _bifrost_cache_binary_path(version)
    target.parent.mkdir(parents=True, exist_ok=True)

    with _bifrost_download_lock(version):
        # Double-check after lock acquisition — another process may have populated cache.
        if target.exists() and os.access(target, os.X_OK):
            return target

        logger.info("Downloading bifrost %s for %s from %s", version, triple, archive_url)
        with tempfile.TemporaryDirectory(prefix="brokk-bifrost-") as tmpdir:
            tmp_path = Path(tmpdir)
            archive_path = tmp_path / asset_name
            sha_path = tmp_path / f"{asset_name}.sha256"

            try:
                _http_download(archive_url, archive_path)
                _http_download(sha256_url, sha_path)
            except httpx.HTTPError as exc:
                raise BifrostInstallError(
                    f"Failed to download bifrost v{version} ({triple}): {exc}"
                ) from exc

            expected_sha = _parse_sha256_file(sha_path, asset_name)
            actual_sha = _file_sha256(archive_path)
            if expected_sha != actual_sha:
                raise BifrostInstallError(
                    f"Checksum mismatch for {asset_name}: expected {expected_sha}, got {actual_sha}"
                )

            extracted_dir = tmp_path / "extracted"
            extracted_dir.mkdir()
            _extract_archive(archive_path, extracted_dir)

            extracted_binary = _find_extracted_binary(extracted_dir)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(extracted_binary), str(target))
            if sys.platform != "win32":
                target.chmod(0o755)

        return target


def _http_download(url: str, dest: Path) -> None:
    with httpx.stream(
        "GET",
        url,
        timeout=_BIFROST_DOWNLOAD_TIMEOUT_SECONDS,
        follow_redirects=True,
    ) as response:
        response.raise_for_status()
        with dest.open("wb") as fh:
            for chunk in response.iter_bytes():
                fh.write(chunk)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _parse_sha256_file(path: Path, asset_name: str) -> str:
    """Parse `<sha>` or `<sha>  <filename>` style sha256 sidecars."""
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise BifrostInstallError(f"Empty sha256 file for {asset_name}")
    token = text.split()[0].strip().lower()
    if len(token) != 64 or not all(c in "0123456789abcdef" for c in token):
        raise BifrostInstallError(f"Malformed sha256 for {asset_name}: {text!r}")
    return token


def _extract_archive(archive: Path, into: Path) -> None:
    name = archive.name.lower()
    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        with tarfile.open(archive, "r:gz") as tf:
            tf.extractall(into, filter="data")
    elif name.endswith(".zip"):
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(into)
    else:
        raise BifrostInstallError(f"Unrecognized archive format: {archive.name}")


def _find_extracted_binary(root: Path) -> Path:
    expected = _bifrost_binary_filename()
    matches = [p for p in root.rglob(expected) if p.is_file()]
    if not matches:
        raise BifrostInstallError(f"No '{expected}' found inside extracted archive at {root}")
    # Prefer the shallowest match if multiple (release archives ship one binary at root).
    matches.sort(key=lambda p: len(p.parts))
    return matches[0]
