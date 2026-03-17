import os
import shutil
import sys
from pathlib import Path
from typing import Optional

_GO_EXECUTOR_BASENAME = "brokk-go-executor"
_GO_MCP_BASENAME = "brokk-go-mcp"
_GO_RUNTIME_ENV = "BROKK_USE_GO_RUNTIME"


def _binary_name(base: str) -> str:
    return f"{base}.exe" if sys.platform == "win32" else base


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _candidate_paths(base: str) -> list[Path]:
    filename = _binary_name(base)
    return [
        _repo_root() / "go-runtime" / "bin" / filename,
        Path.home() / ".brokk" / filename,
    ]


def _truthy_env(var_name: str) -> bool:
    value = os.environ.get(var_name, "").strip().lower()
    return value in {"1", "true", "yes", "on"}


def use_go_runtime_enabled() -> bool:
    return _truthy_env(_GO_RUNTIME_ENV)


def _resolve_binary(env_var: str, base: str) -> Optional[str]:
    env_raw = os.environ.get(env_var, "").strip()
    if env_raw:
        env_path = Path(env_raw).expanduser()
        if env_path.exists():
            return str(env_path)

    if not use_go_runtime_enabled():
        return None

    on_path = shutil.which(_binary_name(base)) or shutil.which(base)
    if on_path:
        return on_path

    for candidate in _candidate_paths(base):
        if candidate.exists():
            return str(candidate)
    return None


def resolve_go_executor_binary() -> Optional[str]:
    return _resolve_binary("BROKK_GO_EXECUTOR", _GO_EXECUTOR_BASENAME)


def resolve_go_mcp_binary() -> Optional[str]:
    return _resolve_binary("BROKK_GO_MCP", _GO_MCP_BASENAME)
