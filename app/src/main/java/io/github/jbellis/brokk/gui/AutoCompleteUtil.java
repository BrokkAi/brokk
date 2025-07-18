package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

public class AutoCompleteUtil {
    private static final Logger logger = LogManager.getLogger(AutoCompleteUtil.class);

    private static final int MAX_POPUP_WIDTH = 800;
    private static final int MAX_POPUP_HEIGHT = 400;
    private static final int MAX_VISIBLE_ROWS = 15; // Limit rows shown without scrolling
    private static final int HORIZONTAL_PADDING = 40;
    private static final int VERTICAL_PADDING = 20;
    private static final int ROW_PADDING = 2;
    private static final double DESC_WIDTH_FACTOR = 1.2; // Hack for monospaced desc font

    /**
     * Binds Ctrl+Enter to accept the current autocomplete suggestion if the popup is visible;
     * if the autocomplete popup is not open, it submits the dialog by triggering its default button.
     *
     * @param autoCompletion The AutoCompletion instance managing the autocomplete popup.
     * @param textComponent The text component that the autocomplete is attached to.
     */
    public static void bindCtrlEnter(AutoCompletion autoCompletion, JTextComponent textComponent)
    {
        KeyStroke ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);

        textComponent.getInputMap(JComponent.WHEN_FOCUSED)
                .put(ctrlEnter, "ctrlEnterAction");

        textComponent.getActionMap()
                .put("ctrlEnterAction", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (autoCompletion.isPopupVisible()) {
                            // If the autocomplete popup is open, simulate the Enter key press
                            // by invoking the action ACPW temporarily bound to it.
                            var enterAction = textComponent.getActionMap().get("Enter");
                            if (enterAction != null) {
                                enterAction.actionPerformed(new ActionEvent(
                                        textComponent, // Source
                                        ActionEvent.ACTION_PERFORMED, // ID
                                        null, // Command string (can be null)
                                        e.getWhen(), // Use original event timestamp
                                        e.getModifiers() // Use original event modifiers
                                ));
                            } else {
                                logger.error("Enter action not found");
                            }
                        }
                        else {
                            // Otherwise, "click" the default button on the current root pane
                            var rootPane = SwingUtilities.getRootPane(textComponent);
                            if (rootPane != null) {
                                var defaultButton = rootPane.getDefaultButton();
                                if (defaultButton != null) {
                                    defaultButton.doClick();
                                }
                            }
                        }
                    }
                });
    }

    /**
     * Dynamically adjusts the size of the AutoCompletion popup windows (choices and description)
     * based on the content of the completions.
     *
     * @param autoCompletion The AutoCompletion instance whose popups need resizing.
     * @param textComponent The text component the AutoCompletion is attached to (used for font metrics).
     * @param completions   The list of completions to display.
     */
    public static void sizePopupWindows(AutoCompletion autoCompletion, JTextComponent textComponent, List<ShorthandCompletion> completions) {
        // Nothing works, autocomplete is just broken.  stick with the defaults
        if (true) {
            autoCompletion.setShowDescWindow(false);
            return;
        }

        var defaultFontMetrics = textComponent.getFontMetrics(textComponent.getFont());
        int rowHeight = defaultFontMetrics.getHeight() + ROW_PADDING;
        int visibleRowCount = Math.min(completions.size(), MAX_VISIBLE_ROWS);
        int calculatedHeight = visibleRowCount * rowHeight + VERTICAL_PADDING;
        int popupHeight = Math.min(MAX_POPUP_HEIGHT, calculatedHeight);

        // Calculate Choices window width
        int maxInputWidth = completions.stream()
                .mapToInt(c -> defaultFontMetrics.stringWidth(c.getInputText() + " - " + c.getShortDescription()))
                .max().orElse(200); // Default width if stream is empty (shouldn't happen here)
        int choicesWidth = Math.min(MAX_POPUP_WIDTH, maxInputWidth + HORIZONTAL_PADDING);
        autoCompletion.setChoicesWindowSize(choicesWidth, popupHeight);

        // Calculate Description window width and show it
        var ttFontMetrics = textComponent.getFontMetrics(UIManager.getFont("ToolTip.font"));
        boolean hasDescriptions = completions.stream().anyMatch(c -> {
            String desc = getCompletionDescription(c);
            return desc != null && !desc.isEmpty();
        });
        // disabled for https://github.com/bobbylight/AutoComplete/issues/97
        if (hasDescriptions) {
            int maxDescWidth = completions.stream()
                    .mapToInt(c -> {
                        String desc = getCompletionDescription(c);
                        return desc != null ? ttFontMetrics.stringWidth(desc) : 0;
                    })
                    .max().orElse(300); // Default width
            // Apply hack factor for potentially monospaced font in description
            int descWidth = Math.min(MAX_POPUP_WIDTH, (int) (DESC_WIDTH_FACTOR * maxDescWidth) + HORIZONTAL_PADDING);

            autoCompletion.setShowDescWindow(true);
            autoCompletion.setDescriptionWindowSize(descWidth, popupHeight);
        } else {
            autoCompletion.setShowDescWindow(false);
        }
    }

    /**
     * Helper to get the description text, handling ShorthandCompletion.
     */
    private static @Nullable String getCompletionDescription(Completion c) {
        if (c instanceof ShorthandCompletion sc) {
            // ShorthandCompletion often uses replacement text as the primary description
            return sc.getReplacementText();
        }
        // Fallback to tooltip text if available, otherwise null
        return c.getToolTipText();
    }
}
