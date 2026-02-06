# Brokk TUI

Brokk includes a full-screen terminal UI (TUI) mode for running the `code` workflow in a terminal.

## Usage

Run with a goal prompt:

```bash
./brokk tui --project . --goal "Describe the project architecture"
```

Add specific files first:

```bash
./brokk tui --project . --file app/src/main/java/ai/brokk/ContextManager.java --goal "Explain this class"
```

## Key Bindings

- `PgUp` / `PgDn`: scroll output
- `Home` / `End`: jump to top/bottom
- `Ctrl+L`: clear output
- `Ctrl+R`: toggle line wrapping
- `Ctrl+C`: request exit

## Notes

- The TUI is implemented using Lanterna and is powered by the same headless execution pipeline as the CLI.
- The UI renders streaming output routed through `IConsoleIO`.
