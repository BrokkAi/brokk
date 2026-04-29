---
name: brokk-workspace
description: >-
  Set or query the active workspace using Brokk's activate_workspace and
  get_active_workspace tools. Required before code intelligence works on a
  new project or when switching repositories.
---

# Workspace

Use these Brokk MCP tools to set or check which project the server is
analyzing. The server will not return useful results for code intelligence
tools until a workspace is activated.

## Tools

| Tool | Purpose |
|---|---|
| `activate_workspace` | Set the active workspace directory (absolute path; normalizes to git root) |
| `get_active_workspace` | Return the current workspace root path |
| `refresh` | Re-scan the workspace and rebuild the analyzer snapshot |

## Parameters

### activate_workspace

| Parameter | Type | Required | Description |
|---|---|---|---|
| `workspace_path` | string | yes | Absolute path to the desired workspace directory |

### get_active_workspace

No parameters.

### refresh

No parameters.

## Tips

- Always call `activate_workspace` before using any other Brokk tools when
  starting work on a new project or switching repositories.
- The server automatically resolves the given path upward to the nearest
  `.git` root, so you can pass a subdirectory path; the path must be absolute.
- Use `get_active_workspace` to confirm which project root is currently active.
- Call `refresh` after the workspace files change on disk in ways the
  analyzer hasn't picked up yet (e.g., a large `git checkout` or external
  edits) so subsequent symbol lookups see the latest definitions.
