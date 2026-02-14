# Brokk for VS Code

Context management and AI chat for the [Brokk](https://brokk.ai) coding assistant, embedded as a VS Code sidebar panel.

## Prerequisites

- **VS Code** 1.106.0+
- **Node.js** 18+
- **Java** 21+ (for the headless executor)
- The Brokk repo cloned locally, with the shadow JAR built:
  ```
  ./gradlew :app:shadowJar
  ```
  This produces `app/build/libs/brokk-*.jar`.

## Quick Start

1. **Install dependencies & build:**
   ```bash
   cd vscode-brokk
   npm install
   npm run compile
   ```

2. **Launch the extension** in the VS Code Extension Development Host:
   - Open the `vscode-brokk/` folder (or the repo root) in VS Code
   - Press **F5** (uses the "Run Extension" launch config)
   - A new VS Code window opens with the Brokk extension active

3. **Open the Brokk panel** in the sidebar (look for the Brokk icon in the secondary sidebar or run `View > Secondary Side Bar`).

On activation, the extension auto-spawns the headless executor from the shadow JAR using a random port and auth token. The status bar item at the bottom shows the connection state.

## Configuration

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `brokk.executorPort` | number | `0` | Port of an already-running executor. `0` = auto-spawn. |
| `brokk.authToken` | string | `""` | Auth token for a remote executor. |

You can also set these via environment variables (useful for development):

```bash
export BROKK_EXECUTOR_PORT=8080
export BROKK_AUTH_TOKEN=my-token
```

When both are set, the extension connects to the existing executor instead of spawning one.

## Commands

| Command | Description |
|---------|-------------|
| `Brokk: Start Executor` | Connect to or spawn the headless executor |
| `Brokk: Stop Executor` | Kill the executor process |
| `Brokk: Add to Context` | Add file(s) to the Brokk context (also in Explorer right-click menu) |

## Development

### Build scripts

```bash
npm run compile   # One-shot build (extension + webview + worker)
npm run watch     # Continuous rebuild on file changes
npm run lint      # Type-check with tsc (no emit)
```

### Project structure

```
vscode-brokk/
├── src/
│   ├── extension.ts              # Extension entry point (activate/deactivate)
│   ├── types.ts                  # Shared type definitions
│   ├── executor/
│   │   ├── client.ts             # HTTP client for the headless executor API
│   │   ├── lifecycle.ts          # JAR discovery & executor process spawning
│   │   ├── EventStreamManager.ts # Job event polling
│   │   └── EventDispatcher.ts    # Event → state-hint translation
│   ├── providers/
│   │   ├── BrokkPanelProvider.ts  # Webview provider (core logic)
│   │   ├── panelHtml.ts           # HTML template for the webview
│   │   ├── settingsManager.ts     # brokk.properties I/O & balance API
│   │   └── DiffContentProvider.ts # Virtual document provider for diffs
│   ├── webview/
│   │   ├── panel.js               # Webview entry point (message router)
│   │   ├── chat.js                # Chat rendering, streaming, Shiki worker
│   │   ├── context.js             # Context chips, token bar, task list
│   │   ├── activity.js            # Activity panel & undo/redo
│   │   ├── settings.js            # Settings overlay
│   │   ├── custom-select.js       # Custom dropdown component
│   │   ├── util.js                # Shared utilities (escapeHtml, showMenuAt)
│   │   └── markdown-worker.ts     # Shiki syntax-highlighting web worker
│   └── markdown/                  # Markdown processing pipeline (remark/rehype/shiki)
├── media/                         # Built webview assets (panel.js, panel.css, worker)
├── out/                           # Built extension code (extension.js)
├── esbuild.mjs                    # Build config (3 bundles)
├── tsconfig.json
└── package.json
```

### Build pipeline

esbuild produces three bundles:

| Entry point | Output | Format | Platform |
|-------------|--------|--------|----------|
| `src/extension.ts` | `out/extension.js` | CJS | Node 18 |
| `src/webview/panel.js` | `media/panel.js` | IIFE | Browser (ES2020) |
| `src/webview/markdown-worker.ts` | `media/markdown-worker.js` | ESM | Browser (ES2020) |

### Debugging

The `.vscode/launch.json` includes two launch configs:

- **Run Extension** — builds first, then launches the Extension Development Host
- **Run Extension (Skip Build)** — skips the build (faster iteration when using `npm run watch`)

### Connecting to an external executor

For faster iteration, you can run the executor separately and point the extension at it:

```bash
# Terminal 1: start the executor
java -cp app/build/libs/brokk-*.jar ai.brokk.executor.HeadlessExecutorMain \
  --listen-addr 127.0.0.1:8080 \
  --auth-token dev-token \
  --workspace-dir /path/to/your/project \
  --exec-id $(uuidgen)

# Terminal 2: launch VS Code with env vars
BROKK_EXECUTOR_PORT=8080 BROKK_AUTH_TOKEN=dev-token code .
```

Or set `brokk.executorPort` and `brokk.authToken` in your VS Code settings.

## Architecture

The extension communicates with Brokk's headless executor over a local HTTP API. The executor manages the full Brokk backend (context, code intelligence, LLM orchestration) and exposes it via REST endpoints under `/v1/`.

```
┌─────────────────────┐         HTTP/REST          ┌──────────────────────┐
│   VS Code Extension │ ◄─────────────────────────► │  Headless Executor   │
│                     │    localhost:<port>          │  (Java process)      │
│  BrokkPanelProvider │    Bearer <token>           │                      │
│  EventStreamManager │                             │  ContextManager      │
│  BrokkClient        │                             │  LLM orchestration   │
└─────────────────────┘                             └──────────────────────┘
        │
        │ postMessage
        ▼
┌─────────────────────┐
│   Webview (panel)   │
│                     │
│  chat, context,     │
│  activity, settings │
└─────────────────────┘
```

Key endpoints: context CRUD, job submission/streaming, activity undo/redo, session management, and model listing. Job events (LLM tokens, notifications, command results) are polled via the EventStreamManager during execution.
