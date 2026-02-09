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

### Options

- `--workspace <path>`: Specify the workspace directory (defaults to current directory).
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
| `Ctrl+R` | Toggle task list panel |
| `Ctrl+C` | Cancel job / quit |

### Commands

| Command | Description |
|---------|-------------|
| `/model <name>` | Switch the LLM model |
| `/help` | Show available commands |
| `/quit` | Exit the application |
