# SlopScan Agent Guide

## Project Overview

SlopScan is a React TypeScript web application that provides a "Forensic Audit" dashboard for code quality analysis. It communicates with the **Brokk Headless Executor** (a Java backend) via HTTP/JSON API.

## Architecture Reference

### How the TUI Connects (Usage Example)

The existing Python TUI (`brokk-code/`) demonstrates the communication pattern you should follow:

**Key file**: `brokk-code/brokk_code/executor.py`

```python
# The TUI spawns the Java executor as a subprocess
self._process = await asyncio.create_subprocess_exec(
    *cmd,
    stdin=asyncio.subprocess.PIPE,
    stdout=asyncio.subprocess.PIPE,
    stderr=asyncio.subprocess.STDOUT,
)

# Waits for the ready sentinel on stdout
# "Executor listening on http://127.0.0.1:<port>"

# Then communicates via HTTP
self._http_client = httpx.AsyncClient(
    base_url=f"http://127.0.0.1:{port}",
    headers={"Authorization": f"Bearer {self.auth_token}"},
)
```

**For SlopScan**: You likely want to connect to an already-running executor service rather than spawning one. The API is identical either way.

### Key API Endpoints

All endpoints require `Authorization: Bearer <token>` header.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health/live` | GET | Liveness check (no auth required) |
| `/v1/jobs` | POST | Submit analysis jobs |
| `/v1/jobs/{id}` | GET | Get job status |
| `/v1/jobs/{id}/events` | GET | Poll for events (`?after={seq}&limit=100`) |
| `/v1/github/oauth/start` | POST | Start GitHub device flow |
| `/v1/github/oauth/status` | GET | Check GitHub auth status |
| `/v1/context` | GET | Get current context state |

### Event Streaming Pattern

The executor does NOT use WebSockets or SSE. Events are polled:

```typescript
// TypeScript example for SlopScan
async function* streamEvents(jobId: string, client: HttpClient) {
  let afterSeq = -1;
  const terminalStates = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
  
  while (true) {
    const resp = await client.get(`/v1/jobs/${jobId}/events?after=${afterSeq}&limit=100`);
    const { events, nextAfter } = resp.data;
    
    for (const event of events) {
      yield event;
    }
    afterSeq = nextAfter;
    
    // Check job status periodically
    const status = await client.get(`/v1/jobs/${jobId}`);
    if (terminalStates.has(status.data.state)) {
      break;
    }
    
    // Adaptive backoff when no events
    await sleep(events.length > 0 ? 50 : 500);
  }
}
```

### Event Types You'll Receive

From `docs/headless-executor-events.md`:

| Type | Purpose |
|------|---------|
| `LLM_TOKEN` | Streaming LLM output |
| `NOTIFICATION` | Progress messages; `level: "COST"` includes USD cost |
| `COMMAND_RESULT` | Build/test/lint results |
| `STATE_HINT` | UI state transitions |
| `TOOL_CALL` / `TOOL_OUTPUT` | Tool execution details |

### Job Modes for Code Analysis

Submit jobs with `POST /v1/jobs`:

```json
{
  "taskInput": "Analyze this repository for code quality issues",
  "plannerModel": "gpt-4o",
  "tags": { "mode": "SEARCH" },
  "autoCompress": true
}
```

**Available modes**:
- `SEARCH`: Read-only repository discovery (symbols, usages, files)
- `ASK`: Single answer from current context (optionally with `preScan: true`)
- `LUTZ`: Two-phase planning + execution

For SlopScan, you'll likely use `SEARCH` mode with custom prompts, or eventually add a new `SLOP_SCAN` mode.

### Existing Analysis Tools (Java Side)

The `SearchTools` class provides these capabilities you can leverage:

| Tool | Description |
|------|-------------|
| `searchSymbols` | Find class/method/field definitions by pattern |
| `getClassSkeletons` | Get API surface (fields + signatures, no bodies) |
| `getMethodSources` | Get full method implementations |
| `getFileSummaries` | Skeleton views of files |
| `getGitLog` | Commit history for ownership analysis |
| `scanUsages` | Find where symbols are used |

### New Analysis Tools Needed

To implement SlopScan's features, you'll need to extend the Java executor:

1. **CyclomaticComplexityTool**: Compute McCabe complexity from AST
2. **CommentSemanticsTool**: Classify comments (how vs. why) using LLM
3. **OwnershipHeatmapTool**: Correlate complexity with commit frequency

These would be added to `app/src/main/java/ai/brokk/tools/` and registered in `ToolRegistry`.

## Code Style & Standards

### TypeScript/React

- Use functional components with hooks
- Prefer `async/await` over `.then()` chains
- Use TypeScript strict mode
- Component files: PascalCase (e.g., `ForensicFeed.tsx`)
- Hook files: camelCase with `use` prefix (e.g., `useEventStream.ts`)
- Utility files: camelCase (e.g., `executor-client.ts`)

### ESLint & Prettier

This project uses:
- ESLint 9 flat config (`eslint.config.js`)
- Prettier for formatting (`.prettierrc`)
- TypeScript ESLint for type-aware linting

Run checks:
```bash
npm run lint        # ESLint
npm run format      # Prettier check
npm run format:fix  # Prettier fix
```

### Tailwind CSS

Use Tailwind utility classes for styling. Custom theme extensions go in `tailwind.config.js`.

## Testing

- **Unit tests**: Vitest
- **Component tests**: React Testing Library
- **E2E tests**: Consider Playwright if needed

Run tests:
```bash
npm test
npm run test:watch
```

## Development Workflow

1. Start the Brokk executor (or use an existing instance)
2. Run the dev server: `npm run dev`
3. The app will connect to the executor at the configured URL

## Environment Variables

Create a `.env.local` file:

```env
VITE_EXECUTOR_URL=http://localhost:8080
VITE_AUTH_TOKEN=your-dev-token
```

## Related Documentation

- `docs/headless-executor.md` - Full API documentation
- `docs/headless-executor-events.md` - Event type reference
- `brokk-code/brokk_code/executor.py` - Reference client implementation
- `app/src/main/java/ai/brokk/tools/SearchTools.java` - Available analysis tools
