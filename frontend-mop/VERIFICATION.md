# Frontend UI Toggle Verification Guide

## Summary

This document provides manual verification steps for the **AI Summary Toggle** feature in `ThreadBlock.svelte`.

## Feature Overview

When a conversation history task is **compressed** (has an AI summary), the frontend now:

1. **Defaults to showing full messages** with a distinct purple left border
2. **Displays an "AI summary" badge** in the collapsed header (and inline in expanded view)
3. **Toggles to show the summary only** when the badge is clicked
4. **Changes badge to "Full messages"** to allow toggling back
5. **Uses show/hide** of already-parsed bubbles (no re-parsing) for performance

## Prerequisites

- Start the Brokk application
- Ensure you have a history task that is **compressed** (has both `summary` and `messages`)
- The backend should send a `history-task` event with `compressed=true`, containing both `summary` and `messages`

## Verification Steps

### 1. Visual Indicators (Compressed Thread)

**Expected:**
- History thread has a **purple/violet left border** (`--summary-border-color`)
- If not already collapsed, thread shows all message bubbles

**Steps:**
1. Open the MOP chat interface
2. Locate a compressed history task (one that has an AI summary)
3. Verify the left border is a distinct color (purple/violet) different from normal threads

### 2. Badge in Collapsed Header

**Expected:**
- When thread is **collapsed**, an **"AI summary" badge** appears in the header (right side, near copy/delete buttons)
- Badge has a purple/violet text color

**Steps:**
1. Collapse a compressed history thread (click the thread or its chevron)
2. Look at the collapsed header
3. Verify the **"AI summary"** badge is visible and purple-colored
4. Normal (non-compressed) threads should **not** have this badge

### 3. Toggle to Summary View

**Expected:**
- Click the **"AI summary"** badge
- Only the summary bubble is displayed (single SYSTEM-type message with full summary text)
- Badge text changes to **"Full messages"**
- Summary bubble is still fully parsed and rendered (no re-parsing needed)

**Steps:**
1. In collapsed header view, click the **"AI summary"** badge
2. Verify the thread expands to show **only the summary bubble**
3. Verify the badge now reads **"Full messages"**
4. Verify the summary content is properly formatted (markdown, code blocks, etc.)

### 4. Toggle Back to Full Messages

**Expected:**
- Click the **"Full messages"** badge
- All original message bubbles are displayed (not just the summary)
- Badge text changes back to **"AI summary"**
- No delay or re-parsing occurs (bubbles are already in DOM)

**Steps:**
1. With summary view active, click the **"Full messages"** badge
2. Verify all original message bubbles appear instantly
3. Verify the badge text reverts to **"AI summary"**
4. Verify metadata (diff stats, message count, line count) reflects the full messages, not the summary

### 5. Inline Metadata in Expanded View

**Expected:**
- When thread is **expanded**, the metadata overlay (showing adds/dels, message count, copy/delete buttons) is displayed
- Metadata includes the **summary toggle badge** inline
- Badge is clickable and toggles as expected

**Steps:**
1. Expand a compressed history thread
2. Look for the metadata row at the top (showing "+X -Y • N msgs • M lines")
3. Verify the **"AI summary"** or **"Full messages"** badge appears
4. Click the badge and verify it toggles the view (summary ↔ full messages)

### 6. Diff Stats & Line Count

**Expected:**
- **Full messages view**: diff stats and line count reflect the original messages
- **Summary view**: only the summary bubble is shown (metrics may vary)

**Steps:**
1. Toggle between summary and full messages views
2. In full messages view, verify adds/dels count matches the original conversation
3. In summary view, the metrics should reflect the summary bubble only

### 7. Copy Functionality

**Expected:**
- Copy button still works in both views
- In full messages view, copies all messages as XML
- In summary view, copies only the summary bubble as XML

**Steps:**
1. Toggle to full messages view, click copy button
2. Paste into a text editor; verify all original messages are copied
3. Toggle to summary view, click copy button
4. Paste into a text editor; verify only the summary is copied

### 8. Performance (Show/Hide, No Re-parsing)

**Expected:**
- Toggling between summary and full messages is **instant** with no visible parsing delay
- No spinner or parsing indication should appear

**Steps:**
1. Expand a compressed thread showing full messages
2. Click the badge to switch to summary (should be instant)
3. Click the badge again to switch back (should be instant)
4. Repeat several times; verify no parsing delay or spinner

## CSS Variables

The feature uses the following CSS variables (can be customized in theme):

- `--summary-border-color`: Border color for compressed threads (default: `#9b59b6` / purple)
- `--summary-badge-color`: Badge text color (default: `#9b59b6` / purple)

To override in a theme, add to CSS:
```css
:root {
  --summary-border-color: #your-color;
  --summary-badge-color: #your-color;
}
```

## Troubleshooting

### Badge doesn't appear
- Verify the backend is sending `compressed=true` in the `history-task` event
- Check browser DevTools > Network > WebSocket to see the event payload
- Verify `task.compressed === true` in historyStore

### Toggle doesn't work
- Check browser DevTools > Console for errors
- Verify `summaryBubble` exists (bubble with `isSummary: true`)
- Verify `onToggleSummary` handler is defined and connected

### Border color not showing
- Check CSS variables are defined in your theme
- Verify `data-compressed="true"` attribute is on the thread-block element
- Check browser DevTools > Inspect Element on the thread header

## Related Files

- `frontend-mop/src/components/ThreadBlock.svelte` — Main toggle logic and rendering
- `frontend-mop/src/components/ThreadMeta.svelte` — Badge rendering and styling
- `frontend-mop/src/stores/historyStore.ts` — Summary bubble creation (`isSummary: true`)
- `frontend-mop/src/MOP.svelte` — Passes `compressed` prop to ThreadBlock
