package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import java.awt.*;
import java.awt.event.ActionListener;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * A Material-themed loading button that shows a spinner while a background operation runs. Extends MaterialButton so it
 * matches the application's Material look-and-feel.
 */
public class MaterialLoadingButton extends MaterialButton {

    private final Chrome chrome;
    private String idleText;
    private String idleTooltip;

    @Nullable
    private Icon idleIcon;

    public MaterialLoadingButton(
            String initialText, @Nullable Icon initialIcon, Chrome chrome, @Nullable ActionListener actionListener) {
        super(initialText);

        this.chrome = chrome;
        this.idleText = initialText;
        this.idleIcon = initialIcon;
        if (initialIcon != null) {
            super.setIcon(initialIcon);
        }

        String tooltip = getToolTipText();
        this.idleTooltip = tooltip != null ? tooltip : "";

        if (actionListener != null) {
            addActionListener(actionListener);
        }

        // No direct call to applyStyling() here to avoid depending on a specific MaterialButton API.
        // Visual parity should be provided by extending MaterialButton; additional styling can be applied
        // by callers or by overriding methods if needed.
    }

    /**
     * Sets the loading state of the button. This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param loading true to enter loading state, false to return to idle state.
     * @param busyText The text to display when in the loading state. If null, the button's text area might appear empty
     *     or show the icon only.
     */
    public void setLoading(boolean loading, @Nullable String busyText) {
        assert SwingUtilities.isEventDispatchThread() : "MaterialLoadingButton.setLoading must be called on the EDT";

        if (loading) {
            var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, true);
            super.setIcon(spinnerIcon);
            super.setDisabledIcon(spinnerIcon); // Show spinner even when disabled
            super.setText(busyText);
            super.setToolTipText(busyText != null ? busyText + "..." : "Processing...");
            super.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            super.setEnabled(false); // Disable the button
        } else {
            super.setIcon(idleIcon);
            super.setDisabledIcon(null); // No special disabled icon for idle state unless idleIcon handles it
            super.setText(idleText);
            super.setToolTipText(idleTooltip);
            super.setCursor(Cursor.getDefaultCursor());
            super.setEnabled(true); // Re-enable the button

            // Restore themed primary styling after loading completes so callers don't need to reapply.
            io.github.jbellis.brokk.gui.SwingUtil.applyPrimaryButtonStyle(this);
        }
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        if (isEnabled()) { // Only update idleText if not in loading state (isEnabled is false during loading)
            this.idleText = text;
        }
    }

    @Override
    public void setIcon(@Nullable Icon icon) {
        super.setIcon(icon);
        if (isEnabled()) {
            this.idleIcon = icon;
        }
    }

    @Override
    public void setToolTipText(@Nullable String text) {
        super.setToolTipText(text);
        if (isEnabled()) {
            this.idleTooltip = text != null ? text : "";
        }
    }
}
