"""Resolve paths to the Rust ACP server (`brokk-acp`) and `bifrost`.

brokk-code does not install or locate these binaries. By default we write the
literal binary names into the editor config and rely on the editor inheriting
a PATH that finds them at agent-launch time. Pass `--brokk-acp-binary PATH` to
override with an explicit path (e.g. for dev iteration against a locally-built
binary).
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


class RustAcpInstallError(Exception):
    """Raised when an explicitly-provided override path is invalid."""


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
