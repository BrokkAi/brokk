from pathlib import Path
from typing import Optional


def find_dev_jar(workspace_dir: Path, subproject: str = "app") -> Optional[Path]:
    """
    Searches for a local development JAR in the project structure.

    Walks upward from workspace_dir until gradlew is found, then looks in
    <repo>/<subproject>/build/libs/<subproject_prefix>-*.jar.

    *subproject* defaults to ``"app"`` (jar prefix ``"brokk"``).  Pass
    ``"brokk-core"`` to locate the core MCP jar instead.

    Excludes clearly non-runnable classifier jars like -sources, -javadoc,
    and -plain. Preserves runnable artifacts like -all or -shadow.
    Returns the newest acceptable jar by mtime.
    """
    excluded_suffixes = ("-sources.jar", "-javadoc.jar", "-plain.jar")
    jar_prefix = "brokk" if subproject == "app" else subproject

    def _find_in_repo(base: Path) -> Optional[Path]:
        libs_dir = base / subproject / "build" / "libs"
        if not libs_dir.exists():
            return None

        candidates = [
            jar
            for jar in libs_dir.glob(f"{jar_prefix}-*.jar")
            if not jar.name.endswith(excluded_suffixes)
        ]
        if not candidates:
            return None

        return max(candidates, key=lambda jar: jar.stat().st_mtime)

    curr = workspace_dir.resolve()
    while True:
        if (curr / "gradlew").exists() or (curr / "gradlew.bat").exists():
            potential_jar = _find_in_repo(curr)
            if potential_jar:
                return potential_jar

        if curr == curr.parent:
            break
        curr = curr.parent

    return None
