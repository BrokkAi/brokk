# Brokk Code Agent Guide

## What this project is for

This project is a Python (Textual) terminal UI client for Brokk that launches and manages a local Java executor subprocess. It authenticates via an HTTP bearer token to submit jobs and stream real-time events or tokens, presenting an interactive workspace through dedicated chat, context, and task panels.

## Environment & Requirements

- **Python Version**: 3.11 or higher is required.
- **Key Dependencies**:
  - `textual`: For building the TUI.
  - `httpx`: For asynchronous communication with the executor.

## Communication Architecture

This project acts as a client that communicates with the Java-based Brokk executor via an HTTP API.
- The TUI spawns the Java executor as a subprocess.
- It authenticates using a bearer token generated at startup.
- It streams job events and updates the UI based on state hints from the executor.
- For ACP mode startup, create an executor session before calling `wait_ready()`. The readiness check can fail indefinitely without an active session.
- In ACP mode, emit a read-only context snapshot after each prompt completes. Format it as compact chip-style rows (kind, short description, tokens, pin marker).

## Code Style & Standards

- **PEP 8**: Follow standard Python style guidelines.
- **Linting**: Use `ruff` for linting and formatting. 
- **Type Hints**: Use type hints for all function signatures and complex variables.
- **Naming**: Use `snake_case` for variables and functions, and `PascalCase` for classes.

## Testing

ALWAYS RUN TESTS WHEN MAKING CHANGES!

- **Framework**: Use `pytest` for all tests.
- **Command**: Run tests with `uv run pytest` so the project-managed environment is always used.
- **Location**: Place tests in the `tests/` directory.
- **Smoke Tests**: Maintain `test_smoke.py` to ensure basic app and executor manager instantiation works without starting the subprocess.

### Manual testing for `brokk mcp` (stdio proxy)

- Run from `brokk-code` so the CLI can resolve workspace-relative resources:
  - `echo '<jsonl-message>' | uv run brokk mcp --workspace /home/jonathan/Projects/brokk`
- The MCP transport is newline-delimited JSON-RPC on stdio (no `Content-Length` framing).
- Use `notifications/initialized` (not `initialized`) after `initialize`.
- `initialize` and `tools/list` return quickly and reliably.
- A valid `tools/call` example that succeeds:
  - `searchSymbols` with `{"patterns":["BrokkExternalMcpServer"],"reasoning":"target class match","includeTests":false}` returned symbol output.
- Invalid/missing args reproduces a handled failure:
  - `tools/call` with `{"name":"searchSymbols"}` returns `isError=true` with message:
    `Cannot invoke "java.util.Map.containsKey(Object)" because "argumentsMap" is null`.
- In shell piped probes, if stdin closes immediately after sending JSON messages, the transport can close before long tool execution. Holding stdin open briefly after writing requests improves reliability for longer calls.

## Project Structure

- `brokk_code/`: Main package directory. (See [brokk_code/AGENTS.md](brokk_code/AGENTS.md) for subtree rules).
- `app.py`: Main Textual Application class.
- `executor.py`: Logic for managing the Java executor lifecycle and API calls.
- `widgets/`: Custom Textual widgets (Chat, Context, TaskList).
- `styles/`: TCSS files for application styling.

## Utilities to use consistently

- format_token_count for displaying token counts anywhere in the UI
