# SessionChangesPanel — Partial Commit / Stash Verification (GitHub Issue #2587)

Purpose
- Provide a clear, repeatable verification for the partial commit/stash behavior in SessionChangesPanel.
- This documents manual test steps because the repository currently does not contain a lightweight automated Swing harness for SessionChangesPanel tests. If/when a Swing test harness (EDT-friendly) is introduced, these steps should be converted to automated tests using TestContextManager and TestRepo/TestGitRepo utilities.

Background
- A past regression caused the Commit/Stash context-menu actions on the SessionChangesPanel file tree to operate on a single default file instead of the selected set of files. The SessionChangesPanel implementation intentionally uses the exact selection provided by FileTreePanel when opening the Commit dialog or invoking createPartialStash.
- This document encodes the manual verification checklist to guard against regressions (GitHub Issue #2587).

Manual Test Checklist (Windows / macOS / Linux)
- Preconditions:
  - Have a working repository checked out with at least three tracked files under the project root.
  - Ensure the repository has at least two modified (unstaged or staged) files so a multi-selection makes sense.
  - Launch the Brokk application in a development build that includes the SessionChangesPanel changes.

1) Open the Changes/Review Panel
- Open the Right panel -> Review or Build section so that SessionChangesPanel is visible.
- Ensure the "Review" view shows the file tree / list of modified files.

2) Multi-select files in the file tree
- In the file tree (the left file tree inside the SessionChangesPanel), select multiple modified files:
  - Click the first file, then Ctrl+Click (Cmd+Click on macOS) additional files to build a multi-selection.
  - Alternatively, click the first file then Shift+Click to select a contiguous range.

3) Right-click to open context menu
- Right-click on the selection area (on one of the selected files). The context menu should appear.
- Verify the menu contains "Commit" and "Stash" entries.

4) Commit context-menu behavior (dialog)
- Click "Commit" from the context menu.
- A Commit Dialog should open.
- Verify the commit dialog header and file list shows exactly the files you selected (and the count matches).
- If the dialog shows only a single file or different files than selected, this is a regression.
- Closing the dialog without committing is fine for this test.

5) Stash context-menu behavior (operation)
- Right-click to open context menu again with the same multi-selection.
- Click "Stash".
- The application will attempt to stash only the selected files. Observe the notification:
  - Successful stash should show "Stashed selected files." or similar message.
- To validate what was stashed:
  - Use the Git command line in the repo to inspect the stash diffs (git stash show -p).
  - Verify the stash contains changes *only* for the files selected in the UI.
- If the stash contains only a single default file or a file not in the selection, this is a regression.

6) Edge cases
- Right-click when nothing is selected (click in whitespace where fileTreePanel returns no selection):
  - The UI should show an informational notification: "No files selected." and no commit/stash should be performed.
- Select a single file and confirm Commit and Stash operate on that single file as expected.

Developer Notes / How the code defends against the regression
- The SessionChangesPanel sets a FileTreePanel.FileTreeContextMenuProvider that:
  - Returns null and shows a notification if selectedFiles is null or empty.
  - Captures a defensive copy of the selectedFiles and passes that collection directly to CommitDialog and to repo.createPartialStash(...) when Stash is invoked.
- The CommitDialog receives a Collection<ProjectFile> exactly as provided; CommitDialog's header and internal behavior surface the list to the user so mismatches are visible.

Converting to an automated test (future work)
- Automated tests should:
  - Instantiate a TestContextManager backed by TestRepo or TestGitRepo (app/src/test/java/ai/brokk/testutil/TestContextManager.java and TestRepo/TestGitRepo mentioned in project docs).
  - Create a headless or EDT-friendly Swing environment (use SwingUtilities.invokeAndWait / runOnEdt helpers).
  - Instantiate SessionChangesPanel with the TestContextManager and a lightweight fake Chrome or stub IConsoleIO.
  - Populate the TestRepo with multiple modified ProjectFile instances.
  - Simulate FileTreePanel selection (call FileTreePanel.setSelectionListener or invoke internal selection methods if available).
  - Trigger the context-menu actions by calling the provider created via fileTreePanel.getContextMenuProvider() (if accessible) or by invoking the action listeners that the provider installs.
  - Assert that:
    - CommitDialog is constructed with the exact selected files (could require making CommitDialog constructor package-private or allowing injection of a CommitDialog factory in SessionChangesPanel for testability).
    - TestRepo.createPartialStash(...) was invoked with the expected ProjectFile list.
- Avoid reflection by exposing small test hooks (factory injection or protected/package methods) if automated GUI tests are added.

Where to file follow-ups
- If you add automated Swing tests, please:
  - Use TestContextManager and TestRepo/TestGitRepo test utilities under app/src/test/java/ai/brokk.
  - Add tests under app/src/test/java/ai/brokk/gui, with @Disabled wrappers if flaky.
  - Ensure tests run on the EDT and follow project guidance from AGENTS.md (no reflection, Null Away compliance).

Reference
- Related source: app/src/main/java/ai/brokk/gui/SessionChangesPanel.java
- Issue: GitHub Issue #2587 — "Partial commit/stash selection regressed to single-file default"
