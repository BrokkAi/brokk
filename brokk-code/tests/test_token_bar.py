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
    # 50% usage of 1000 max. In a 100-wide bar, total fill is 50.
    # Total gaps: 1 (between EDIT and HISTORY).
    # Effective fill: 50 - 1 = 49.
    # Each gets 24.5 -> floor 24. Deficit 1. 1st gets 25, 2nd 24.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 49
    assert (25, "EDIT") in segments
    assert (24, "HISTORY") in segments


def test_compute_segments_summary_grouping():
    fragments = [
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "EDIT", "tokens": 800},
    ]
    # Total 1000. 100% fill on width 100.
    # Total gaps: 1. Effective fill: 99.
    # 800/1000 * 99 = 79.2 -> 79
    # 200/1000 * 99 = 19.8 -> 19
    # Deficit 1. 19.8 has higher rem. 19 becomes 20.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 99
    assert (79, "EDIT") in segments
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
    # Total gaps: 1. Effective fill: 99.
    # 980/1000 * 99 = 97.02 -> 97
    # 20/1000 * 99 = 1.98 -> 2 (min_w)
    # Sum: 99.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 99
    assert (97, "EDIT") in segments
    assert (2, "OTHER") in segments
    assert len(segments) == 2


def test_compute_segments_history_not_grouped_even_if_small():
    fragments = [
        {"chipKind": "EDIT", "tokens": 990},
        {"chipKind": "HISTORY", "tokens": 10},
    ]
    # Total gaps: 1. Effective fill: 99.
    # 990/1000 * 99 = 98.01 -> 98
    # 10/1000 * 99 = 0.99 -> 0.
    # HISTORY is not grouped. Deficit 1. 0.99 has high rem. 0 -> 1.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert sum(w for w, k in segments) == 99
    assert (98, "EDIT") in segments
    assert (1, "HISTORY") in segments


def test_compute_segments_accounts_for_gaps():
    # Width 100, 100% fill. 3 segments.
    # Total gaps: 2. Effective fill: 98.
    fragments = [
        {"chipKind": "EDIT", "tokens": 333},
        {"chipKind": "HISTORY", "tokens": 333},
        {"chipKind": "OTHER", "tokens": 334},
    ]
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    total_w = sum(w for w, k in segments)
    assert total_w == 98
    assert len(segments) == 3
