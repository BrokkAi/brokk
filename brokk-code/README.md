# Brokk TUI

A Textual-based Terminal User Interface for interacting with the Brokk Headless Executor.

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
cd brokk-tui
uv sync
```

**Using pip:**

```bash
cd brokk-tui
pip install -e .
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
python -m brokk_tui
```

### Options

- `--workspace <path>`: Specify the workspace directory (defaults to current directory)
- `--jar <path>`: Specify a custom path to brokk.jar (default: auto-download to ~/.brokk/)

### Key Bindings

| Key | Action |
|-----|--------|
| `Ctrl+L` | Toggle context panel |
| `Ctrl+R` | Toggle task list panel |
| `Ctrl+C` | Cancel job / quit |

### Commands

| Command | Description |
|---------|-------------|
| `/model <name>` | Switch the LLM model |
| `/help` | Show available commands |
| `/quit` | Exit the application |
