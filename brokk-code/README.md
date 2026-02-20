# Brokk Code

## What this project is for

This project is a Python (Textual) terminal UI client for Brokk that launches and manages a local Java executor subprocess. It authenticates using an HTTP bearer token to submit jobs and streams real-time events and tokens to power its interactive chat, context, and task panels.

## Getting Started

### Prerequisites

- Python 3.11+
- Java 21+ (fallback mode only, when not launching via jbang)
- jbang (auto-installed on first run when possible)

By default, Brokk bootstraps jbang and launches the headless executor through
`brokk-headless@brokkai/brokk-releases`.

For local development, you can build the JAR manually:
```bash
./gradlew :app:shadowJar
```

### Installation

**Using uv (recommended):**

```bash
cd brokk-code
uv sync
```

**Using pip:**

```bash
cd brokk-code
pip install -e .
```

**Using pipx (global command):**

```bash
pipx install .
```

### Running

**With uv:**

```bash
uv run brokk
```

**With pip installation:**

```bash
brokk
```

**Or run directly:**

```bash
python -m brokk_code
```

### Resuming Sessions

Brokk automatically saves your session state (fragments, history, etc.) when you exit.

**Resume the last session in the current workspace:**
```bash
brokk --resume
```

**Resume a specific session by ID:**
```bash
brokk resume <session_id>
```
*Note: When you exit `brokk`, it prints a convenient "resume hint" command for the session you just finished, for example:*
`brokk resume <session_id>`

### ACP Mode

Run the official ACP server mode over stdio:

```bash
uv run brokk acp
```

This mode is headless and intended for ACP-compatible clients. The default `brokk` command still launches the interactive TUI.

### Options

- `--workspace <path>`: Specify the workspace directory (defaults to current directory).
- `--resume`: Resume the last used session in the current workspace.
- `--session <id>`: Attempt to resume a specific session ID (similar to the `resume` command).
- `--jar <path>`: Specify a custom path to `brokk.jar`. This bypasses jbang launch and runs the local JAR directly.

### Key Bindings

| Key | Action |
|-----|--------|
| `Ctrl+L` | Toggle context panel |
| `Ctrl+N` | Toggle notifications panel |
| `Shift+Tab` | Toggle mode (CODE/ASK/LUTZ) |
| `Ctrl+D` | Exit immediately |
| `Ctrl+C` | Cancel job / quit |
| `Ctrl+P` | Open settings |

### Task List Key Bindings (when the task list is open)

| Key | Action |
|-----|--------|
| `Up/Down` | Move selection |
| `Enter` or `Space` | Toggle selected task done |
| `A` | Add task |
| `E` | Edit selected task title |
| `D` | Delete selected task |
| `Esc` | Close task list |

## Theming

### Textual vs Java Themes
`brokk-code` is a Terminal UI built with the **Textual** framework. It uses Textual's built-in theme system and CSS (`app.tcss`).
- **Does NOT use** Java/FlatLaf `*.theme.json` files found in the Java executor resources.
- **Available Themes**: Supports all built-in Textual themes (like `textual-dark`, `textual-light`).
- **Customization**: UI colors are defined via TCSS variables in `brokk_code/styles/app.tcss`.

### Persistence & Interaction
- **Settings**: The current theme is persisted in `~/.brokk/settings.json` under the `theme` key.
- **Settings Picker**: Use `Ctrl+P` then select `Change theme` to open settings (including theme options like solarized).
- **Command**: You can use `/settings` to open the same picker.

### Commands

| Command | Description |
|---------|-------------|
| `/code` | Set mode to CODE (direct implementation) |
| `/ask` | Set mode to ASK (questions only) |
| `/lutz` | Set mode to LUTZ (default; full agent access) |
| `/mode` | Cycle between CODE, ASK, and LUTZ modes |
| `/model <name>` | Switch the LLM model |
| `/task` | Open/close the task list |
| `/help` | Show available commands |
| `/quit` | Exit the application |

## For Contributors & LLMs

To avoid common mistakes when working on this subproject:

- **Context**: `brokk-code` is the **Python TUI client**. It launches and manages the **Java executor** as a subprocess.
- **Do**: Run all Python-related commands (pytest, ruff, uv) from within the `brokk-code/` directory.
- **Don't**: Assume `./gradlew` builds the Python client; it builds the Java executor/app.
- **Executor JAR**: The client automatically downloads/caches the executor to `~/.brokk/brokk.jar` (or `brokk-<tag>.jar`).
- **Guidelines**: 
    - See [AGENTS.md](AGENTS.md) for general Python contribution rules.
    - See [brokk_code/AGENTS.md](brokk_code/AGENTS.md) for package-specific details.
