from extract_turn import _extract_response_sections


def test_extract_response_sections_preserves_markdown_headings_in_text() -> None:
    log_text = """\
## reasoningContent
thinking

## text
## Findings
Keep this heading.

### Details
Keep this subheading too.

## toolExecutionRequests
[]

## metadata
{"elapsedMs": 123}
"""

    sections = _extract_response_sections(log_text, 1)

    assert sections["text"] == """\
## Findings
Keep this heading.

### Details
Keep this subheading too."""
    assert sections["toolExecutionRequests"] == "[]"
    assert sections["metadata"] == '{"elapsedMs": 123}'
