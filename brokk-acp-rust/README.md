# brokk-acp-rust

`brokk-acp-rust` is a high-performance Agent Client Protocol (ACP) server implemented in Rust. It acts as an agentic bridge between ACP-compatible IDEs (like Zed) and Ollama or OpenAI-compatible LLM backends, providing Brokk's "Lutz Mode" agentic capabilities directly within the editor.

## Features

- **Standardized Protocol**: Full support for ACP over stdio, including session lifecycle management.
- **Agentic Tool Loop**: Implements a multi-turn autonomous loop with a configurable turn limit.
- **Rich Feedback**: Streams text responses and tool-call lifecycle notifications (Pending, InProgress, Completed/Failed) with inline diffs for file writes.
- **Permission Gating**: Configurable security policies (Default, Accept Edits, Read-only, Bypass) to control tool execution.
- **Code Intelligence**: Optional integration with Bifrost for symbol search, cross-references, and structural analysis.
- **Session Persistence**: Saves and resumes conversation history and session state from disk.

## Architecture

The server is composed of several specialized modules:

- **`agent`**: The entry point for the ACP protocol; handles JSON-RPC dispatching for sessions, configuration, and prompts.
- **`tool_loop`**: The core "Lutz" engine. Orchestrates LLM streaming, tool execution, and the permission gate.
- **`llm_client`**: Handles communication with OpenAI-compatible APIs, including tool-calling SSE stream parsing.
- **`session`**: Manages session state, conversation history persistence, and model/mode selection.
- **`tools`**: Implementation of built-in filesystem tools (read, write, list) and shell execution.
- **`bifrost_client`**: Manages the lifecycle of the Bifrost subprocess for advanced code analysis tools.

## Configuration / CLI Options

The server binary is named `brokk-acp`. It can be configured via CLI flags or environment variables:

| Flag | Env Var | Default | Description |
|------|---------|---------|-------------|
| `--endpoint-url` | - | `http://localhost:11434/v1` | Base URL for the OpenAI-compatible LLM API. |
| `--api-key` | `BROKK_ENDPOINT_API_KEY` | - | API key for the endpoint (optional for local LLMs). |
| `--default-model` | - | - | Model to use if not specified by the client. |
| `--max-turns` | - | `25` | Max tool-calling iterations per prompt before forcing a final response. |
| `--bifrost-binary`| `BROKK_BIFROST_BINARY` | - | Path to the `bifrost` executable to enable code-intel tools. |

## Running Locally

### Quickest path: build + wire into your editor in one step

The crate ships with two `cargo xtask` subcommands that build the release
binary **and** rewrite a `Brokk Code (Rust Local)` entry under `agent_servers`
in your editor's config. Run from `brokk-acp-rust/`:

```bash
# Wire into Zed   (~/.config/zed/settings.json)
cargo xtask build-acp-for-zed

# Wire into JetBrains   (~/.jetbrains/acp.json)
cargo xtask build-acp-for-jetbrains
```

Each task runs `cargo build --release --bin brokk-acp`, then writes a
single agent-server entry pointing at `target/release/brokk-acp`. Other
entries in the file are preserved verbatim. Re-run any time the binary
changes — the entry is rewritten in place. After running, restart the
editor's Brokk panel and pick `Brokk Code (Rust Local)` in the agent
server selector.

Both subcommands accept:
- `--config <path>` — override the editor config path (mostly for tests).
- `--bifrost-binary <name|path>` — value passed via `--bifrost-binary`
  in the entry's args. Defaults to the literal `bifrost` (assumed to be
  on the editor's `PATH`); pass an absolute path if Bifrost lives
  somewhere `PATH` does not reach.

### Manual / advanced

If you prefer to wire things up by hand (or just want to run the binary
standalone):

```bash
# Build the release binary
cargo build --release --bin brokk-acp

# Run against a local Ollama instance
./target/release/brokk-acp --default-model llama3.1
```

Then add the binary path to your editor's agent server config:
- Zed: `~/.config/zed/settings.json` under `agent_servers`, with
  `"type": "custom"` and `"command"` set to the absolute path.
- JetBrains: `~/.jetbrains/acp.json` under `agent_servers`, same shape
  minus the `type` field.

## Tool Calling and Permissions

The server supports a variety of tools, including `readFile`, `writeFile`, `listDirectory`, and `runShellCommand`. Execution is governed by a **Permission Mode** selectable in the client:

- **Default**: Prompts the user for approval before every mutating tool call.
- **Accept Edits**: Automatically allows file modifications but prompts for shell commands.
- **Read-only**: Strictly forbids any tool that modifies the filesystem or executes commands.
- **Bypass Permissions**: Trust the agent to execute all tools without interruption.

## Bifrost Integration

When configured with `--bifrost-binary`, the server spawns a Bifrost subprocess to provide structural code intelligence. This enables advanced tools such as:
- `search_symbols`: Find definitions across the workspace.
- `get_symbol_sources`: Fetch source code for specific symbols.
- `most_relevant_files`: Identify related files using import analysis and git history.

## Current Status / Roadmap

The Rust ACP server is actively developed. Current known limitations and planned areas of improvement include:

- **Tool Call Persistence**: Intermediate tool calls/results are not yet persisted in the session history (Replay shows text only).
- **Binary Detection**: Improved handling for binary files and large file limits in search tools.
- **Terminal Integration**: Future plans include leveraging the ACP Terminal protocol for live-streaming shell output.
