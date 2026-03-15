"""Tests for review panel widgets."""

import pytest
from textual.app import App, ComposeResult

from brokk_code.review_models import CodeExcerpt, GuidedReview, ReviewSection
from brokk_code.widgets.review_panel import GuidedReviewPanel, ReviewSectionWidget


class ReviewPanelTestApp(App):
    """Test app for GuidedReviewPanel."""

    def __init__(self, review: GuidedReview | None = None):
        super().__init__()
        self._review = review

    def compose(self) -> ComposeResult:
        yield GuidedReviewPanel(id="review-panel")

    def on_mount(self) -> None:
        if self._review is not None:
            self.query_one("#review-panel", GuidedReviewPanel).update_review(self._review)


class ReviewSectionTestApp(App):
    """Test app for ReviewSectionWidget."""

    def __init__(self, section: ReviewSection, expanded: bool = True):
        super().__init__()
        self._section = section
        self._expanded = expanded

    def compose(self) -> ComposeResult:
        yield ReviewSectionWidget(
            section=self._section,
            section_id="test-section",
            expanded=self._expanded,
            id="test-section",
        )


def make_sample_review() -> GuidedReview:
    """Create a sample guided review for testing."""
    return GuidedReview(
        overview="This is the overview of the review.",
        key_changes=[
            ReviewSection(
                title="Added new feature",
                content="Description of the new feature.",
                excerpts=[
                    CodeExcerpt(
                        file_path="src/main.py",
                        start_line=10,
                        end_line=20,
                        content="def new_feature():\n    pass",
                    )
                ],
            ),
            ReviewSection(
                title="Refactored module",
                content="Refactoring details.",
                excerpts=[],
            ),
        ],
        design_notes=[
            ReviewSection(
                title="Consider abstraction",
                content=(
                    "The code could benefit from abstraction.\n\n"
                    "**Recommendation:** Use interfaces."
                ),
                excerpts=[
                    CodeExcerpt(
                        file_path="src/utils.py",
                        start_line=5,
                        end_line=5,
                        content="helper = Helper()",
                    )
                ],
            ),
        ],
        tactical_notes=[
            ReviewSection(
                title="Fix typo",
                content="There's a typo in the variable name.",
                excerpts=[
                    CodeExcerpt(
                        file_path="src/config.py",
                        start_line=42,
                        end_line=44,
                        content="varibale = 1",
                    )
                ],
            ),
        ],
        additional_tests="**Test edge cases:** Ensure empty input is handled.",
    )


@pytest.mark.asyncio
async def test_guided_review_panel_empty_state():
    """Test that an empty panel shows the empty message."""
    app = ReviewPanelTestApp()
    async with app.run_test() as _:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        empty_label = panel.query_one("#review-empty")
        assert "hidden" not in empty_label.classes


@pytest.mark.asyncio
async def test_guided_review_panel_loading_state():
    """Test that loading state shows the loading indicator."""
    app = ReviewPanelTestApp()
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.set_loading(True)
        await pilot.pause()

        loading = panel.query_one("#review-loading")
        assert "hidden" not in loading.classes

        empty_label = panel.query_one("#review-empty")
        assert "hidden" in empty_label.classes


@pytest.mark.asyncio
async def test_guided_review_panel_displays_review():
    """Test that a review is displayed correctly."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as _:
        panel = app.query_one("#review-panel", GuidedReviewPanel)

        # Check overview is displayed
        overview = panel.query_one("#review-overview")
        assert overview is not None

        # Check sections are created
        sections = panel.query(ReviewSectionWidget)
        # 2 key changes + 1 design note + 1 tactical note = 4
        assert len(sections) == 4


@pytest.mark.asyncio
async def test_guided_review_panel_section_ids():
    """Test that section IDs are generated correctly."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as _:
        panel = app.query_one("#review-panel", GuidedReviewPanel)

        assert "key-change-0" in panel._section_ids
        assert "key-change-1" in panel._section_ids
        assert "design-note-0" in panel._section_ids
        assert "tactical-note-0" in panel._section_ids


@pytest.mark.asyncio
async def test_guided_review_panel_keyboard_navigation():
    """Test keyboard navigation between sections."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.focus()
        await pilot.pause()

        # Initial cursor at 0
        assert panel._cursor_index == 0

        # Navigate down
        await pilot.press("j")
        assert panel._cursor_index == 1

        # Navigate up
        await pilot.press("k")
        assert panel._cursor_index == 0

        # Wrap around at top
        await pilot.press("up")
        assert panel._cursor_index == 3  # Last section

        # Wrap around at bottom
        await pilot.press("down")
        assert panel._cursor_index == 0


@pytest.mark.asyncio
async def test_guided_review_panel_toggle_section():
    """Test toggling a section's expanded state."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.focus()
        await pilot.pause()

        # Get the first section - sections start collapsed
        section = panel.query_one("#key-change-0", ReviewSectionWidget)
        assert section.expanded is False

        # Toggle with enter - now expanded
        await pilot.press("enter")
        assert section.expanded is True

        # Toggle again - back to collapsed
        await pilot.press("space")
        assert section.expanded is False


