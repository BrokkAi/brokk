from typing import Any, Dict, List, Optional

from textual.widgets import Static


class TokenBar(Static):
    """
    A widget to display textual token usage information.
    """

    DEFAULT_CSS = """
    TokenBar {
        width: auto;
        height: 1;
        content-align: right middle;
    }
    """

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._fragments: List[Dict[str, Any]] = []

    def update_tokens(
        self,
        used_tokens: int,
        max_tokens: Optional[int] = None,
        fragments: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        """
        Update the displayed token counts and store fragment metadata.
        """
        if used_tokens <= 0:
            self.update("")
            self._fragments = []
            return

        if max_tokens is None or max_tokens <= 0:
            max_tokens = 200_000

        self._fragments = fragments if fragments is not None else []

        # Render: "used / max" with thousands separators
        self.update(f"{used_tokens:,} / {max_tokens:,}")
