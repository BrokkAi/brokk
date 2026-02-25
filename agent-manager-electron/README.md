# Brokk Agent Manager (Electron)

Electron + React prototype for managing conversation threads, sessions, and worktrees.

## What it does

- Uses TypeScript in both the Electron process and React renderer.
- Creates a new conversation thread from a seed prompt.
- Automatically derives:
  - a session name (`session-<prompt-summary>-<timestamp>`)
  - a worktree name (`wt-<prompt-summary>`)
- Gives every thread its own headless executor lifecycle.
- Stops idle executors after 5 minutes.
- Relaunches an executor automatically when a prompt arrives for a stopped thread.
- Keeps per-session output logs and replays them when switching threads.
- Uses a Material Design look and feel through MUI.

## Commands

```bash
npm install
npm run dev
npm run build
npm run typecheck
```

Run commands from `agent-manager-electron/`.
