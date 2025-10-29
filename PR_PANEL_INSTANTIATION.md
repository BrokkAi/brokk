# Panels that must be instantiated per-dialog (PR notes)

This document records which UI panels must be created as fresh instances when opened in modeless dialogs
(rather than being reparented from the main UI). Include this note in the PR description so reviewers
understand why we avoid reparenting these components.

Required fresh instantiation
- TerminalPanel
  - Reason: Manages a PTY/process and platform resources. A TerminalPanel should be created per-dialog and
    must be disposed when the dialog is closed to avoid leaving OS processes or PTYs running.
  - Implementation note: MenuBar uses an `onClose` Runnable that calls `TerminalPanel.dispose()` for the Terminal dialog.

- GitWorktreeTab
  - Reason: The Worktrees tab is shown as a dialog-backed tab in some builds. Reparenting it out of the main UI
    can lead to subtle state and event-dispatch issues; a fresh instance is safer for a dialog-backed view.
  - Implementation note: MenuBar always creates a new `GitWorktreeTab` when opening the Worktrees dialog.

Additional reviewer notes
- BlitzForge Tools item is intentionally unchanged; do not modify gating code for drawers or advanced-mode visibility.
- MenuBar defines stable dialog key constants used when opening modeless dialogs:
  - DIALOG_KEY_ISSUES, DIALOG_KEY_TERMINAL, DIALOG_KEY_PULL_REQUESTS, DIALOG_KEY_GIT_LOG, DIALOG_KEY_WORKTREES, DIALOG_KEY_CHANGES
- Callers that create panels which manage OS/native resources (e.g. TerminalPanel) MUST provide an `onClose` Runnable that disposes the component. MenuBar will attempt a best-effort fallback dispose for TerminalPanel if `onClose` is absent, but callers should prefer to supply explicit cleanup.
