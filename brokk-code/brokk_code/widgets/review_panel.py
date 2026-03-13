"""Review panel widget for displaying structured guided review data."""

from typing import List, Optional

from rich.panel import Panel
from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.message import Message
from textual.widgets import Label, LoadingIndicator, Static

from brokk_code.review_models import CodeExcerpt, GuidedReview, ReviewSection
from brokk_code.widgets.chat_panel import DotMarkdown


class ReviewSectionWidget(Static):
    """A collapsible section of a guided review with title, content, and code excerpts."""

    DEFAULT_CSS = """
    ReviewSectionWidget {
        width: 1fr;
        height: auto;
        margin: 0 0 1 0;
    }
    ReviewSectionWidget.collapsed .review-section-body {
        display: none;
    }
    ReviewSectionWidget .review-section-header {
        width: 1fr;
        height: auto;
        padding: 0 1;
        background: $surface;
    }
    ReviewSectionWidget .review-section-header:hover {
        background: $surface-lighten-1;
    }
    ReviewSectionWidget .review-section-header:focus {
        background: $primary-darken-2;
    }
    ReviewSectionWidget.active .review-section-header {
        background: $primary-darken-2;
    }
    ReviewSectionWidget .review-section-body {
        width: 1fr;
        height: auto;
        padding: 0 1 0 2;
    }
    ReviewSectionWidget .review-section-content {
        width: 1fr;
        height: auto;
    }
    ReviewSectionWidget .review-excerpt-list {
        width: 1fr;
        height: auto;
        margin: 1 0 0 0;
    }
    ReviewSectionWidget .review-excerpt-item {
        width: 1fr;
        height: auto;
        color: $text-muted;
    }
    """

    class Toggled(Message):
        """Posted when the section is toggled."""

        def __init__(self, section_id: str, expanded: bool) -> None:
            self.section_id = section_id
            self.expanded = expanded
            super().__init__()

    class Selected(Message):
        """Posted when the section header is clicked or focused."""

        def __init__(self, section_id: str) -> None:
            self.section_id = section_id
            super().__init__()

    def __init__(
        self,
        section: ReviewSection,
        section_id: str,
        expanded: bool = True,
        *args,
        **kwargs,
    ) -> None:
        super().__init__(*args, **kwargs)
        self._section = section
        self._section_id = section_id
        self._expanded = expanded

    @property
    def section_id(self) -> str:
        return self._section_id

    @property
    def expanded(self) -> bool:
        return self._expanded

    def compose(self) -> ComposeResult:
        title_text = self._collapsible_title(self._section.title, self._expanded)
        yield Static(title_text, classes="review-section-header", markup=True)
        with Vertical(classes="review-section-body"):
            if self._section.content:
                yield Static(
                    DotMarkdown(self._section.content),
                    classes="review-section-content",
                )
            if self._section.excerpts:
                with Vertical(classes="review-excerpt-list"):
                    for excerpt in self._section.excerpts:
                        yield Static(
                            self._format_excerpt(excerpt),
                            classes="review-excerpt-item",
                            markup=True,
                        )

    def _collapsible_title(self, label: str, expanded: bool) -> str:
        """Returns a consistent title for collapsible sections."""
        state = "[-]" if expanded else "[+]"
        action = "collapse" if expanded else "expand"
        return f"[bold]{label}[/bold] {state} (enter to {action})"

    def _format_excerpt(self, excerpt: CodeExcerpt) -> str:
        """Format a code excerpt as a file:line reference."""
        if excerpt.start_line == excerpt.end_line:
            return f"[dim]  {excerpt.file_path}:{excerpt.start_line}[/dim]"
        return f"[dim]  {excerpt.file_path}:{excerpt.start_line}-{excerpt.end_line}[/dim]"

    def on_click(self) -> None:
        """Handle click to toggle expansion."""
        self._toggle()

    def on_mount(self) -> None:
        """Set initial collapsed state."""
        if not self._expanded:
            self.add_class("collapsed")

    def toggle(self) -> None:
        """Public method to toggle expansion state."""
        self._toggle()

    def _toggle(self) -> None:
        """Toggle the expanded/collapsed state."""
        self._expanded = not self._expanded
        self.toggle_class("collapsed")
        header = self.query_one(".review-section-header", Static)
        header.update(self._collapsible_title(self._section.title, self._expanded))
        self.post_message(self.Toggled(self._section_id, self._expanded))

    def set_active(self, active: bool) -> None:
        """Set the active state for keyboard navigation highlighting."""
        self.set_class(active, "active")


