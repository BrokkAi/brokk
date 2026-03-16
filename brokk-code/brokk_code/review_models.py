"""Python dataclasses mirroring Java ReviewParser output structures."""

from dataclasses import dataclass, field
from typing import Any, Dict, List


@dataclass
class CodeExcerpt:
    """A code excerpt from a review, referencing a specific location in a file."""

    file_path: str
    start_line: int
    end_line: int
    content: str


@dataclass
class ReviewSection:
    """A section of a review with a title, content, and optional code excerpts."""

    title: str
    content: str
    excerpts: List[CodeExcerpt] = field(default_factory=list)


@dataclass
class GuidedReview:
    """A structured code review with overview, changes, and feedback sections."""

    overview: str
    key_changes: List[ReviewSection] = field(default_factory=list)
    design_notes: List[ReviewSection] = field(default_factory=list)
    tactical_notes: List[ReviewSection] = field(default_factory=list)
    additional_tests: List[ReviewSection] = field(default_factory=list)


def _parse_excerpt(data: Dict[str, Any]) -> CodeExcerpt:
    """Parse a single CodeExcerpt from JSON data."""
    file_path = data.get("file", {})
    if isinstance(file_path, dict):
        file_path = file_path.get("relativePath", "") or file_path.get("path", "")
    elif not isinstance(file_path, str):
        file_path = str(file_path) if file_path else ""

    line = data.get("line", 1)
    excerpt_content = data.get("text", "")
    line_count = excerpt_content.count("\n") + 1 if excerpt_content else 1

    return CodeExcerpt(
        file_path=file_path,
        start_line=line,
        end_line=line + line_count - 1,
        content=excerpt_content,
    )


def _parse_excerpts(excerpts_data: Any) -> List[CodeExcerpt]:
    """Parse a list of excerpts from JSON data."""
    if not excerpts_data or not isinstance(excerpts_data, list):
        return []
    return [_parse_excerpt(e) for e in excerpts_data if isinstance(e, dict)]


def _parse_key_change(data: Dict[str, Any]) -> ReviewSection:
    """Parse a KeyChanges entry into ReviewSection."""
    return ReviewSection(
        title=data.get("title", ""),
        content=data.get("description", ""),
        excerpts=_parse_excerpts(data.get("excerpts")),
    )


def _parse_design_note(data: Dict[str, Any]) -> ReviewSection:
    """Parse a DesignFeedback entry into ReviewSection."""
    description = data.get("description", "")
    recommendation = data.get("recommendation", "")
    content = description
    if recommendation:
        if content:
            content += "\n\n**Recommendation:** " + recommendation
        else:
            content = "**Recommendation:** " + recommendation

    return ReviewSection(
        title=data.get("title", ""),
        content=content,
        excerpts=_parse_excerpts(data.get("excerpts")),
    )


def _parse_tactical_note(data: Dict[str, Any]) -> ReviewSection:
    """Parse a TacticalFeedback entry into ReviewSection."""
    description = data.get("description", "")
    recommendation = data.get("recommendation", "")
    content = description
    if recommendation:
        if content:
            content += "\n\n**Recommendation:** " + recommendation
        else:
            content = "**Recommendation:** " + recommendation

    excerpts = _parse_excerpts(data.get("excerpts"))

    return ReviewSection(
        title=data.get("title", ""),
        content=content,
        excerpts=excerpts,
    )


def _parse_additional_tests(tests_data: Any) -> List[ReviewSection]:
    """Parse additionalTests list into a list of ReviewSection objects."""
    if not tests_data or not isinstance(tests_data, list):
        return []

    sections: List[ReviewSection] = []
    for test in tests_data:
        if not isinstance(test, dict):
            continue
        title = test.get("title", "")
        recommendation = test.get("recommendation", "")
        if title or recommendation:
            sections.append(
                ReviewSection(
                    title=title or "Test",
                    content=recommendation,
                    excerpts=[],
                )
            )

    return sections


def parse_guided_review(data: Dict[str, Any]) -> GuidedReview:
    """Convert executor JSON response to GuidedReview dataclass.

    Args:
        data: JSON dict from the executor containing the guided review structure.
              Expected keys: overview, keyChanges, designNotes, tacticalNotes, additionalTests

    Returns:
        A GuidedReview instance populated from the JSON data.
    """
    key_changes_data = data.get("keyChanges", [])
    key_changes = [_parse_key_change(kc) for kc in key_changes_data if isinstance(kc, dict)]

    design_notes_data = data.get("designNotes", [])
    design_notes = [_parse_design_note(dn) for dn in design_notes_data if isinstance(dn, dict)]

    tactical_notes_data = data.get("tacticalNotes", [])
    tactical_notes = [
        _parse_tactical_note(tn) for tn in tactical_notes_data if isinstance(tn, dict)
    ]

    additional_tests = _parse_additional_tests(data.get("additionalTests"))

    return GuidedReview(
        overview=data.get("overview", ""),
        key_changes=key_changes,
        design_notes=design_notes,
        tactical_notes=tactical_notes,
        additional_tests=additional_tests,
    )
