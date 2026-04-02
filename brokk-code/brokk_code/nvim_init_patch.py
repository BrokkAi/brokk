import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

PatchStatus = Literal["patched", "already_configured", "missing", "unsupported"]


@dataclass(frozen=True)
class NvimInitPatchResult:
    status: PatchStatus
    path: Path
    detail: str | None = None


def _default_nvim_init_path() -> Path:
    return Path.home() / ".config" / "nvim" / "init.lua"


def _atomic_write_text(path: Path, text: str) -> None:
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temp_file:
        temp_file.write(text)
        temp_path = Path(temp_file.name)

    if path.exists():
        temp_path.chmod(path.stat().st_mode)
    temp_path.replace(path)


def _find_plugin_block(lines: list[str], plugin_repo: str) -> tuple[int, int] | None:
    plugin_line = None
    for i, line in enumerate(lines):
        if plugin_repo in line:
            plugin_line = i
            break
    if plugin_line is None:
        return None

    start = None
    for i in range(plugin_line, -1, -1):
        if lines[i].strip() == "{":
            start = i
            break
    if start is None:
        return None

    end = None
    for i in range(plugin_line + 1, len(lines)):
        if lines[i].strip() == "},":
            end = i
            break
    if end is None:
        return None

    return start, end


def wire_nvim_plugin_setup(
    *, plugin_repo: str, module_name: str, init_path: Path | None = None
) -> NvimInitPatchResult:
    path = init_path or _default_nvim_init_path()
    if not path.exists():
        return NvimInitPatchResult(status="missing", path=path)

    text = path.read_text(encoding="utf-8")
    if module_name in text:
        return NvimInitPatchResult(status="already_configured", path=path)

    lines = text.splitlines()
    block = _find_plugin_block(lines, plugin_repo)
    if block is None:
        return NvimInitPatchResult(
            status="unsupported",
            path=path,
            detail=f"Could not find plugin block for {plugin_repo}",
        )

    start, end = block
    opts_index = None
    for i in range(start, end + 1):
        if lines[i].strip() == "opts = {},":
            opts_index = i
            break

    if opts_index is None:
        return NvimInitPatchResult(
            status="unsupported",
            path=path,
            detail=(
                f"Found {plugin_repo} but did not find a simple `opts = {{}}` block to patch safely"
            ),
        )

    indent = lines[opts_index][: len(lines[opts_index]) - len(lines[opts_index].lstrip())]
    replacement = [
        f"{indent}opts = function()",
        f'{indent}  return require("{module_name}")',
        f"{indent}end,",
    ]
    lines[opts_index : opts_index + 1] = replacement

    _atomic_write_text(path, "\n".join(lines) + "\n")
    return NvimInitPatchResult(status="patched", path=path)
