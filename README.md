## Interactive TUI (Text UI)

Run Brokk in an interactive terminal UI using:

```bash
brokk-cli --project /path/to/repo --tui
```

What you get:
- An interactive loop where any non-slash input runs the Lutz workflow (SearchAgent.Objective.LUTZ) and streams output to your terminal.
- A header that shows your remaining account balance and a token-usage summary when available. A spinner message appears while tasks run.
- Read-only views you can toggle on/off:
  - /chips — toggle a chip-style list of current context fragments
  - /tasks — show read-only Task List entries

Slash commands:
- /chips (/c) — toggle the context chip panel
- /tasks (/t) — toggle the read-only task list
- /quit (/q) — exit the TUI

Notes:
- In TUI mode, one-shot flags (e.g., --ask, --code, etc.) are ignored; you interactively drive prompts instead.
- The TUI uses your configured default models unless overridden by your project configuration.
