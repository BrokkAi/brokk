import base64

from textual.geometry import Size

from brokk_code.widgets.token_bar import TokenBar, get_token_bar_markdown, get_token_bar_svg


def test_compute_segments_empty():
    assert TokenBar.compute_segments(100, 0, 1000, []) == []


def test_format_tokens():
    assert TokenBar.format_tokens(500) == "500"
    assert TokenBar.format_tokens(1500) == "1.5k"
    assert TokenBar.format_tokens(45300) == "45k"
    assert TokenBar.format_tokens(1_500_000) == "1.5m"


def test_compute_segments_basic_proportions():
    fragments = [
        {"chipKind": "EDIT", "tokens": 500},
        {"chipKind": "HISTORY", "tokens": 500},
    ]
    # used_tokens 500 of max_tokens 1000 -> 50% usage.
    # In a 100-wide bar, total fill is 50.
    # Each gets 25.
    segments = TokenBar.compute_segments(100, 500, 1000, fragments)
    assert sum(w for w, k in segments) == 50
    assert (25, "EDIT") in segments
    assert (25, "HISTORY") in segments


def test_compute_segments_summary_grouping():
    fragments = [
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "EDIT", "tokens": 800},
    ]
    # Total 1000. 100% fill on width 100.
    # 800/1000 * 100 = 80
    # 200/1000 * 100 = 20
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 100
    assert (80, "EDIT") in segments
    assert (20, "SUMMARIES") in segments
    assert len(segments) == 2


def test_compute_segments_small_fragment_grouping():
    fragments = [
        {"chipKind": "EDIT", "tokens": 980},
        {"chipKind": "EDIT", "tokens": 10},
        {"chipKind": "EDIT", "tokens": 10},
    ]
    # Total 1000. On width 100, the 10-token ones would be 1.0 wide.
    # Min width is 2, so they should be grouped into OTHER.
    # 980/1000 * 100 = 98
    # 20/1000 * 100 = 2 (min_w)
    # Sum: 100.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 100
    assert (98, "EDIT") in segments
    assert (2, "OTHER") in segments
    assert len(segments) == 2


def test_compute_segments_history_not_grouped_even_if_small():
    fragments = [
        {"chipKind": "EDIT", "tokens": 990},
        {"chipKind": "HISTORY", "tokens": 10},
    ]
    # 990/1000 * 100 = 99
    # 10/1000 * 100 = 1
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 100
    assert (99, "EDIT") in segments
    assert (1, "HISTORY") in segments


def test_compute_segments_accounts_for_gaps():
    # Width 100, 100% fill. 3 segments.
    # No gaps are reserved in the filled area.
    fragments = [
        {"chipKind": "EDIT", "tokens": 333},
        {"chipKind": "HISTORY", "tokens": 333},
        {"chipKind": "OTHER", "tokens": 334},
    ]
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    total_w = sum(w for w, k in segments)
    assert total_w == 100
    assert len(segments) == 3


def test_compute_segments_merge_other_stays_below_min_floor():
    # Without strict minima enforcement, summary + other can cause a width of 1.
    # With strict enforcement and merge-to-OTHER behavior, this should stay in the 2+ range
    # and still land on or near the filled width.
    segments = TokenBar.compute_segments(
        3,
        1_000,
        1_000,
        [{"chipKind": "SUMMARY", "tokens": 500}, {"chipKind": "EDIT", "tokens": 500}],
    )
    assert segments == [(3, "OTHER")]
    assert all(width >= 2 for width, _ in segments)


def test_compute_segments_merge_more_fragments_into_other():
    # OVERFLOW with MIN_SEGMENT_WIDTH groups should fold extra non-HISTORY groups into OTHER.
    segments = TokenBar.compute_segments(
        5,
        1_000,
        1_000,
        [
            {"chipKind": "EDIT", "tokens": 600},
            {"chipKind": "EDIT", "tokens": 200},
            {"chipKind": "SUMMARY", "tokens": 200},
        ],
    )
    assert sum(w for w, k in segments) == 5
    assert segments == [(3, "EDIT"), (2, "OTHER")]


def test_compute_segments_cannot_fit_when_min_width_unavoidable():
    # Very narrow bar: even strict minima can force width overflow.
    segments = TokenBar.compute_segments(
        1, 2, 2, [{"chipKind": "SUMMARY", "tokens": 1}, {"chipKind": "EDIT", "tokens": 1}]
    )
    assert sum(w for w, k in segments) == 2
    assert all(w >= 2 for w, _ in segments)


def test_render_percentage_remaining():
    bar = TokenBar()
    # Force a width that can accommodate the label
    bar._test_size = Size(80, 1)

    # 6400 / 200,000 used = 3.2% used = 96.8% remaining
    bar.update_tokens(used_tokens=6400, max_tokens=200_000)
    assert "96.8% context remaining" in bar._rendered_text.plain

    # Test clamping: used > max
    bar.update_tokens(used_tokens=250_000, max_tokens=200_000)
    assert "0.0% context remaining" in bar._rendered_text.plain

    # Test clamping: used < 0 (unlikely but handled)
    bar.update_tokens(used_tokens=-1000, max_tokens=200_000)
    assert "100.0% context remaining" in bar._rendered_text.plain


def test_render_absolute_fallback():
    bar = TokenBar()
    bar._test_size = Size(80, 1)

    # max_tokens <= 0 should show absolute count
    bar.update_tokens(used_tokens=5000, max_tokens=0)
    assert "5k tokens" in bar._rendered_text.plain


def test_get_token_bar_svg_contains_svg_tag():
    svg = get_token_bar_svg(500, 1000, [{"chipKind": "EDIT", "tokens": 500}])
    assert svg.startswith("<svg")
    assert svg.endswith("</svg>")
    assert 'fill="#4CAF50"' in svg  # EDIT color
    assert 'fill="#333333"' in svg  # Background track


def test_get_token_bar_markdown_format():
    md = get_token_bar_markdown(500, 1000, [{"chipKind": "EDIT", "tokens": 500}])
    assert md.startswith("![Token usage](data:image/svg+xml;base64,")
    assert md.endswith(")")

    # Validate base64 content
    b64_part = md.split("base64,")[1].rstrip(")")
    decoded = base64.b64decode(b64_part).decode("ascii")
    assert decoded.startswith("<svg")


def test_get_token_bar_svg_colors_for_multiple_kinds():
    fragments = [
        {"chipKind": "EDIT", "tokens": 100},
        {"chipKind": "HISTORY", "tokens": 100},
    ]
    svg = get_token_bar_svg(200, 200, fragments, width_px=100)
    assert 'fill="#4CAF50"' in svg  # EDIT
    assert 'fill="#E91E63"' in svg  # HISTORY
    # Both should have width 50
    assert 'width="50"' in svg


def test_get_token_bar_svg_absolute_mode_when_max_tokens_zero():
    # When max_tokens <= 0, it should use used_tokens as effective max (full bar)
    fragments = [{"chipKind": "EDIT", "tokens": 1000}]
    svg = get_token_bar_svg(1000, 0, fragments, width_px=100)
    # The bar should be full width (100) for the EDIT segment
    assert 'width="100"' in svg
    assert 'fill="#4CAF50"' in svg


def test_get_token_bar_svg_empty_or_zero_tokens():
    # zero used tokens should result in just the background track (no segments)
    svg = get_token_bar_svg(0, 1000, [], width_px=100)
    assert "<svg" in svg
    assert 'fill="#333333"' in svg
    assert '<rect x="' not in svg  # No segments
