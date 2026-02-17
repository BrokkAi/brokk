import pytest
from brokk_code.widgets.token_bar import TokenBar


def test_compute_segments_empty():
    assert TokenBar.compute_segments(100, 0, 1000, []) == []


def test_compute_segments_basic_proportions():
    fragments = [
        {"chipKind": "EDIT", "tokens": 500},
        {"chipKind": "HISTORY", "tokens": 500},
    ]
    # 50% usage of 1000 max. In a 100-wide bar, total fill is 50.
    # Each gets 25.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert segments == [(50, "EDIT"), (50, "HISTORY")]


def test_compute_segments_summary_grouping():
    fragments = [
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "SUMMARY", "tokens": 100},
        {"chipKind": "EDIT", "tokens": 800},
    ]
    # Total 1000. 100% fill on width 100.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    # Expected: 80 EDIT, 20 SUMMARIES
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
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    # 980 -> 98. 20 (Others) -> 2.
    assert (98, "EDIT") in segments
    assert (2, "OTHER") in segments
    assert len(segments) == 2


def test_compute_segments_history_not_grouped_even_if_small():
    fragments = [
        {"chipKind": "EDIT", "tokens": 990},
        {"chipKind": "HISTORY", "tokens": 10},
    ]
    # HISTORY is 1.0 wide. Should NOT be grouped, and should be at least 1 wide (or min width if we prefer)
    # In our implementation, HISTORY isn't grouped but also doesn't have a min_w of 2 unless it hits floor logic.
    segments = TokenBar.compute_segments(100, 1000, 1000, fragments)
    assert (99, "EDIT") in segments
    assert (1, "HISTORY") in segments
