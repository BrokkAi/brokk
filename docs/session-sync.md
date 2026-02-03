Session synchronization (developer notes)
========================================

Summary
-------
This document describes the behavior of the SessionSynchronizer when it encounters backend timeouts or other IO failures while listing or transferring remote session data. It documents the retry behavior, logging, and where to look for related diagnostics. This change addresses flakey exception reporting when the remote service occasionally times out (see Issue #2570).

What changed
------------
- Listing remote sessions (the initial call that discovers remote session metadata) now uses a bounded retry policy for transient timeouts:
  - On SocketTimeoutException the synchronizer will retry the list operation once (initial attempt + at most 1 retry).
  - Between attempts a small backoff is applied (a few hundred milliseconds) so brief network blips may recover.
  - If both attempts time out, the synchronizer aborts the current sync cycle gracefully: no per-session operations (download/upload/delete) are attempted, and the method returns without throwing.
- Non-timeout IO failures (other IOExceptions) during the listing still abort the sync cycle and are logged as before.
- Other per-session operations (download, upload, merge, delete) keep their existing failure handling. Notably:
  - HTTP 413 (Payload Too Large) on upload is recorded in the local sync-info file so oversized sessions are skipped in future sync cycles.
  - Rate-limit errors (e.g., HTTP 429) will pause remaining uploads for the sync cycle and are logged.
- The net effect is fewer spurious client exception reports from the listing step caused by intermittent SocketTimeoutException while preserving visibility and handling of other, actionable errors.

Why this change
----------------
Previously, transient SocketTimeoutException during the remote session listing could surface as errors and lead to noisy exception reports. Listing is an inexpensive discovery operation; transient network blips are expected and should not escalate to client exceptions or trigger partial sync activity. The new behavior reduces noise while still surfacing persistent or non-transient failures.

Logging and diagnostics
-----------------------
- The synchronizer logs messages using the class-level logger for ai.brokk.SessionSynchronizer. Look for messages at WARN/INFO/DEBUG in application logs.
  - Timeout attempts are logged at WARN with the attempt number and cause.
  - If the sync cycle is aborted after repeated timeouts, a WARN is logged indicating the sync was aborted due to repeated timeouts.
  - Non-timeout IO failures when listing are logged at WARN with the exception type and message.
  - Per-session failures are logged (including payload-too-large and rate-limit conditions) and handled according to the existing policy.
- The sync-info file (sessions/.brokk/sessions/sync/sync_info.json) is updated when a session is determined to be oversized (HTTP 413). This prevents repeated futile upload attempts.
- For devs running tests: tests for sync behavior include unit tests that simulate SocketTimeoutException during listing and confirm the retry/abort behavior.

User-facing behavior
--------------------
- There is no additional user-visible notification for a transient timeout during listing. The sync simply aborts the current cycle to avoid noisy errors; it will resume on the next scheduled/manual sync.
- If a session is found to be too large to upload (HTTP 413), it will be added to the local "oversized" list; subsequent sync cycles skip uploading that session and a debug/log message is emitted.
- If you see repeated sync failures in logs (not transient timeouts), investigate connectivity to the backend or check for non-timeout IOExceptions reported by the synchronizer.

Where to look in code
---------------------
- Session listing and sync loop: ai.brokk.SessionSynchronizer#synchronize
- Retry/backoff behavior: implemented around the call to SyncCallbacks.listRemoteSessions in the synchronize() method.
- Per-session upload behavior and oversized-session persistence: ai.brokk.SessionSynchronizer.SyncExecutor and SessionSynchronizer.addOversizedSession().
- Tests that exercise the behavior:
  - app/src/test/java/ai/brokk/SessionSynchronizerTest.java
  - app/src/test/java/ai/brokk/SessionSyncExecutorTest.java

Reference
---------
- GitHub Issue #2570 — "Reduce spurious client exception reports from session sync when listing remote sessions times out."