@pytest.mark.asyncio
async def test_guided_review_panel_expand_collapse_all():
    """Test expand/collapse all functionality."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.focus()
        await pilot.pause()

        # Collapse all
        await pilot.press("O")  # Shift+O
        for section_id in panel._section_ids:
            section = panel.query_one(f"#{section_id}", ReviewSectionWidget)
            assert section.expanded is False

        # Expand all
        await pilot.press("o")
        for section_id in panel._section_ids:
            section = panel.query_one(f"#{section_id}", ReviewSectionWidget)
            assert section.expanded is True


@pytest.mark.asyncio
async def test_review_section_widget_renders_content():
    """Test that ReviewSectionWidget renders title and content."""
    section = ReviewSection(
        title="Test Section",
        content="This is the content.",
        excerpts=[],
    )
    app = ReviewSectionTestApp(section)
    async with app.run_test() as _:
        widget = app.query_one("#test-section", ReviewSectionWidget)

        # Verify the section title is in the widget
        assert widget._section.title == "Test Section"


@pytest.mark.asyncio
async def test_review_section_widget_renders_excerpts():
    """Test that ReviewSectionWidget renders code excerpts."""
    section = ReviewSection(
        title="Section with Excerpts",
        content="Content here.",
        excerpts=[
            CodeExcerpt(
                file_path="src/file.py",
                start_line=10,
                end_line=15,
                content="code",
            ),
            CodeExcerpt(
                file_path="src/other.py",
                start_line=5,
                end_line=5,
                content="single",
            ),
        ],
    )
    app = ReviewSectionTestApp(section)
    async with app.run_test() as _:
        widget = app.query_one("#test-section", ReviewSectionWidget)

        excerpts = widget.query(".review-excerpt-item")
        assert len(excerpts) == 2


@pytest.mark.asyncio
async def test_review_section_widget_toggle():
    """Test toggling a ReviewSectionWidget."""
    section = ReviewSection(
        title="Toggleable",
        content="Content",
        excerpts=[],
    )
    app = ReviewSectionTestApp(section, expanded=True)
    async with app.run_test() as pilot:
        widget = app.query_one("#test-section", ReviewSectionWidget)

        assert widget.expanded is True
        assert "collapsed" not in widget.classes

        widget.toggle()
        await pilot.pause()

        assert widget.expanded is False
        assert "collapsed" in widget.classes


@pytest.mark.asyncio
async def test_review_section_widget_collapsed_initial():
    """Test starting a section in collapsed state."""
    section = ReviewSection(
        title="Initially Collapsed",
        content="Hidden content",
        excerpts=[],
    )
    app = ReviewSectionTestApp(section, expanded=False)
    async with app.run_test() as _:
        widget = app.query_one("#test-section", ReviewSectionWidget)

        assert widget.expanded is False
        assert "collapsed" in widget.classes


@pytest.mark.asyncio
async def test_review_section_widget_excerpt_format_single_line():
    """Test excerpt formatting for single line."""
    section = ReviewSection(
        title="Single Line",
        content="Content",
        excerpts=[
            CodeExcerpt(
                file_path="src/file.py",
                start_line=42,
                end_line=42,
                content="line",
            ),
        ],
    )
    app = ReviewSectionTestApp(section)
    async with app.run_test() as _:
        widget = app.query_one("#test-section", ReviewSectionWidget)
        # Use _format_excerpt directly to verify the format
        formatted = widget._format_excerpt(section.excerpts[0])
        assert "src/file.py:42" in formatted
        # Should NOT have range format
        assert "42-42" not in formatted


@pytest.mark.asyncio
async def test_review_section_widget_excerpt_format_range():
    """Test excerpt formatting for line range."""
    section = ReviewSection(
        title="Line Range",
        content="Content",
        excerpts=[
            CodeExcerpt(
                file_path="src/file.py",
                start_line=10,
                end_line=20,
                content="lines",
            ),
        ],
    )
    app = ReviewSectionTestApp(section)
    async with app.run_test() as _:
        widget = app.query_one("#test-section", ReviewSectionWidget)
        # Use _format_excerpt directly to verify the format
        formatted = widget._format_excerpt(section.excerpts[0])
        assert "src/file.py:10-20" in formatted


@pytest.mark.asyncio
async def test_guided_review_panel_clear_review():
    """Test clearing the review."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)

        # Verify review is loaded
        sections = panel.query(ReviewSectionWidget)
        assert len(sections) == 4

        # Clear the review
        panel.clear_review()
        await pilot.pause()

        # Verify empty state
        sections = panel.query(ReviewSectionWidget)
        assert len(sections) == 0

        empty_label = panel.query_one("#review-empty")
        assert "hidden" not in empty_label.classes


@pytest.mark.asyncio
async def test_guided_review_panel_navigation_message():
    """Test that navigation posts SectionNavigated messages."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    messages = []

    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.focus()
        await pilot.pause()

        # Capture messages
        original_post = panel.post_message

        def capture_message(msg):
            messages.append(msg)
            return original_post(msg)

        panel.post_message = capture_message

        # Navigate
        await pilot.press("j")

        # Check message was posted
        nav_messages = [m for m in messages if isinstance(m, GuidedReviewPanel.SectionNavigated)]
        assert len(nav_messages) == 1
        assert nav_messages[0].section_id == "key-change-1"


@pytest.mark.asyncio
async def test_guided_review_panel_active_state():
    """Test that active state is applied to current section."""
    review = make_sample_review()
    app = ReviewPanelTestApp(review)
    async with app.run_test() as pilot:
        panel = app.query_one("#review-panel", GuidedReviewPanel)
        panel.focus()
        await pilot.pause()

        # First section should be active
        first = panel.query_one("#key-change-0", ReviewSectionWidget)
        assert "active" in first.classes

        second = panel.query_one("#key-change-1", ReviewSectionWidget)
        assert "active" not in second.classes

        # Navigate down
        await pilot.press("j")

        assert "active" not in first.classes
        assert "active" in second.classes
