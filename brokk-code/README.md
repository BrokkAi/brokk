# Brokk Code

## What this project is for

This project is a Python (Textual) terminal UI client for Brokk that launches and manages a local Java executor subprocess. It authenticates using an HTTP bearer token to submit jobs and streams real-time events and tokens to power its interactive chat, context, and task panels.

## Getting Started

### Prerequisites

- Python 3.11+
- Java 21+ (for the Brokk executor)

The Brokk executor JAR will be **automatically downloaded** on first run to `~/.brokk/brokk.jar`.

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

### Running

**With uv:**

```bash
uv run brokk-code
```

**With pip installation:**

```bash
brokk-code
```

**Or run directly:**

```bash
python -m brokk_code
```

### Resuming Sessions

Brokk automatically saves your session state (fragments, history, etc.) when you exit.

**Resume the last session in the current workspace:**
```bash
brokk-code --resume
```

**Resume a specific session by ID:**
```bash
brokk-code resume <session_id>
```
*Note: When you exit `brokk-code`, it prints a convenient "resume hint" command for the session you just finished, for example:*
`brokk-code resume <session_id>`

### ACP Mode

Run the official ACP server mode over stdio:

```bash
uv run brokk-code acp
```

This mode is headless and intended for ACP-compatible clients. The default `brokk-code` command still launches the interactive TUI.

### Options

- `--workspace <path>`: Specify the workspace directory (defaults to current directory).
- `--resume`: Resume the last used session in the current workspace.
- `--session <id>`: Attempt to resume a specific session ID (similar to the `resume` command).
- `--executor-version <tag>`: Specify a version/tag of the executor to download (e.g., `v0.1.0`).
- `--executor-snapshot`: Download the latest snapshot release instead of the stable release (ignored if `--executor-version` is set).
- `--jar <path>`: Specify a custom path to `brokk.jar`. This **overrides** all version/download logic.

### Selecting an Executor Version

By default, `brokk-code` downloads the latest stable release to `~/.brokk/brokk.jar`. You can pin a specific version using the `--executor-version` flag:

```bash
uv run brokk-code --executor-version v0.1.0
```

Versioned JARs are cached at `~/.brokk/brokk-<tag>.jar`.

### Key Bindings

| Key | Action |
|-----|--------|
| `Ctrl+L` | Toggle context panel |
| `Ctrl+T` | Toggle task list panel |
| `Ctrl+N` | Toggle notifications panel |
| `Ctrl+D` | Exit immediately |
| `Ctrl+C` | Cancel job / quit |
| `Ctrl+G` | Toggle mode (LUTZ/ASK/CODE) |
| `F3` | Toggle mode (LUTZ/ASK/CODE) |
| `Ctrl+P` | Open settings |

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
| `/ask` | Set mode to ASK (questions only) |
| `/code` | Set mode to CODE (read-only mode) |
| `/lutz` | Set mode to LUTZ (full agent access) |
| `/model <name>` | Switch the LLM model |
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