class GuidedReviewPanel(Vertical, can_focus=True):
    """Panel for displaying a structured guided code review."""

    BINDINGS = [
        Binding("up", "cursor_prev", "Previous Section", show=False),
        Binding("k", "cursor_prev", "Previous Section", show=False),
        Binding("down", "cursor_next", "Next Section", show=False),
        Binding("j", "cursor_next", "Next Section", show=False),
        Binding("enter", "toggle_section", "Toggle Section", show=False),
        Binding("space", "toggle_section", "Toggle Section", show=False),
        Binding("o", "expand_all", "Expand All", show=False),
        Binding("O", "collapse_all", "Collapse All", show=False),
    ]

    DEFAULT_CSS = """
    GuidedReviewPanel {
        width: 1fr;
        height: 1fr;
        background: $surface;
    }
    GuidedReviewPanel #review-header {
        width: 1fr;
        height: auto;
        padding: 1;
        background: $primary-background;
    }
    GuidedReviewPanel #review-title {
        text-style: bold;
    }
    GuidedReviewPanel #review-scroll {
        width: 1fr;
        height: 1fr;
    }
    GuidedReviewPanel #review-content {
        width: 1fr;
        height: auto;
        padding: 1;
    }
    GuidedReviewPanel #review-overview {
        width: 1fr;
        height: auto;
        margin: 0 0 1 0;
    }
    GuidedReviewPanel #review-overview-panel {
        border: round $primary;
    }
    GuidedReviewPanel .review-section-group {
        width: 1fr;
        height: auto;
        margin: 1 0 0 0;
    }
    GuidedReviewPanel .review-group-title {
        width: 1fr;
        height: auto;
        text-style: bold;
        color: $text;
        margin: 0 0 1 0;
    }
    GuidedReviewPanel #review-loading {
        width: 1fr;
        height: auto;
        content-align: center middle;
        padding: 2;
    }
    GuidedReviewPanel #review-empty {
        width: 1fr;
        height: auto;
        content-align: center middle;
        padding: 2;
        color: $text-muted;
    }
    GuidedReviewPanel #review-help {
        width: 1fr;
        height: auto;
        dock: bottom;
        padding: 0 1;
        background: $surface;
        color: $text-muted;
    }
    GuidedReviewPanel .hidden {
        display: none;
    }
    """

    class SectionNavigated(Message):
        """Posted when the user navigates to a section."""

        def __init__(self, section_id: str) -> None:
            self.section_id = section_id
            super().__init__()

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._review: Optional[GuidedReview] = None
        self._loading: bool = False
        self._section_ids: List[str] = []
        self._cursor_index: int = -1

    def compose(self) -> ComposeResult:
        with Vertical(id="review-header"):
            yield Label("Guided Review", id="review-title")
        with VerticalScroll(id="review-scroll"):
            yield Vertical(id="review-content")
        yield LoadingIndicator(id="review-loading", classes="hidden")
        yield Static("No review loaded", id="review-empty")
        yield Static(self._get_help_text(), id="review-help")

    def _get_help_text(self) -> str:
        """Build help text from bindings."""
        return (
            "[b]j/k[/b] Nav  [b]Enter[/b] Toggle  [b]o/O[/b] Expand/Collapse All  [b]Esc[/b] Close"
        )

    def on_mount(self) -> None:
        """Initial state setup."""
        self._update_visibility()

    def set_loading(self, loading: bool) -> None:
        """Show or hide the loading indicator."""
        self._loading = loading
        self._update_visibility()

    def _update_visibility(self) -> None:
        """Update visibility of loading/empty/content states."""
        loading_indicator = self.query_one("#review-loading", LoadingIndicator)
        empty_label = self.query_one("#review-empty", Static)
        scroll = self.query_one("#review-scroll", VerticalScroll)

        if self._loading:
            loading_indicator.remove_class("hidden")
            empty_label.add_class("hidden")
            scroll.add_class("hidden")
        elif self._review is None:
            loading_indicator.add_class("hidden")
            empty_label.remove_class("hidden")
            scroll.add_class("hidden")
        else:
            loading_indicator.add_class("hidden")
            empty_label.add_class("hidden")
            scroll.remove_class("hidden")

    def update_review(self, review: GuidedReview) -> None:
        """Update the panel with a new guided review."""
        self._review = review
        self._loading = False
        self._render_review()
        self._update_visibility()

    def clear_review(self) -> None:
        """Clear the current review."""
        self._review = None
        self._section_ids = []
        self._cursor_index = -1
        content = self.query_one("#review-content", Vertical)
        content.remove_children()
        self._update_visibility()

    def _render_review(self) -> None:
        """Render the guided review content."""
        if self._review is None:
            return

        content = self.query_one("#review-content", Vertical)
        content.remove_children()
        self._section_ids = []

        # Overview section
        if self._review.overview:
            overview_panel = Panel(
                DotMarkdown(self._review.overview),
                title="Overview",
                title_align="left",
                border_style="blue",
            )
            content.mount(Static(overview_panel, id="review-overview"))

        # Key Changes
        if self._review.key_changes:
            self._mount_section_group(
                content, "Key Changes", self._review.key_changes, "key-change"
            )

        # Design Notes
        if self._review.design_notes:
            self._mount_section_group(
                content, "Design Notes", self._review.design_notes, "design-note"
            )

        # Tactical Notes
        if self._review.tactical_notes:
            self._mount_section_group(
                content, "Tactical Notes", self._review.tactical_notes, "tactical-note"
            )

        # Additional Tests
        if self._review.additional_tests:
            tests_panel = Panel(
                DotMarkdown(self._review.additional_tests),
                title="Additional Tests",
                title_align="left",
                border_style="green",
            )
            content.mount(Static(tests_panel, classes="review-section-group"))

        # Set initial cursor
        if self._section_ids:
            self._cursor_index = 0
            # Defer active state refresh until widgets are mounted
            self.call_after_refresh(self._refresh_active_states)

    def _mount_section_group(
        self,
        container: Vertical,
        group_title: str,
        sections: List[ReviewSection],
        id_prefix: str,
    ) -> None:
        """Mount a group of review sections."""
        # Build all children first, then mount the group with children via container
        children: list = [Label(group_title, classes="review-group-title")]

        for i, section in enumerate(sections):
            section_id = f"{id_prefix}-{i}"
            self._section_ids.append(section_id)
            widget = ReviewSectionWidget(
                section=section,
                section_id=section_id,
                expanded=True,
                id=section_id,
            )
            children.append(widget)

        group = Vertical(*children, classes="review-section-group")
        container.mount(group)

    def _refresh_active_states(self) -> None:
        """Update active class on all section widgets."""
        current_id = self._current_section_id()
        for section_id in self._section_ids:
            try:
                widget = self.query_one(f"#{section_id}", ReviewSectionWidget)
                widget.set_active(section_id == current_id)
            except Exception:
                pass

    def _current_section_id(self) -> Optional[str]:
        """Get the currently selected section ID."""
        if self._cursor_index < 0 or self._cursor_index >= len(self._section_ids):
            return None
        return self._section_ids[self._cursor_index]

    def _scroll_to_current(self) -> None:
        """Scroll to make the current section visible."""
        section_id = self._current_section_id()
        if not section_id:
            return
        try:
            widget = self.query_one(f"#{section_id}", ReviewSectionWidget)
            widget.scroll_visible()
        except Exception:
            pass

    def action_cursor_prev(self) -> None:
        """Move to the previous section."""
        if not self._section_ids:
            return
        if self._cursor_index < 0:
            self._cursor_index = 0
        elif self._cursor_index == 0:
            self._cursor_index = len(self._section_ids) - 1
        else:
            self._cursor_index -= 1
        self._refresh_active_states()
        self._scroll_to_current()
        self._post_navigation()

    def action_cursor_next(self) -> None:
        """Move to the next section."""
        if not self._section_ids:
            return
        if self._cursor_index >= len(self._section_ids) - 1:
            self._cursor_index = 0
        else:
            self._cursor_index += 1
        self._refresh_active_states()
        self._scroll_to_current()
        self._post_navigation()

    def action_toggle_section(self) -> None:
        """Toggle the current section's expanded state."""
        section_id = self._current_section_id()
        if not section_id:
            return
        try:
            widget = self.query_one(f"#{section_id}", ReviewSectionWidget)
            widget.toggle()
        except Exception:
            pass

    def action_expand_all(self) -> None:
        """Expand all sections."""
        for section_id in self._section_ids:
            try:
                widget = self.query_one(f"#{section_id}", ReviewSectionWidget)
                if not widget.expanded:
                    widget.toggle()
            except Exception:
                pass

    def action_collapse_all(self) -> None:
        """Collapse all sections."""
        for section_id in self._section_ids:
            try:
                widget = self.query_one(f"#{section_id}", ReviewSectionWidget)
                if widget.expanded:
                    widget.toggle()
            except Exception:
                pass

    def _post_navigation(self) -> None:
        """Post a navigation message for the current section."""
        section_id = self._current_section_id()
        if section_id:
            self.post_message(self.SectionNavigated(section_id))

    def on_review_section_widget_selected(self, message: ReviewSectionWidget.Selected) -> None:
        """Handle section selection."""
        try:
            idx = self._section_ids.index(message.section_id)
            self._cursor_index = idx
            self._refresh_active_states()
        except ValueError:
            pass
