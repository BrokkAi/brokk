"""Resolve absolute paths to the Rust ACP server (`brokk-acp`) and `bifrost`.

brokk-code does not build or fetch these binaries -- the user is responsible
for installing them (`cargo install`, package manager, prebuilt download, etc.).
This module only resolves where they live so the editor's agent_servers config
can be written to point at them.
"""

from __future__ import annotations

import shutil
from dataclasses import dataclass
from pathlib import Path


class RustAcpInstallError(Exception):
    """Raised when a required Rust ACP binary cannot be located."""


@dataclass
class RustAcpPaths:
    brokk_acp: Path
    bifrost: Path
    model: str
    endpoint_url: str | None = None
    api_key: str | None = None


def resolve_rust_paths(*, brokk_acp_override: Path | None) -> tuple[Path, Path]:
    """Resolve absolute paths for the brokk-acp and bifrost binaries.

    `brokk-acp` may be supplied explicitly via override; otherwise it (and
    bifrost, always) must be discoverable on PATH.
    """
    brokk_acp = (
        _validate_existing_file(brokk_acp_override, "brokk-acp")
        if brokk_acp_override is not None
        else _resolve_on_path("brokk-acp")
    )
    bifrost = _resolve_on_path("bifrost")
    return brokk_acp, bifrost


def _resolve_on_path(name: str) -> Path:
    found = shutil.which(name)
    if not found:
        raise RustAcpInstallError(
            f"'{name}' not found on PATH. Install it (e.g. `cargo install ...`) "
            "and ensure it is on your PATH, then re-run."
        )
    return Path(found).resolve()


def _validate_existing_file(path: Path, name: str) -> Path:
    if not path.exists():
        raise RustAcpInstallError(f"{name} binary not found at {path}.")
    if not path.is_file():
        raise RustAcpInstallError(f"{name} path {path} is not a regular file.")
    return path.resolve()
