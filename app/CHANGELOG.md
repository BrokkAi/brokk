# Changelog

All notable changes to this project are documented in this file.

## Unreleased

- Session sync: Improve robustness when listing remote sessions (Issue #2570).
  - Session synchronizer now treats transient SocketTimeoutException from the remote listing as a recoverable condition: it will retry the listing once with a short backoff and, if the retry also times out, abort the current sync cycle gracefully without throwing further exceptions or generating spurious client exception reports.
  - Non-timeout IO failures during listing (other types of IOException) continue to abort the sync cycle and are logged.
  - Large upload payloads (HTTP 413) are persisted in the local "sync info" so that oversized sessions are not repeatedly retried.
  - Where applicable, the sync logs warnings and info messages to aid debugging (see docs/session-sync.md for details).

See docs/session-sync.md for developer-facing behavior and guidance.

(Reference: GitHub Issue #2570)
