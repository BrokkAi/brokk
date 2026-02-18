import importlib.resources
import re


def test_chat_input_no_border():
    """
    Regression test to ensure #chat-input does not use borders.
    We prefer background-based styling for the prompt.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    chat_input_match = re.search(r"#chat-input\s*\{([^}]*)\}", css_content)
    assert chat_input_match, "Could not find #chat-input rule in app.tcss"
    chat_input_body = chat_input_match.group(1)

    assert "border: none;" in chat_input_body or "border: none !important;" in chat_input_body, (
        f"#chat-input should explicitly set border: none to override defaults. Found: {chat_input_body.strip()}"
    )
    assert "background:" in chat_input_body, "#chat-input should have a background."
    assert "padding:" in chat_input_body, "#chat-input should have padding for spacing."

    focus_match = re.search(r"#chat-input:focus\s*\{([^}]*)\}", css_content)
    assert focus_match, "Could not find #chat-input:focus rule in app.tcss"
    focus_body = focus_match.group(1)

    assert "border: none;" in focus_body or "border: none !important;" in focus_body, (
        f"#chat-input:focus should explicitly set border: none. Found: {focus_body.strip()}"
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

    # Verify centering rule exists
    centering_match = re.search(r"ModelReasoningSelectModal\s*\{([^}]*)\}", css_content)
    assert centering_match, "Could not find ModelReasoningSelectModal rule in app.tcss"
    centering_body = centering_match.group(1)
    assert "align: center middle;" in centering_body, (
        "ModelReasoningSelectModal should be centered via align: center middle;"
    )

    # Verify list wrapper constraints within the modal
    m = r"#model-select-list-wrap,\s*#reasoning-select-list-wrap\s*\{([^}]*)\}"
    wrapper_match = re.search(m, css_content)
    assert wrapper_match, "Could not find selector list wrapper rule in app.tcss"
    wrapper_body = wrapper_match.group(1)
    assert "max-height: 30;" in wrapper_body, "Selector list wrappers should have max-height: 30"


def test_status_progress_overflow_valid():
    """
    Regression test to ensure #status-progress does not use 'overflow: visible'.
    Textual does not support 'visible' for overflow; it must be auto, hidden, or scroll.
    Also guard against 'overflow_x: visible' / 'overflow_y: visible' and hyphenated variants.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    progress_match = re.search(r"#status-progress\s*\{([^}]*)\}", css_content)
    assert progress_match, "Could not find #status-progress rule in app.tcss"

    progress_body = progress_match.group(1)

    # Check for 'overflow', 'overflow-x', 'overflow_x', 'overflow-y', and 'overflow_y'
    # being set to 'visible' anywhere in the rule block.
    for prop in ["overflow", "overflow-x", "overflow_x", "overflow-y", "overflow_y"]:
        pattern = rf"{prop}\s*:\s*visible"
        assert not re.search(pattern, progress_body), (
            f"#status-progress uses '{prop}: visible' which is invalid in Textual TCSS."
        )


def test_status_timer_width_regression():
    """
    Ensure #status-timer has sufficient width/min-width to avoid truncating
    the elapsed timer text (e.g., 'Elapsed: 00:00' which is ~14 chars).
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    timer_match = re.search(r"#status-timer\s*\{([^}]*)\}", css_content)
    assert timer_match, "Could not find #status-timer rule in app.tcss"
    timer_body = timer_match.group(1)

    # Check for width or min-width >= 14
    width_match = re.search(r"(?:min-)?width\s*:\s*(\d+)\s*;", timer_body)
    assert width_match, (
        f"#status-timer should have a numeric width or min-width. Found: {timer_body.strip()}"
    )

    width_val = int(width_match.group(1))
    assert width_val >= 14, (
        f"#status-timer width/min-width ({width_val}) is too small to prevent truncation. "
        "It should be at least 14 for 'Elapsed: 00:00'."
    )
