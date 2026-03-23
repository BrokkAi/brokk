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

### Login and Logout (Brokk API Key)

Interactive login (masked input):

```bash
uv run brokk login
```

Piped login:

```bash
echo "your-brokk-api-key" | uv run brokk login --stdin
```

Remove your saved Brokk API key:

```bash
uv run brokk logout
```

When you run `brokk login` interactively, Brokk prints these setup steps:

1. Go to <https://brokk.ai/>
2. Click **Try Brokk Now**
3. Login with GitHub or Google
4. Copy your API key
5. Paste it into the terminal

Interactive key input is masked as `*` characters (one star per character).

After login, Brokk immediately validates the key and reports account state (paid/free/unknown) and balance when available.

Clipboard piping examples:

```bash
# macOS
pbpaste | uv run brokk login --stdin

# Windows PowerShell
Get-Clipboard | uv run brokk login --stdin

# Linux (xclip)
xclip -o -selection clipboard | uv run brokk login --stdin
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

### Editor Integration Installers

You can generate integration settings for supported clients:

```bash
uv run brokk install zed
uv run brokk install intellij
uv run brokk install neovim --plugin codecompanion
uv run brokk install neovim --plugin avante
```

If you run `brokk install neovim` without `--plugin`, Brokk shows a menu in interactive terminals.

#### Neovim + CodeCompanion

`brokk install neovim --plugin codecompanion` creates a Brokk ACP adapter module at:

`~/.config/nvim/lua/brokk/brokk_codecompanion.lua`

What is CodeCompanion?
- `codecompanion.nvim` is a Neovim plugin for AI chat, code assistance, and agent workflows.
- It can connect to ACP-compatible agent servers.
- Brokk provides one of those agent servers via `brokk acp`.

What this installer does:
- Writes a small adapter module that tells CodeCompanion to start and talk to Brokk over ACP.
- Keeps this Brokk-specific config in a clearly named module (`brokk.brokk_codecompanion`).
- Attempts a conservative auto-wire of `~/.config/nvim/init.lua` when it can safely patch a simple `opts = {}` plugin spec.
- Does **not** install Neovim plugins by itself.

1. Install CodeCompanion:
   - GitHub: <https://github.com/olimorris/codecompanion.nvim>
   - Docs: <https://codecompanion.olimorris.dev/>
2. Ensure Brokk is available on your shell `PATH` as `brokk` (or edit the generated file command).
3. Wire the generated Brokk module into your CodeCompanion plugin setup:

```lua
{
  "olimorris/codecompanion.nvim",
  opts = function()
    return require("brokk.brokk_codecompanion")
  end,
}
```

The generated module sets `interactions.chat.adapter = "brokk"` so Brokk becomes the default for CodeCompanion chat.

If you see `Copilot Adapter: No token found`, CodeCompanion is still using its default adapter and the Brokk module is not loaded yet.

#### Neovim + Avante

`brokk install neovim --plugin avante` creates a Brokk Avante provider module at:

`~/.config/nvim/lua/brokk/brokk_avante.lua`

What is Avante?
- `avante.nvim` is a Neovim AI assistant plugin that supports ACP providers.
- Brokk can be configured as an ACP provider via `brokk acp`.
- Installer behavior is the same: write Brokk module first, then only auto-patch `init.lua` when safe.

1. Install Avante:
   - GitHub: <https://github.com/yetone/avante.nvim>
2. Ensure Brokk is available on your shell `PATH` as `brokk` (or edit the generated file command).
3. Load the generated Brokk provider in your Avante config:

```lua
local brokk = require("brokk.brokk_avante")

require("avante").setup(vim.tbl_deep_extend("force", brokk, {
  -- your existing Avante options
}))
```

### Options

- `--workspace <path>`: Specify the workspace directory (defaults to current directory).
- `--resume`: Resume the last used session in the current workspace.
- `--session <id>`: Attempt to resume a specific session ID (similar to the `resume` command).
- `--executor-version <tag>`: Specify a version/tag of the executor to download (e.g., `v0.1.0`).
- `--executor-snapshot`: Download the latest snapshot release instead of the stable release (ignored if `--executor-version` is set).
- `--jar <path>`: Specify a custom path to `brokk.jar`. This **overrides** all version/download logic.

### Selecting an Executor Version

By default, `brokk` downloads the latest stable release to `~/.brokk/brokk.jar`. You can pin a specific version using the `--executor-version` flag:

```bash
uv run brokk --executor-version v0.1.0
```

Versioned JARs are cached at `~/.brokk/brokk-<tag>.jar`.

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
`brokk` is a Terminal UI built with the **Textual** framework. It uses Textual's built-in theme system and CSS (`app.tcss`).
- **Does NOT use** Java/FlatLaf `*.theme.json` files found in the Java executor resources.
- **Available Themes**: Supports all built-in Textual themes (like `textual-dark`, `textual-light`).
- **Customization**: UI colors are defined via TCSS variables in `brokk_code/styles/app.tcss`.

### Persistence & Interaction
- **Settings**: The current theme is persisted in `~/.brokk/settings.json` under the `theme` key.
- **Settings Picker**: Use `Ctrl+P` then select `Change theme` to open settings (including theme options like solarized).
- **Command**: You can use `/settings` to open the same picker.

### Issue Management (GitHub)

The Python CLI supports creating GitHub issues based on repository evidence via the `issue create` command. This is a read-only operation for the local repository; it uses the GitHub API to post a new issue.

```bash
# Example: Create an issue for a discovered bug
brokk issue create "Describe the NPE in AuthService" \
  --repo-owner acme-corp \
  --repo-name service-api \
  --github-token ghp_yourToken
```

**Required Arguments:**
- `prompt`: A description of the problem or evidence to report.
- `--repo-owner` / `--repo-name`: Target GitHub repository.
- `--github-token`: GitHub PAT (can also be set via `GITHUB_TOKEN` environment variable).

### Commands

| Command | Description |
|---------|-------------|
| `/model <name>` | Switch the LLM model |
| `/task` | Open/close the task list |
| `/help` | Show available commands |
| `/quit` | Exit the application |

## For Contributors & LLMs

To avoid common mistakes when working on this subproject:

- **Context**: `brokk` is the **Python TUI client**. It launches and manages the **Java executor** as a subprocess.
- **Do**: Run all Python-related commands (pytest, ruff, uv) from within the `brokk-code/` directory.
- **Don't**: Assume `./gradlew` builds the Python client; it builds the Java executor/app.
- **Executor JAR**: The client automatically downloads/caches the executor to `~/.brokk/brokk.jar` (or `brokk-<tag>.jar`).
- **Guidelines**: 
    - See [AGENTS.md](AGENTS.md) for general Python contribution rules.
    - See [brokk_code/AGENTS.md](brokk_code/AGENTS.md) for package-specific details.
