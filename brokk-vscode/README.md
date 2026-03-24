# Brokk for VS Code

AI-powered code intelligence and context management, embedded as a VS Code sidebar panel.

[Brokk](https://brokk.ai) understands your entire codebase — classes, methods, dependencies, and call graphs — so you can have precise, context-aware conversations with an LLM about your code.

## Features

- **Smart context management** — Add files, classes, and methods to your conversation context with `@` autocomplete. Brokk resolves symbols using full code intelligence, not just text search.
- **Codebase-aware AI chat** — Ask questions, request refactors, or explore unfamiliar code with an LLM that understands your project's structure.
- **Edit and apply** — Brokk can propose edits across multiple files. Review diffs and apply changes directly from the sidebar.
- **Activity history** — Undo and redo context and code changes with a full activity log.

## Getting Started

1. Install the extension from the VS Code Marketplace
2. Open a project in VS Code
3. Open the Brokk panel in the secondary sidebar (look for the Brokk icon, or run **View > Secondary Side Bar**)
4. Brokk will automatically start analyzing your project

## Requirements

- **VS Code** 1.106.0+
- **Java** 21+

## Configuration

| Setting | Description |
|---------|-------------|
| `brokk.launchMode` | How to start the Brokk executor (`auto`, `jbang`, `local`, `external`) |
| `brokk.executorPort` | Port of an already-running executor (`0` = auto-spawn) |
| `brokk.authToken` | Auth token for an already-running executor |

## Development & Publishing

### Prerequisites

- [Node.js](https://nodejs.org/) and [pnpm](https://pnpm.io/)
- `vsce` CLI: `npm install -g @vscode/vsce`
- A Personal Access Token (PAT) for the `brokk` publisher on the [VS Code Marketplace](https://marketplace.visualstudio.com/manage)

### Build

The extension uses esbuild to bundle all dependencies into a single `out/extension.js`. No `node_modules` are needed at runtime.

```bash
pnpm install    # install dependencies
npm run compile # bundle with esbuild
```

### Package

```bash
npx vsce package --no-dependencies --allow-missing-repository
```

This creates a `.vsix` file you can install locally for testing via **Extensions > Install from VSIX**.

The `--no-dependencies` flag skips `npm list` validation, which fails because the project uses pnpm. This is safe because esbuild already bundles everything.

### Publish

```bash
npx vsce publish --no-dependencies --allow-missing-repository
```

This packages and uploads the extension to the VS Code Marketplace in one step. Make sure to bump the `version` in `package.json` before publishing.

## Learn More

Visit [brokk.ai](https://brokk.ai) for documentation and guides.
