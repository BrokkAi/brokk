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

## Learn More

Visit [brokk.ai](https://brokk.ai) for documentation and guides.
