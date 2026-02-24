import base64
import math
from typing import Any, Dict, List, Optional

from rich.console import RenderableType
from rich.text import Text
from textual import events
from textual.geometry import Size
from textual.message import Message
from textual.widgets import Static

from brokk_code.token_format import format_token_count


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

    class FragmentHovered(Message):
        def __init__(self, description: Optional[str], size: Optional[int]) -> None:
            self.description = description
            self.size = size
            super().__init__()

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._used_tokens = 0
        self._max_tokens = 200_000
        self._fragments: List[Dict[str, Any]] = []
        self._session_cost: Optional[float] = None
        self._test_size: Optional[Size] = None
        self._rendered_text: Text = Text()
        self._segment_layout: list[tuple[int, int, str, List[Dict[str, Any]]]] = []
        self._bar_width = 0
        self._hover_signature: tuple[str, int] | None = None

    def update_tokens(
        self,
        used_tokens: int,
        max_tokens: Optional[int] = None,
        fragments: Optional[List[Dict[str, Any]]] = None,
        session_cost: Optional[float] = None,
    ) -> None:
        """
        Update the displayed token counts and store fragment metadata.
        """
        self._used_tokens = used_tokens
        # Default to 200k ONLY if None. If 0 or negative is passed explicitly, preserve it
        # to signal absolute token count rendering.
        self._max_tokens = max_tokens if max_tokens is not None else 200_000
        self._fragments = fragments if fragments is not None else []
        self._session_cost = session_cost
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
        return format_token_count(tokens)

    def _render_bar(self) -> None:
        size = self._test_size or self.size
        width = size.width
        self._segment_layout = []
        self._bar_width = 0

        if self._used_tokens == 0:
            self._rendered_text = Text("No context yet", style="dim italic")
            self._emit_hover(None, None)
        elif width <= 0:
            # No layout yet — show numbers only, no bar
            if self._max_tokens > 0:
                # Clamp used_tokens to 0 for percentage display if negative
                display_used = max(0, self._used_tokens)
                pct = max(0.0, min(100.0, 100 * (1 - display_used / self._max_tokens)))
                usage_str = f"{pct:.1f}% context remaining"
            else:
                usage_str = f"{format_token_count(self._used_tokens)} tokens"
            self._rendered_text = Text(usage_str, style="dim")
            self._emit_hover(None, None)
        else:
            cost_hint = ""
            if self._session_cost is not None and self._session_cost > 0:
                cost_hint = f" (${self._session_cost:.2f})"

            if self._max_tokens > 0:
                # Clamp used_tokens to 0 for percentage display if negative
                display_used = max(0, self._used_tokens)
                pct = max(0.0, min(100.0, 100 * (1 - display_used / self._max_tokens)))
                usage_str = f" {pct:.1f}% context remaining{cost_hint}"
            else:
                usage_str = f" {format_token_count(self._used_tokens)} tokens{cost_hint}"

            # Reserve space for the text at the end
            bar_width = width - len(usage_str)
            if bar_width <= 0:
                self._rendered_text = Text(usage_str.strip(), style="dim")
                self._emit_hover(None, None)
            else:
                self._bar_width = bar_width
                segment_details = self.compute_segment_details(
                    bar_width, self._used_tokens, self._max_tokens, self._fragments
                )

                text = Text()
                filled_width = 0
                for seg_width, kind, segment_fragments in segment_details:
                    color = self._get_kind_color(kind)
                    if seg_width <= 1:
                        text.append(self.BRAILLE_FILL * seg_width, style=color)
                    else:
                        interior = max(0, seg_width - 2)
                        text.append(self.BRAILLE_OPEN, style=color)
                        if interior:
                            text.append(self.BRAILLE_FILL * interior, style=color)
                        text.append(self.BRAILLE_CLOSE, style=color)
                    self._segment_layout.append(
                        (
                            filled_width,
                            filled_width + seg_width,
                            kind,
                            segment_fragments,
                        )
                    )
                    filled_width += seg_width

                # Fill remaining track
                remaining = bar_width - filled_width
                if remaining > 0:
                    text.append(self.BRAILLE_FILL * remaining, style="dim grey15")

                # Append numerical usage text
                text.append(usage_str, style="dim")
                self._rendered_text = text
                if not self._segment_layout:
                    self._emit_hover(None, None)

        try:
            self.refresh()
        except Exception:
            # No active app or display
            pass

    def _emit_hover(self, description: Optional[str], size: Optional[int]) -> None:
        signature = None if description is None else (description, size or 0)
        if signature == self._hover_signature:
            return
        self._hover_signature = signature
        self.post_message(self.FragmentHovered(description=description, size=size))

    def on_mouse_move(self, event: events.MouseMove) -> None:
        if self._bar_width <= 0:
            self._emit_hover(None, None)
            return

        offset = getattr(event, "offset", None)
        column = getattr(offset, "x", None)
        if not isinstance(column, int):
            column = getattr(event, "x", None)
        if not isinstance(column, int):
            return

        payload = self._segment_payload(column)
        if payload is None:
            self._emit_hover(None, None)
            return

        description, size = payload
        if description is None or size is None:
            self._emit_hover(None, None)
            return
        self._emit_hover(description=description, size=size)

    def on_leave(self, event: events.Leave) -> None:
        self._emit_hover(None, None)

    def _segment_payload(self, column: int) -> tuple[Optional[str], Optional[int]] | None:
        if not self._segment_layout or column < 0 or column >= self._bar_width:
            return None
        for segment_start, segment_end, _kind, segment_fragments in self._segment_layout:
            if segment_start <= column < segment_end:
                return self._describe_fragment_segment(segment_fragments)
        return None

    @classmethod
    def _describe_fragment_segment(
        cls, fragments: List[Dict[str, Any]]
    ) -> tuple[Optional[str], Optional[int]]:
        if not fragments:
            return None, None

        if len(fragments) == 1:
            fragment = fragments[0]
            description = str(fragment.get("shortDescription", "Unknown"))
            return description, cls._fragment_size(fragment)

        descriptions = [str(fragment.get("shortDescription", "Unknown")) for fragment in fragments]
        if len(descriptions) > 3:
            descriptions = descriptions[:3] + [f"... +{len(descriptions) - 3} more"]
        return ", ".join(descriptions), sum(cls._fragment_size(fragment) for fragment in fragments)

    @classmethod
    def _fragment_size(cls, fragment: Dict[str, Any]) -> int:
        return cls._safe_int(fragment.get("size", fragment.get("tokens", 0)))

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

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
        return [
            (width, kind)
            for width, kind, _fragments in cls.compute_segment_details(
                width=width,
                used_tokens=used_tokens,
                max_tokens=max_tokens,
                fragments=fragments,
            )
        ]

    @classmethod
    def compute_segment_details(
        cls,
        width: int,
        used_tokens: int,
        max_tokens: int,
        fragments: List[Dict[str, Any]],
    ) -> List[tuple[int, str, List[Dict[str, Any]]]]:
        """
        Pure helper to compute segment widths, labels, and fragment groups.
        """
        if not fragments or used_tokens <= 0:
            # Fallback to single "OTHER" block if no breakdown
            # For bar filling, we clamp negative tokens to 0
            display_used = max(0, used_tokens)
            effective_max = max(max_tokens, display_used)
            if effective_max <= 0:
                return []
            fill_width = int(math.floor(width * (display_used / effective_max)))
            if fill_width > 0:
                return [(fill_width, "OTHER", [])]
            return []

        # 1. Group Summaries
        summaries = [
            f for f in fragments if f.get("chipKind", f.get("chip_kind", "OTHER")) == "SUMMARY"
        ]
        others = [
            f for f in fragments if f.get("chipKind", f.get("chip_kind", "OTHER")) != "SUMMARY"
        ]

        tokens_summaries = sum(cls._safe_int(f.get("tokens", 0)) for f in summaries)
        fragment_total_tokens = sum(cls._safe_int(f.get("tokens", 0)) for f in fragments)

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
            t = cls._safe_int(f.get("tokens", 0))
            kind = f.get("chipKind", f.get("chip_kind", "OTHER"))
            raw_w = (t / proportion_base) * total_fill_width

            if raw_w < cls.MIN_SEGMENT_WIDTH and kind != "HISTORY":
                small_fragments.append(f)
            else:
                alloc_items.append({"tokens": t, "kind": kind, "min_w": 0, "fragments": [f]})

        # Add "OTHER" group if needed
        if small_fragments:
            tokens_other = sum(cls._safe_int(f.get("tokens", 0)) for f in small_fragments)
            alloc_items.append(
                {
                    "tokens": tokens_other,
                    "kind": "OTHER",
                    "min_w": cls.MIN_SEGMENT_WIDTH,
                    "fragments": small_fragments[:],
                }
            )

        # Add "SUMMARIES" group if needed
        if summaries:
            alloc_items.append(
                {
                    "tokens": tokens_summaries,
                    "kind": "SUMMARIES",
                    "min_w": cls.MIN_SEGMENT_WIDTH,
                    "fragments": summaries[:],
                }
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
                        "fragments": item["fragments"],
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
                        for item in sorted(working_items, key=lambda x: x["rem"], reverse=True)[
                            :deficit
                        ]:
                            item["width"] += 1
                    return [
                        (item["width"], item["kind"], list(item["fragments"]))
                        for item in working_items
                        if item["width"] > 0
                    ]

                # If still over, merge the smallest non-HISTORY fragment into OTHER.
                # This intentionally preserves minimum widths rather than violating them.
                candidates = [
                    it
                    for it in working_items
                    if it["kind"] not in {"HISTORY", "OTHER"} and it["width"] > 0
                ]
                if not candidates:
                    return [
                        (it["width"], it["kind"], list(it["fragments"]))
                        for it in working_items
                        if it["width"] > 0
                    ]

                victim = sorted(candidates, key=lambda x: (x["width"], x["tokens"]))[0]
                other_item = next((i for i in alloc_items if i["kind"] == "OTHER"), None)
                if other_item is None:
                    other_item = {
                        "kind": "OTHER",
                        "tokens": 0,
                        "min_w": cls.MIN_SEGMENT_WIDTH,
                        "fragments": [],
                    }
                    alloc_items.append(other_item)
                other_item["tokens"] += victim["item"]["tokens"]
                other_item["fragments"] += victim["item"]["fragments"]
                alloc_items.remove(victim["item"])

                # Re-run with the merged bucket.
                continue

            if sum_w <= total_fill_width:
                # Fill small positive remainder by giving extra cells to
                # highest fractional remainders.
                deficit = total_fill_width - sum_w
                if deficit > 0:
                    for item in sorted(working_items, key=lambda x: x["rem"], reverse=True)[
                        :deficit
                    ]:
                        item["width"] += 1

                return [
                    (item["width"], item["kind"], list(item["fragments"]))
                    for item in working_items
                    if item["width"] > 0
                ]


def get_token_bar_svg(
    used_tokens: int,
    max_tokens: int,
    fragments: List[Dict[str, Any]],
    width_px: int = 400,
    height_px: int = 16,
) -> str:
    """
    Generate a pure SVG string representing the token usage bar.
    """
    if width_px <= 0:
        return ""

    details = TokenBar.compute_segment_details(width_px, used_tokens, max_tokens, fragments)

    # Track background
    svg_parts = [
        f'<svg width="{width_px}" height="{height_px}" xmlns="http://www.w3.org/2000/svg">',
        f'<rect width="{width_px}" height="{height_px}" fill="#333333" rx="2" ry="2" />',
    ]

    x_offset = 0
    for w, kind, _frags in details:
        if w <= 0:
            continue
        color = _KIND_TO_HEX.get(kind.upper(), "#757575")
        svg_parts.append(
            f'<rect x="{x_offset}" y="0" width="{w}" height="{height_px}" fill="{color}" />'
        )
        x_offset += w

    svg_parts.append("</svg>")
    return "".join(svg_parts)


def get_token_bar_markdown(
    used_tokens: int,
    max_tokens: int,
    fragments: List[Dict[str, Any]],
    width_px: int = 400,
    height_px: int = 16,
) -> str:
    """
    Generate a Markdown image string with a base64-encoded SVG token bar.
    """
    svg = get_token_bar_svg(used_tokens, max_tokens, fragments, width_px, height_px)
    if not svg:
        return ""
    b64_svg = base64.b64encode(svg.encode("ascii")).decode("ascii")
    return f"![Token usage](data:image/svg+xml;base64,{b64_svg})"


_KIND_TO_HEX = {
    "EDIT": "#4CAF50",
    "SUMMARY": "#FFC107",
    "SUMMARIES": "#FFC107",
    "HISTORY": "#E91E63",
    "TASK_LIST": "#2196F3",
    "INVALID": "#F44336",
    "OTHER": "#757575",
}
