import importlib.resources
import re

import pytest


def test_chat_input_border_style():
    """
    Regression test to ensure #chat-input does not use 'border: tall'.
    We prefer 'solid' or other lighter borders to keep the UI clean.
    """
    # Load the CSS content from the package resources
    # Python 3.11+ API
    css_content = (
        importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()
    )

    # Check #chat-input rule
    # We use a regex to find the block for #chat-input and ensure it doesn't contain 'border: tall'
    # This is a simple substring check within the block context.
    
    chat_input_match = re.search(r"#chat-input\s*\{([^}]*)\}", css_content)
    assert chat_input_match, "Could not find #chat-input rule in app.tcss"
    
    chat_input_body = chat_input_match.group(1)
    assert "border: tall" not in chat_input_body, (
        "#chat-input should not use 'border: tall'. "
        f"Found: {chat_input_body.strip()}"
    )

    # Check #chat-input:focus rule
    focus_match = re.search(r"#chat-input:focus\s*\{([^}]*)\}", css_content)
    assert focus_match, "Could not find #chat-input:focus rule in app.tcss"
    
    focus_body = focus_match.group(1)
    assert "border: tall" not in focus_body, (
        "#chat-input:focus should not use 'border: tall'. "
        f"Found: {focus_body.strip()}"
    )
