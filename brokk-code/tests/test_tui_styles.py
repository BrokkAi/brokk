import importlib.resources
import re


def test_chat_input_border_style():
    """
    Regression test to ensure #chat-input does not use 'border: tall'.
    We prefer 'solid' or other lighter borders to keep the UI clean.
    """
    # Load the CSS content from the package resources
    # Python 3.11+ API
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    # Check #chat-input rule
    # We use a regex to find the block for #chat-input and ensure it doesn't contain 'border: tall'
    # This is a simple substring check within the block context.

    chat_input_match = re.search(r"#chat-input\s*\{([^}]*)\}", css_content)
    assert chat_input_match, "Could not find #chat-input rule in app.tcss"

    chat_input_body = chat_input_match.group(1)
    assert "border: tall" not in chat_input_body, (
        f"#chat-input should not use 'border: tall'. Found: {chat_input_body.strip()}"
    )

    # Check #chat-input:focus rule
    focus_match = re.search(r"#chat-input:focus\s*\{([^}]*)\}", css_content)
    assert focus_match, "Could not find #chat-input:focus rule in app.tcss"

    focus_body = focus_match.group(1)
    assert "border: tall" not in focus_body, (
        f"#chat-input:focus should not use 'border: tall'. Found: {focus_body.strip()}"
    )


def test_context_chip_no_clipping():
    """
    Regression test to ensure .context-chip has enough height for its rounded border.
    height: 1 with border: round causes text clipping in Textual.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    # Find the .context-chip rule block
    chip_match = re.search(r"\.context-chip\s*\{([^}]*)\}", css_content)
    assert chip_match, "Could not find .context-chip rule in app.tcss"

    chip_body = chip_match.group(1)

    # Check for the problematic combination
    has_height_1 = re.search(r"height:\s*1\s*;", chip_body)
    has_round_border = "border: round" in chip_body

    if has_round_border:
        assert not has_height_1, (
            ".context-chip uses 'border: round' which requires height > 1 to avoid clipping."
        )

    # Specifically assert our fix (height: 3)
    assert "height: 3" in chip_body, (
        f".context-chip should have height: 3. Found: {chip_body.strip()}"
    )
