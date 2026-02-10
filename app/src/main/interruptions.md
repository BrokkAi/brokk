## Cancellation Patterns in Brokk

Brokk relies on standard Java thread interruption for cancellation. Blocking calls should throw `InterruptedException`, loops should honor `Thread.interrupted()` checks, and code should either propagate the interrupt or convert it into a structured stop result where appropriate.

### Execution model (UserActionManager)

All user actions that affect Workspace and current file state run through a single thread in the UserActionManager (UAM). There are two kinds of actions:

- Cancelable LLM actions
  - Submitted via `ContextManager.submitLlmAction(...)` which delegates to UAM.
  - UAM tracks the worker thread (`cancelableThread`), disables action buttons, and temporarily calls `io.blockLlmOutput(true)` to keep UI output coherent during task execution.
  - On completion (success/cancel/error) UAM clears the interrupt status, re-enables UI, and calls `io.blockLlmOutput(false)`.

- Exclusive (non-cancelable) actions
  - Submitted via `ContextManager.submitExclusiveAction(...)`.
  - Intended for short, local UI mutations (e.g., undo/redo/session ops). These execute to completion and are not wired to the Stop button.
  - UAM still disables/re-enables UI and clears the thread interrupt flag in a `finally` block to avoid leaking interrupt state to the next task.

Logging and error reporting for these executors is centralized via `LoggingExecutorService`.

### Stop button wiring

- Chrome UI -> `ContextManager.interruptLlmAction()` -> `UserActionManager.cancelActiveAction()` -> `Thread.interrupt()` of the cancelable action thread.
- Only the active cancelable action is interrupted; exclusive actions are not.

### Best practices for "top level" agents

- Loop checks: In long-running loops, check `Thread.interrupted()` (e.g., the CodeAgent FSM loop).
- Catch `InterruptedException` from LLM requests or build/verify steps, re-interrupt the thread, and terminate promptly.
- Propagate LLM or IO errors as structured StopDetails.
- This is done by hand instead of allowing exceptions to propagate up (our normal practice) so that we can return important state
  to the caller that would be otherwise lost, like CodeAgent's set of changed files.

### Aggregating history with TaskScope

- Use `ContextManager.beginTask` to open a TaskScope, then append one or more `TaskResult`s. Close (or try-with-resources) to commit exactly one history entry:
  - Changed files are made editable before the history entry is pushed.
  - If canceled before any AI output, nothing is pushed to the history stack.  

### Background tasks

- Submit non-user work with `ContextManager.submitBackgroundTask(...)`.
- Background tasks are not targeted by the Stop button and can ignore InterruptedException (won't happen, it's just noise in the code).
- Prefer `getAnalyzerUninterrupted()` in background code; reserve `getAnalyzer()` (interruptible) for cancelable user actions.

### EDT and UI

- Keep Swing UI updates on the EDT. Many ContextManager callbacks use `SwingUtilities.invokeLater` (e.g., context listener notifications).
- UAM automatically disables and re-enables action buttons around user actions; do not duplicate that logic in clients.

### Quick checklist

1. In cancelable actions, check `Thread.interrupted()` in loops and let blocking calls throw `InterruptedException`.
2. If catching `InterruptedException`, call `Thread.currentThread().interrupt()` unless you immediately exit.
3. For CodeAgent-like flows, prefer returning `StopDetails(INTERRUPTED)` over throwing, so partial state can be recorded.
4. Use `submitLlmAction` for interruptible LLM work and `submitExclusiveAction` for short, non-interruptible UI tasks.
5. Do not leak interrupt state across tasks; UAM already clears it in `finally`.
6. Use TaskScope to aggregate and commit history once.
