from typing import Dict, List


def get_braille_icon() -> str:
    """
    Returns a multi-line string representing the Brokk icon using Braille characters.
    Uses characters in the U+2800–U+28FF range.
    """
    # A stylized "B" or block icon
    return "⣿⣿⣿⣿⡇\n⣿⡇⢀⣸⡇\n⣿⣿⣿⣿⡇\n⣿⡇⢀⣸⡇\n⣿⣿⣿⣿⡇"


def build_welcome_message(commands: List[Dict[str, str]]) -> str:
    """
    Constructs the branded welcome/onboarding message as a Markdown string.

    Args:
        commands: The list of supported slash commands from BrokkApp.get_slash_commands().

    Returns:
        A Markdown-formatted string for display in the ChatPanel.
    """
    # Wrap icon in a code block to ensure monospace alignment in Markdown
    icon = f"```\n{get_braille_icon()}\n```"

    description = (
        "# Welcome to Brokk\n\n"
        "Brokk is a code intelligence agent designed for high-precision **context engineering**."
    )

    context_eng = (
        "### Context Engineering\n"
        "In this TUI, your workspace is managed via a living context. "
        "AI performance depends on exactly what code is visible to the model. "
        "You can prune, pin, or focus specific files and methods to optimize results."
    )

    workflows = (
        "### Key Workflows\n"
        "- **`/context`**: Toggle the Context Panel to manage what the AI sees. "
        "Use it to drop unnecessary fragments and keep your token usage efficient.\n"
        "- **`/task`**: Open the Task List to track your current objectives. "
        "The AI uses this list to stay aligned with your goals.\n"
        "- **`@mentions`**: Type `@` followed by a file or class name in the chat to "
        "immediately attach that entity to your context."
    )

    # Optional help hint
    has_help = any(c.get("command") == "/help" for c in commands)
    help_hint = (
        "\n\nType **`/help`** to see the full list of available commands." if has_help else ""
    )

    return f"{icon}\n\n{description}\n\n{context_eng}\n\n{workflows}{help_hint}"
