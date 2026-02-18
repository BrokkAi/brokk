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


def test_context_panel_height_regression():
    """
    Ensure context panel styles support full-screen modal layout.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    modal_match = re.search(r"#context-modal-container\s*\{([^}]*)\}", css_content)
    assert modal_match, "Could not find #context-modal-container rule in app.tcss"
    modal_body = modal_match.group(1)
    assert "width: 100%" in modal_body, "#context-modal-container should use full width"
    assert "height: 100%" in modal_body, "#context-modal-container should use full height"

    panel_match = re.search(r"#context-panel\s*\{([^}]*)\}", css_content)
    assert panel_match, "Could not find #context-panel rule in app.tcss"

    panel_body = panel_match.group(1)

    assert "height: 1fr" in panel_body, "#context-panel should fill modal height"
    assert "max-height: 100%" in panel_body, "#context-panel should expand to modal height"
    assert "width: 100%" in panel_body, "#context-panel should fill modal width"

    scroll_match = re.search(r"#context-chip-scroll\s*\{([^}]*)\}", css_content)
    assert scroll_match, "Could not find #context-chip-scroll rule in app.tcss"
    scroll_body = scroll_match.group(1)
    assert "height: 1fr" in scroll_body, "#context-chip-scroll should use 1fr to fill panel space"


def test_combined_selector_modal_dimensions():
    """
    Regression test for the combined model and reasoning selector modal sizing.
    It should be large enough to display both lists side-by-side comfortably.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    combined_match = re.search(r"#model-reasoning-combined-container\s*\{([^}]*)\}", css_content)
    assert combined_match, "Could not find #model-reasoning-combined-container rule in app.tcss"

    body = combined_match.group(1)
    assert "width: 100;" in body, "Combined modal should have a width of 100 for side-by-side lists"
    assert "max-height: 90%;" in body, "Combined modal should allow up to 90% screen height"

    # Verify list wrapper constraints within the modal
    m = r"#model-select-list-wrap,\s*#reasoning-select-list-wrap\s*\{([^}]*)\}"
    wrapper_match = re.search(m, css_content)
    assert wrapper_match, "Could not find selector list wrapper rule in app.tcss"
    wrapper_body = wrapper_match.group(1)
    assert "max-height: 30;" in wrapper_body, "Selector list wrappers should have max-height: 30"
