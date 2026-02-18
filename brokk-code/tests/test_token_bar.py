from brokk_code.widgets.token_bar import TokenBar


def test_compute_segments_empty():
    assert TokenBar.compute_segments(100, 0, 1000, []) == []


def test_format_tokens():
    assert TokenBar.format_tokens(500) == "500"
    assert TokenBar.format_tokens(1500) == "1.5K"
    assert TokenBar.format_tokens(45300) == "45.3K"
    assert TokenBar.format_tokens(1_500_000) == "1.5M"


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
