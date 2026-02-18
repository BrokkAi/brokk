import math
from typing import Any, Dict, List, Optional

from rich.console import RenderableType
from rich.text import Text
from textual.geometry import Size
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
    BRAILLE_OPEN = "\u28be"  # dots-234568
    BRAILLE_CLOSE = "\u2877"  # dots-123567
    BRAILLE_FILL = "\u28ff"  # dots-12345678

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._used_tokens = 0
        self._max_tokens = 200_000
        self._fragments: List[Dict[str, Any]] = []
        self._test_size: Optional[Size] = None
        self._rendered_text: Text = Text()

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

    def render(self) -> RenderableType:
        return self._rendered_text

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
        size = self._test_size or self.size
        width = size.width

        if self._used_tokens <= 0:
            self._rendered_text = Text("No context yet", style="dim italic")
        elif width <= 0:
            # No layout yet — show numbers only, no bar
            if self._max_tokens > 0:
                usage_str = f"{self._used_tokens:,} / {self._max_tokens:,} tokens"
            else:
                usage_str = f"{self._used_tokens:,} tokens"
            self._rendered_text = Text(usage_str, style="dim")
        else:
            if self._max_tokens > 0:
                usage_str = f" {self._used_tokens:,} / {self._max_tokens:,} tokens"
            else:
                usage_str = f" {self._used_tokens:,} tokens"

            # Reserve space for the text at the end
            bar_width = width - len(usage_str)
            if bar_width <= 0:
                self._rendered_text = Text(usage_str.strip(), style="dim")
            else:
                segments = self.compute_segments(
                    bar_width, self._used_tokens, self._max_tokens, self._fragments
                )

                text = Text()
                filled_width = 0
                for i, (seg_width, kind) in enumerate(segments):
                    color = self._get_kind_color(kind)
                    if seg_width <= 1:
                        text.append(self.BRAILLE_FILL * seg_width, style=color)
                    else:
                        interior = max(0, seg_width - 2)
                        text.append(self.BRAILLE_OPEN, style=color)
                        if interior:
                            text.append(self.BRAILLE_FILL * interior, style=color)
                        text.append(self.BRAILLE_CLOSE, style=color)
                    filled_width += seg_width

                # Fill remaining track
                remaining = bar_width - filled_width
                if remaining > 0:
                    text.append(self.BRAILLE_FILL * remaining, style="dim grey15")

                # Append numerical usage text
                text.append(usage_str, style="dim")
                self._rendered_text = text

        try:
            self.refresh()
        except Exception:
            # No active app or display
            pass

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

        # 3. Allocate widths with strict minima and no shrinking below them.
        while True:
            working_items: List[Dict[str, Any]] = []
            sum_w = 0
            for item in alloc_items:
                raw_w = (item["tokens"] / proportion_base) * total_fill_width
                floor_w = int(math.floor(raw_w))
                width = max(floor_w, item["min_w"])
                working_items.append(
                    {
                        "item": item,
                        "kind": item["kind"],
                        "width": width,
                        "rem": raw_w - floor_w,
                        "tokens": item["tokens"],
                    }
                )
                sum_w += width

            if sum_w > total_fill_width:
                # First, shrink items that are above their minima.
                need = sum_w - total_fill_width
                for item in sorted(working_items, key=lambda x: x["rem"]):
                    if need <= 0:
                        break
                    min_w = item["item"]["min_w"]
                    if item["kind"] != "HISTORY":
                        min_w = max(min_w, cls.MIN_SEGMENT_WIDTH)
                    available = item["width"] - min_w
                    if available <= 0:
                        continue
                    delta = min(available, need)
                    item["width"] -= delta
                    need -= delta

                if need <= 0:
                    current_sum = sum(item["width"] for item in working_items)
                    deficit = total_fill_width - current_sum
                    if deficit > 0:
                        for item in sorted(working_items, key=lambda x: x["rem"], reverse=True)[:deficit]:
                            item["width"] += 1
                    return [(item["width"], item["kind"]) for item in working_items if item["width"] > 0]

                # If still over, merge the smallest non-HISTORY fragment into OTHER.
                # This intentionally preserves minimum widths rather than violating them.
                candidates = [
                    it
                    for it in working_items
                    if it["kind"] not in {"HISTORY", "OTHER"} and it["width"] > 0
                ]
                if not candidates:
                    return [(it["width"], it["kind"]) for it in working_items if it["width"] > 0]

                victim = sorted(candidates, key=lambda x: (x["width"], x["tokens"]))[0]
                other_item = next((i for i in alloc_items if i["kind"] == "OTHER"), None)
                if other_item is None:
                    other_item = {"kind": "OTHER", "tokens": 0, "min_w": cls.MIN_SEGMENT_WIDTH}
                    alloc_items.append(other_item)
                other_item["tokens"] += victim["item"]["tokens"]
                alloc_items.remove(victim["item"])

                # Re-run with the merged bucket.
                continue

            if sum_w <= total_fill_width:
                # Fill small positive remainder by giving extra cells to highest fractional remainders.
                deficit = total_fill_width - sum_w
                if deficit > 0:
                    for item in sorted(working_items, key=lambda x: x["rem"], reverse=True)[:deficit]:
                        item["width"] += 1

                return [(item["width"], item["kind"]) for item in working_items if item["width"] > 0]
