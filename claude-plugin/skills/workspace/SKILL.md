---
name: brokk-workspace
description: >-
  Set or query the active workspace using Brokk's activateWorkspace and
  getActiveWorkspace tools. Required before code intelligence works on a
  new project or when switching repositories.
---

# Workspace

Use these Brokk MCP tools to set or check which project the server is
analyzing. The server will not return useful results for code intelligence
tools until a workspace is activated.

## Tools

| Tool | Purpose |
|---|---|
| `activateWorkspace` | Set the active workspace directory (absolute path; normalizes to git root) |
| `getActiveWorkspace` | Return the current workspace root path |

## Parameters

### activateWorkspace

| Parameter | Type | Required | Description |
|---|---|---|---|
| `workspacePath` | string | yes | Absolute path to the desired workspace directory |

### getActiveWorkspace

No parameters.

## Tips

- Always call `activateWorkspace` before using any other Brokk tools when
  starting work on a new project or switching repositories.
- The server automatically resolves the given path upward to the nearest
  `.git` root, so you can pass a subdirectory path.
- Use `getActiveWorkspace` to confirm which project root is currently active.
