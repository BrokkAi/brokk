# Manual verification checklist (Tools menu changes)

- Start app in both EZ and Advanced modes: confirm Tools menu always contains the new items: `BlitzForge...`, `Issues`, `Terminal`, `Pull Requests`, `Log`, `Worktrees`, `Changes`, and `Open Output in New Window`.

- For each Tools item (Issues, Terminal, Pull Requests, Log, Worktrees, Changes):
  - Click the item: a modeless dialog should open with the expected title ("Issues", "Terminal", "Pull Requests", "Log", "Worktrees", "Changes") and appropriate UI content.
  - Re-click the same menu item while its dialog is open: the existing dialog must be focused and not duplicated.

- Terminal dialog specific:
  - Close the Terminal dialog and verify the PTY/process is stopped: there should be no lingering OS/PTY processes left running after the dialog is closed.
  - If possible, verify by checking OS process list or by observing that TerminalPanel.dispose() is invoked (no exceptions) and the terminal process is terminated.

- Open Output in New Window:
  - Click `Open Output in New Window` from the Tools menu and confirm it performs the same action as the existing button (i.e., a new output window appears with the streamed LLM/history output).

Notes for reviewer/developer:

- Stable keys used by MenuBar.showOrFocusDialog: "issues", "terminal", "pull_requests", "git_log", "worktrees", "changes". Titles are matched to the keys (Issues, Terminal, Pull Requests, Log, Worktrees, Changes).
- Dialog sizing: dialogs are sized to at least the content's preferred size with minimum fallbacks (600x300) and are centered relative to the Chrome main frame.
- Cleanup: callers creating dialog-backed panels that manage OS resources (TerminalPanel) should supply an `onClose` Runnable. MenuBar.showOrFocusDialog also attempts a best-effort cleanup for TerminalPanel when no explicit onClose was provided.

Priority: Mark the Terminal-process verification steps as HIGH PRIORITY for acceptance testing.

Implementation notes for Code Agent:
- Add the new file `PULL_REQUEST_CHECKLIST.md` with the exact content above.
- Do not modify any Java source files.
- Save the file in the workspace so it will be included in the next commit/PR.
