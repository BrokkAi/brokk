# Fragment Preview Integration Testing

## Overview

This document describes manual verification steps to ensure fragment preview behavior is consistent across all UI entry points in Brokk.

All fragment previews route through a single unified entry point: `Chrome.openFragmentPreview(ContextFragment)`.
This guarantees consistent titles, content rendering, window reuse, and async loading behavior.

## Key Scenarios

### Scenario 1: Workspace Chip Click
**Entry Point:** `WorkspaceChip.onPrimaryClick()` → `Chrome.openFragmentPreview()`

1. Open Brokk with a context containing multiple fragments (e.g., a file, a search result, a summary)
2. In the Workspace panel, click on a single fragment chip (e.g., "File: foo.java")
3. Verify:
   - Preview window opens with correct title (e.g., "Preview: foo.java")
   - Content displays correctly (syntax-highlighted code, markdown, or image as appropriate)
   - Window stays focused and is ready for interaction

### Scenario 2: Workspace Table Row Double-Click
**Entry Point:** `WorkspacePanel.buildContextPanel()` JTable double-click handler → `WorkspacePanel.showFragmentPreview()` → `Chrome.openFragmentPreview()`

1. Open Brokk with a context containing multiple fragments
2. In the Workspace panel table, double-click on a row (e.g., the "foo.java" file row)
3. Verify:
   - Same preview window opens (if one was already open from Scenario 1, it should be reused and updated)
   - Title matches the one from Scenario 1
   - Content is identical

### Scenario 3: Workspace Action Menu "Show Contents"
**Entry Point:** `WorkspaceAction.createFragmentAction()` (VIEW_FILE/SHOW_CONTENTS) → `WorkspacePanel.showFragmentPreview()` → `Chrome.openFragmentPreview()`

1. Open Brokk with a context containing multiple fragments
2. Right-click on a fragment in the Workspace table
3. Select "Show Contents" (or "View File" for PROJECT_PATH fragments)
4. Verify:
   - Same preview window opens (or is reused if already open)
   - Title and content match Scenarios 1 and 2

### Scenario 4: TokenUsageBar Single-Fragment Segment Click
**Entry Point:** `TokenUsageBar.mouseClicked()` → `Chrome.openFragmentPreview()` (single fragment)

1. Open Brokk with a context containing a mix of editable files, summaries, and output fragments
2. In the TokenUsageBar (colored segment bar at top of Workspace), click on a single-fragment segment (not a grouped "Summaries" or "Other" segment)
3. Verify:
   - Preview window opens with correct title
   - Content displays correctly
   - Window behavior matches Scenarios 1–3

### Scenario 5: Window Reuse Consistency
**Tests:** Preview window reuse across multiple entry points

1. Complete Scenario 1 (click a chip, preview opens)
2. Without closing the preview window, complete Scenario 2 (double-click same row in table)
3. Verify:
   - Same preview window is reused (not a new one)
   - Content and title are correctly updated

4. Without closing the preview window, complete Scenario 3 (right-click → "Show Contents")
5. Verify:
   - Same preview window is reused again
   - Content and title remain consistent

### Scenario 6: Computed Fragment Description Resolution
**Tests:** Async title updates for dynamic fragments

1. Open Brokk and add a computed/dynamic fragment (e.g., a Usage fragment or Code fragment)
2. In the Workspace, click on the chip before the computed description is resolved
3. Verify:
   - Preview window opens with title "Preview: Loading..." or similar placeholder
   - Once the description is resolved, the window title updates to the final description (e.g., "Preview: Usage for myMethod()")
   - Content loads correctly in the background

### Scenario 7: Image Fragment Preview
**Tests:** Image previews (both file-based and pasted images)

1. Add an image fragment to the context (either a ProjectFile image or a pasted image)
2. Click on the image chip in Workspace
3. Verify:
   - Preview window opens with image displayed
   - Title reflects the image source (file name or description)
   - Window is properly sized and rendered

### Scenario 8: Output Fragment (History/Task) Preview
**Tests:** Output fragment previews (Task entries, History fragments)

1. Add a Task or History fragment to the context
2. Click on the chip in Workspace
3. Verify:
   - Preview window opens with searchable markdown content
   - Search bar is present and functional
   - Title reflects the fragment description
   - Content rendering is consistent with other preview types

## Automated Assertions

The following lightweight runtime assertion is included in `Chrome.openFragmentPreview()`:

```java
assert fragment != null : "openFragmentPreview called with null fragment";
assert fragment.id() != null && !fragment.id().isBlank() : "Fragment has invalid/empty ID: " + fragment;
```

These assertions will catch programming errors (null fragments, invalid IDs) and help ensure correct behavior during development.

## Known Limitations & Special Cases

### Zero-Width Segments
Some TokenUsageBar segments (particularly HISTORY fragments) may have zero width and be un-hoverable. These segments may not be clickable. See task history (Task 4) for details.

### Grouped Segments ("Summaries", "Other")
TokenUsageBar may combine multiple fragments into a single grouped segment. Clicking a grouped segment triggers a combined preview or generic handler, which may differ from clicking individual chips. This is expected and documented in the TokenUsageBar implementation.

### Window Reuse Key Generation
Preview windows are reused based on a key generated from the content type and title. File-based previews use the file path as part of the key; fragment previews use the title. Ensure that titles are stable and consistent across entry points to maintain proper window reuse.

## Manual Verification Checklist

Use this checklist for regular QA:

- [ ] Scenario 1: Workspace chip click opens correct preview
- [ ] Scenario 2: Table double-click reuses same preview window
- [ ] Scenario 3: Action menu "Show Contents" reuses same preview window
- [ ] Scenario 4: TokenUsageBar single-segment click shows correct preview
- [ ] Scenario 5: Window reuse is consistent across multiple entry points
- [ ] Scenario 6: Computed descriptions resolve and update titles correctly
- [ ] Scenario 7: Image fragment previews display correctly
- [ ] Scenario 8: Output fragment previews display with search functionality
- [ ] All preview windows have correct titles (no truncation, no "Loading..." persistence)
- [ ] Content rendering is consistent across all entry points (same font, syntax highlighting, margins)

## Questions or Issues?

If preview behavior is inconsistent or titles/content do not match across entry points, check:

1. **Fragment ID validity:** Verify fragment.id() is non-empty and unique
2. **Computed fragment resolution:** Ensure computed descriptions complete and update titles
3. **Window key generation:** Check `Chrome.generatePreviewWindowKey()` is stable
4. **Content reuse:** Verify `PreviewTextPanel` and `PreviewImagePanel` correctly update content when reused
5. **Entry point routing:** Confirm all preview calls eventually call `Chrome.openFragmentPreview()`

Refer to the code comments in `Chrome.openFragmentPreview()` and `WorkspacePanel.showFragmentPreview()` for additional details.
