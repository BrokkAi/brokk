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
        "#chat-input should explicitly set border: none to override defaults. "
        f"Found: {chat_input_body.strip()}"
    )
    assert "background:" in chat_input_body, "#chat-input should have a background."
    assert "padding:" in chat_input_body, "#chat-input should have padding for spacing."
    assert "content-align: left middle;" in chat_input_body, (
        "#chat-input should have 'content-align: left middle;' for vertical centering."
    )

    focus_match = re.search(r"#chat-input:focus\s*\{([^}]*)\}", css_content)
    assert focus_match, "Could not find #chat-input:focus rule in app.tcss"
    focus_body = focus_match.group(1)

    assert "border: none;" in focus_body or "border: none !important;" in focus_body, (
        f"#chat-input:focus should explicitly set border: none. Found: {focus_body.strip()}"
    )
    assert "content-align: left middle;" in focus_body, (
        "#chat-input:focus should maintain 'content-align: left middle;'."
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


def test_help_elapsed_width_regression():
    """
    Ensure #help-elapsed has sufficient width/min-width to avoid truncating
    the elapsed timer text (e.g., 'Elapsed: 00:00' which is ~14 chars).
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    timer_match = re.search(r"#help-elapsed\s*\{([^}]*)\}", css_content)
    assert timer_match, "Could not find #help-elapsed rule in app.tcss"
    timer_body = timer_match.group(1)

    # Check for width or min-width >= 14
    width_match = re.search(r"(?:min-)?width\s*:\s*(\d+)\s*;", timer_body)
    assert width_match, (
        f"#help-elapsed should have a numeric width or min-width. Found: {timer_body.strip()}"
    )

    width_val = int(width_match.group(1))
    assert width_val >= 14, (
        f"#help-elapsed width/min-width ({width_val}) is too small to prevent truncation. "
        "It should be at least 14 for 'Elapsed: 00:00'."
    )


def test_help_labels_style_parity():
    """
    Ensure the chat help static has a transparent background, disabled text color,
    and appropriate dimensions to maintain style parity.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    selector = "#chat-help"
    # Match the selector and its block; keep consistent with other regex-based checks.
    pattern = rf"{re.escape(selector)}\s*\{{([^}}]*)\}}"
    match = re.search(pattern, css_content)
    assert match, f"Could not find {selector} rule in app.tcss"

    body = match.group(1)

    # Explicitly require transparent background; fail if changed to any non-transparent value.
    assert "background: transparent;" in body, (
        f"{selector} should have 'background: transparent;' to avoid filled label background. "
        f"Found: {body.strip()}"
    )

    # Assert color parity
    assert "color: $text-disabled;" in body, (
        f"{selector} should use '$text-disabled' color. Found: {body.strip()}"
    )

    # Assert height and padding for layout parity
    assert "height: 1;" in body, f"{selector} should have 'height: 1;'. Found: {body.strip()}"
    assert "padding: 0 1;" in body, f"{selector} should have 'padding: 0 1;'. Found: {body.strip()}"


def test_help_menu_layout_contract():
    """
    Regression test to ensure the help menu matches the chat input's horizontal layout
    and that legacy help labels are removed or hidden.
    """
    css_content = importlib.resources.files("brokk_code.styles").joinpath("app.tcss").read_text()

    # 1. Check #chat-input margins for reference
    input_match = re.search(r"#chat-input\s*\{([^}]*)\}", css_content)
    assert input_match, "Could not find #chat-input rule"
    input_body = input_match.group(1)
    # Extract horizontal margins (expecting '0 2 1 2' or similar where 2 is horizontal)
    input_margin_match = re.search(r"margin:\s*([^;]+);", input_body)
    assert input_margin_match, "#chat-input must have a margin defined"
    input_margin = input_margin_match.group(1).strip()

    # 2. Check #chat-help-row matches margins of #chat-input
    help_row_match = re.search(r"#chat-help-row\s*\{([^}]*)\}", css_content)
    assert help_row_match, "Could not find #chat-help-row rule in app.tcss"
    help_row_body = help_row_match.group(1)

    # Horizontal alignment check: Extract lateral margins from input (2nd and 4th components)
    input_margins = input_margin.split()
    help_row_margin_match = re.search(r"margin:\s*([^;]+);", help_row_body)
    assert help_row_margin_match, "#chat-help-row must have a margin defined"
    help_row_margins = help_row_margin_match.group(1).strip().split()

    same_margins = (
        input_margins[1] == help_row_margins[1] and input_margins[3] == help_row_margins[3]
    )
    assert same_margins, (
        f"#chat-help-row horizontal margins ({help_row_margins[1]}, {help_row_margins[3]}) "
        f"should match #chat-input ({input_margins[1]}, {input_margins[3]}) for alignment."
    )
    assert help_row_margins[2] == "0", "#chat-help-row should have 0 bottom margin."

    help_match = re.search(r"#chat-help\s*\{([^}]*)\}", css_content)
    assert help_match, "Could not find #chat-help rule in app.tcss"
    help_body = help_match.group(1)
    assert "width: 1fr;" in help_body, "#chat-help should use 1fr width"

    # 3. Verify right-alignment
    assert "content-align: right middle;" in help_body, (
        "#chat-help should use 'content-align: right middle;' for horizontal positioning."
    )
    assert "text-align: right;" in help_body, (
        "#chat-help should use 'text-align: right;' for the label content."
    )

    # 4. Ensure help spinner is styled correctly on the left
    spinner_match = re.search(r"#help-spinner\s*\{([^}]*)\}", css_content)
    assert spinner_match, "Could not find #help-spinner rule in app.tcss"
    spinner_body = spinner_match.group(1)
    assert "height: 1;" in spinner_body
    assert "margin-right: 1;" in spinner_body, (
        "Spinner should have right margin to separate from text"
    )

    # Negative regression: flex-shrink is invalid in TCSS and causes a crash.
    assert "flex-shrink" not in spinner_body, (
        "Textual TCSS does not support 'flex-shrink' property; using it causes a crash."
    )

    # 5. Ensure legacy help widgets are not active/visible
    # (If they were removed from the file entirely, these regexes should fail to find active rules)
    for legacy_id in ["#tasklist-help", "#context-help", "#status-spinner"]:
        match = re.search(rf"{legacy_id}\s*\{{([^}}]*)\}}", css_content)
        if match:
            body = match.group(1)
            assert "display: none;" in body, f"Legacy help {legacy_id} should be display: none;"
