# Manual Verification Checklist

This checklist verifies the integration of InstructionsPanel and TaskListPanel within a single tabbed container (InstructionsTasksTabbedPanel), correct theming behavior, and persistence of relevant UI state.

## Prerequisites
- A project that opens successfully in Brokk.
- Models configured (at least default ones) so Architect/Ask/Search can run.
- Optional: Git repository for branch-related UI (not required for this checklist).

## 1) Launch and Layout
1. Start the application.
2. Confirm the main window opens without empty/gray panes.
3. Ensure the main layout shows:
   - Output at the top.
   - Workspace (top) and Instructions/Tasks area (bottom).
   - The "Instructions" and "Tasks" tabs are visible in the bottom main input area.

Expected:
- The “Instructions” tab is selected by default.
- No errors in logs.

## 2) Add Tasks and Interact with Task List
1. Switch to the “Tasks” tab.
2. Add several tasks using the task input and Add button (or Enter).
3. Mark some tasks as Done.
4. Reorder tasks via drag-and-drop.
5. Use multi-select with Shift/Ctrl/Cmd and try the Combine/Split actions if available.

Expected:
- Tasks appear with correct order and Done state toggles immediately.
- Drag-and-drop reorders correctly and persists in-session.

## 3) Run Architect on Tasks
1. Select one or more tasks.
2. Click the Play/Run Architect button for selected tasks.
3. Monitor the Output panel for streaming tokens and results.
4. Optionally try “Run All” to execute multiple tasks in sequence.

Expected:
- Buttons in the Instructions and Git sections are disabled while running.
- Output shows LLM progress and results.
- On completion, action buttons are re-enabled and notifications appear.

## 4) Switch Between Tabs
1. Move between “Instructions” and “Tasks” tabs repeatedly.
2. In “Instructions”, type a prompt; in “Tasks”, ensure task edits persist.
3. Confirm focus returns to the correct component when switching (e.g., instructions text area or task input).

Expected:
- State in both tabs is preserved when switching.
- No visual glitches or content loss.

## 5) Theming
1. Open Settings > Global and toggle the theme (Light/Dark).
2. Observe both tabs:
   - Instructions: labels, mode badge (if visible), input border stripe, action button styling.
   - Tasks: list row backgrounds, selection colors, icons/buttons.

Expected:
- Both tabs update colors consistently with the selected theme.
- No unreadable text or mismatched backgrounds.

## 6) Persistence Across Restarts
1. With the “Tasks” tab selected, add or update some tasks (including Done status).
2. Close the application normally.
3. Reopen the application.

Expected:
- The previously active tab restores correctly.
- Tasks (including order and Done state) reload for the current session.
- Drawer proportions and split pane positions restore to reasonable defaults or previous values.

## 7) Keyboard Shortcuts (Spot Check)
- Cmd/Ctrl+Enter: Submit action when Instructions is focused.
- Cmd/Ctrl+K: Switch to the Tasks tab.
- Cmd/Ctrl+T: Open the Terminal drawer (if configured in settings).
- Cmd/Ctrl+M: Toggle Code/Ask in Instructions.

Expected:
- Shortcuts trigger the correct behavior and do not collide with text editing.

## Notes
- If Git is available, confirm branch UI in Instructions reflects current branch after checkout.
- For large outputs, verify search-ability and scrolling in Output.
- If models lack vision, image-related Ask/Code actions should prompt an actionable error.
