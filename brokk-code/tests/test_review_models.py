"""Tests for review_models.py parsing logic."""

import pytest

from brokk_code.review_models import (
    CodeExcerpt,
    GuidedReview,
    ReviewSection,
    parse_guided_review,
)


class TestCodeExcerpt:
    def test_basic_creation(self):
        excerpt = CodeExcerpt(
            file_path="src/main.py",
            start_line=10,
            end_line=15,
            content="def foo():\n    pass",
        )
        assert excerpt.file_path == "src/main.py"
        assert excerpt.start_line == 10
        assert excerpt.end_line == 15
        assert excerpt.content == "def foo():\n    pass"


class TestReviewSection:
    def test_basic_creation(self):
        section = ReviewSection(
            title="Test Section",
            content="This is the content",
            excerpts=[],
        )
        assert section.title == "Test Section"
        assert section.content == "This is the content"
        assert section.excerpts == []

    def test_with_excerpts(self):
        excerpt = CodeExcerpt("file.py", 1, 5, "code")
        section = ReviewSection(
            title="With Excerpts",
            content="Content",
            excerpts=[excerpt],
        )
        assert len(section.excerpts) == 1
        assert section.excerpts[0].file_path == "file.py"


class TestGuidedReview:
    def test_basic_creation(self):
        review = GuidedReview(overview="Overview text")
        assert review.overview == "Overview text"
        assert review.key_changes == []
        assert review.design_notes == []
        assert review.tactical_notes == []
        assert review.additional_tests is None

    def test_with_all_fields(self):
        review = GuidedReview(
            overview="Overview",
            key_changes=[ReviewSection("Change 1", "Description", [])],
            design_notes=[ReviewSection("Design 1", "Note", [])],
            tactical_notes=[ReviewSection("Tactical 1", "Fix", [])],
            additional_tests="Test recommendations",
        )
        assert len(review.key_changes) == 1
        assert len(review.design_notes) == 1
        assert len(review.tactical_notes) == 1
        assert review.additional_tests == "Test recommendations"


class TestParseGuidedReview:
    def test_empty_dict(self):
        review = parse_guided_review({})
        assert review.overview == ""
        assert review.key_changes == []
        assert review.design_notes == []
        assert review.tactical_notes == []
        assert review.additional_tests is None

    def test_overview_only(self):
        data = {"overview": "This is the overview"}
        review = parse_guided_review(data)
        assert review.overview == "This is the overview"

    def test_key_changes_parsing(self):
        data = {
            "overview": "Overview",
            "keyChanges": [
                {
                    "title": "Added new feature",
                    "description": "This change adds X",
                    "excerpts": [
                        {
                            "file": {"relativePath": "src/feature.py"},
                            "line": 42,
                            "excerpt": "def new_feature():\n    return True",
                        }
                    ],
                }
            ],
        }
        review = parse_guided_review(data)
        assert len(review.key_changes) == 1
        kc = review.key_changes[0]
        assert kc.title == "Added new feature"
        assert kc.content == "This change adds X"
        assert len(kc.excerpts) == 1
        assert kc.excerpts[0].file_path == "src/feature.py"
        assert kc.excerpts[0].start_line == 42
        assert kc.excerpts[0].end_line == 43
        assert "def new_feature" in kc.excerpts[0].content

    def test_design_notes_with_recommendation(self):
        data = {
            "overview": "",
            "designNotes": [
                {
                    "title": "Consider refactoring",
                    "description": "The current approach is complex",
                    "recommendation": "Split into smaller functions",
                    "excerpts": [],
                }
            ],
        }
        review = parse_guided_review(data)
        assert len(review.design_notes) == 1
        dn = review.design_notes[0]
        assert dn.title == "Consider refactoring"
        assert "The current approach is complex" in dn.content
        assert "**Recommendation:** Split into smaller functions" in dn.content

    def test_tactical_notes_single_excerpt(self):
        data = {
            "overview": "",
            "tacticalNotes": [
                {
                    "title": "Fix null check",
                    "description": "Missing null check here",
                    "recommendation": "Add if x is not None",
                    "excerpt": {
                        "file": {"relativePath": "src/util.py"},
                        "line": 10,
                        "excerpt": "x.process()",
                    },
                }
            ],
        }
        review = parse_guided_review(data)
        assert len(review.tactical_notes) == 1
        tn = review.tactical_notes[0]
        assert tn.title == "Fix null check"
        assert len(tn.excerpts) == 1
        assert tn.excerpts[0].file_path == "src/util.py"
        assert tn.excerpts[0].start_line == 10

    def test_additional_tests_parsing(self):
        data = {
            "overview": "",
            "additionalTests": [
                {"title": "Test edge case", "recommendation": "Add test for empty input"},
                {"title": "Test error handling", "recommendation": "Verify exception is raised"},
            ],
        }
        review = parse_guided_review(data)
        assert review.additional_tests is not None
        assert "**Test edge case:** Add test for empty input" in review.additional_tests
        assert "**Test error handling:** Verify exception is raised" in review.additional_tests

    def test_file_path_string_format(self):
        data = {
            "keyChanges": [
                {
                    "title": "Change",
                    "description": "Desc",
                    "excerpts": [{"file": "direct/path.py", "line": 1, "excerpt": "code"}],
                }
            ]
        }
        review = parse_guided_review(data)
        assert review.key_changes[0].excerpts[0].file_path == "direct/path.py"

    def test_empty_excerpts_list(self):
        data = {
            "keyChanges": [{"title": "No excerpts", "description": "Description", "excerpts": []}]
        }
        review = parse_guided_review(data)
        assert review.key_changes[0].excerpts == []

    def test_missing_optional_fields(self):
        data = {
            "keyChanges": [{"title": "Minimal"}],
            "designNotes": [{"title": "Minimal design"}],
        }
        review = parse_guided_review(data)
        assert review.key_changes[0].title == "Minimal"
        assert review.key_changes[0].content == ""
        assert review.key_changes[0].excerpts == []

    def test_full_review_structure(self):
        data = {
            "overview": "This PR adds feature X and fixes bug Y.",
            "keyChanges": [
                {
                    "title": "Added feature X",
                    "description": "Implements the X feature as requested",
                    "excerpts": [
                        {
                            "file": {"relativePath": "src/x.py"},
                            "line": 100,
                            "excerpt": "class X:\n    pass",
                        }
                    ],
                }
            ],
            "designNotes": [
                {
                    "title": "Architecture concern",
                    "description": "The coupling is tight",
                    "recommendation": "Consider dependency injection",
                    "excerpts": [],
                }
            ],
            "tacticalNotes": [
                {
                    "title": "Typo in variable",
                    "description": "Variable is misspelled",
                    "recommendation": "Rename to correct spelling",
                    "excerpt": {
                        "file": {"relativePath": "src/y.py"},
                        "line": 50,
                        "excerpt": "teh_value = 1",
                    },
                }
            ],
            "additionalTests": [
                {"title": "Unit test needed", "recommendation": "Add test for X.method()"}
            ],
        }
        review = parse_guided_review(data)
        assert review.overview == "This PR adds feature X and fixes bug Y."
        assert len(review.key_changes) == 1
        assert len(review.design_notes) == 1
        assert len(review.tactical_notes) == 1
        assert review.additional_tests is not None
        assert "Unit test needed" in review.additional_tests
