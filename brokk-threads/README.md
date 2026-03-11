# brokk-threads

Initial Electron + React + TypeScript shell for thread-oriented Brokk UI.

This increment intentionally keeps startup lazy:
- initial hydration reads only local thread metadata JSON
- no executor startup/session creation/switch on initial load
- no worktree creation/provisioning on initial load
