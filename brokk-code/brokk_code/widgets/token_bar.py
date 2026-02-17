import math
from typing import Any, Dict, List, Optional

from rich.text import Text
from textual.widgets import Static


class TokenBar(Static):
    """
    A widget to display segmented token usage information.
    """

    DEFAULT_CSS = """
    TokenBar {
        width: 1fr;
        height: 1;
        content-align: right middle;
    }
    """

    MIN_SEGMENT_WIDTH = 2

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._used_tokens = 0
        self._max_tokens = 200_000
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
        self._used_tokens = used_tokens
        # Reset to default if not provided, matching expected test behavior
        self._max_tokens = max_tokens if max_tokens is not None and max_tokens > 0 else 200_000
        self._fragments = fragments if fragments is not None else []
        self._render_bar()

    def on_resize(self) -> None:
        self._render_bar()

    @staticmethod
    def format_tokens(tokens: int) -> str:
        """
        Ported from Swing TokenUsageBar.formatTokens().
        """
        if tokens < 1000:
            return str(tokens)
        if tokens < 1_000_000:
            return f"{tokens / 1000.0:.1f}K"
        return f"{tokens / 1_000_000.0:.1f}M"

    def _render_bar(self) -> None:
        if self._used_tokens <= 0:
            self.update(Text("No context yet", style="dim italic"))
            return

        width = self.size.width
        if width <= 0:
            return

        if self._max_tokens > 0:
            usage_str = f" {self._used_tokens:,} / {self._max_tokens:,} tokens"
        else:
            usage_str = f" {self._used_tokens:,} tokens"

        # Reserve space for the text at the end
        bar_width = width - len(usage_str)
        if bar_width <= 0:
            self.update(Text(usage_str.strip(), style="dim"))
            return

        segments = self.compute_segments(
            bar_width, self._used_tokens, self._max_tokens, self._fragments
        )

        text = Text()
        filled_width = 0
        for i, (seg_width, kind) in enumerate(segments):
            if i > 0:
                text.append(" ")  # SEGMENT_GAP
                filled_width += 1

            color = self._get_kind_color(kind)
            text.append("█" * seg_width, style=color)
            filled_width += seg_width

        # Fill remaining track
        remaining = bar_width - filled_width
        if remaining > 0:
            text.append("█" * remaining, style="dim grey15")

        # Append numerical usage text
        text.append(usage_str, style="dim")

        self.update(text)

    @staticmethod
    def _get_kind_color(kind: str) -> str:
        """Maps chip kind to a Rich-compatible color string."""
        k = kind.upper()
        if k == "EDIT":
            return "green"
        if k == "SUMMARY" or k == "SUMMARIES":
            return "yellow"
        if k == "HISTORY":
            return "magenta"
        if k == "TASK_LIST":
            return "blue"
        if k == "INVALID":
            return "red"
        return "grey37"  # OTHER

    @classmethod
    def compute_segments(
        cls,
        width: int,
        used_tokens: int,
        max_tokens: int,
        fragments: List[Dict[str, Any]],
    ) -> List[tuple[int, str]]:
        """
        Pure helper to compute segment widths and labels.
        Analogous to Swing TokenUsageBar.computeSegments.
        """
        if not fragments or used_tokens <= 0:
            # Fallback to single "OTHER" block if no breakdown
            effective_max = max(max_tokens, used_tokens)
            fill_width = int(math.floor(width * (used_tokens / effective_max)))
            if fill_width > 0:
                return [(fill_width, "OTHER")]
            return []

        # 1. Group Summaries
        summaries = [f for f in fragments if f.get("chipKind", f.get("chip_kind")) == "SUMMARY"]
        others = [f for f in fragments if f.get("chipKind", f.get("chip_kind")) != "SUMMARY"]

        tokens_summaries = sum(int(f.get("tokens", 0)) for f in summaries)
        fragment_total_tokens = sum(int(f.get("tokens", 0)) for f in fragments)

        if fragment_total_tokens <= 0:
            return []

        # Total filled width is determined by used_tokens vs max_tokens.
        effective_max = max(max_tokens, used_tokens)
        total_fill_width = int(math.floor(width * (used_tokens / effective_max)))
        if total_fill_width <= 0:
            return []

        # 2. Identify small fragments to group into "OTHER" (except HISTORY)
        alloc_items: List[Dict[str, Any]] = []
        small_fragments: List[Dict[str, Any]] = []

        # Use the sum of fragment tokens to determine proportions within the filled area.
        proportion_base = fragment_total_tokens

        for f in others:
            t = int(f.get("tokens", 0))
            kind = f.get("chipKind", f.get("chip_kind", "OTHER"))
            raw_w = (t / proportion_base) * total_fill_width

            if raw_w < cls.MIN_SEGMENT_WIDTH and kind != "HISTORY":
                small_fragments.append(f)
            else:
                alloc_items.append({"tokens": t, "kind": kind, "min_w": 0})

        # Add "OTHER" group if needed
        if small_fragments:
            tokens_other = sum(int(f.get("tokens", 0)) for f in small_fragments)
            alloc_items.append(
                {"tokens": tokens_other, "kind": "OTHER", "min_w": cls.MIN_SEGMENT_WIDTH}
            )

        # Add "SUMMARIES" group if needed
        if summaries:
            alloc_items.append(
                {"tokens": tokens_summaries, "kind": "SUMMARIES", "min_w": cls.MIN_SEGMENT_WIDTH}
            )

        # 3. Allocation (Simplified largest-remainder)
        total_gaps = len(alloc_items) - 1
        effective_fill = max(0, total_fill_width - total_gaps)

        # First pass: Floor and min-width clamping
        sum_w = 0
        working_items: List[Dict[str, Any]] = []
        for item in alloc_items:
            raw_w = (item["tokens"] / proportion_base) * effective_fill
            w = max(int(math.floor(raw_w)), item["min_w"])
            working_items.append(
                {"kind": item["kind"], "width": w, "rem": raw_w - math.floor(raw_w)}
            )
            sum_w += w

        # Distribute deficit/excess
        deficit = effective_fill - sum_w
        if deficit > 0:
            # Sort by remainder descending
            for item in sorted(working_items, key=lambda x: x["rem"], reverse=True)[:deficit]:
                item["width"] += 1
        elif deficit < 0:
            # Shrink items that are above their minimums
            need = -deficit
            for item in sorted(working_items, key=lambda x: x["rem"]):
                if need <= 0:
                    break
                # We don't have explicit min_w in working_items here, but we can check if > 1
                if item["width"] > 1:
                    item["width"] -= 1
                    need -= 1

        return [(item["width"], item["kind"]) for item in working_items if item["width"] > 0]
