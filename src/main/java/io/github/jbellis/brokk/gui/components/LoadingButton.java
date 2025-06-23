package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import org.jetbrains.annotations.Nullable;

public final class LoadingButton extends JButton {

    private final Chrome chrome;
    private String idleText;
    private String idleTooltip;
    @Nullable
    private Icon idleIcon;

    public LoadingButton(String initialText,
                         @Nullable Icon initialIcon,
                         Chrome chrome,
                         @Nullable ActionListener actionListener)
    {
        super(initialText, initialIcon);
        if (initialText == null) throw new IllegalArgumentException("initialText cannot be null");
        if (chrome == null) throw new IllegalArgumentException("chrome cannot be null");

        this.idleText = initialText;
        this.idleIcon = initialIcon;
        // Capture tooltip that might have been set by super() or default, never null due to JButton behavior
        String tooltip = getToolTipText();
        this.idleTooltip = tooltip != null ? tooltip : "";
        this.chrome = chrome;

        if (actionListener != null) {
            addActionListener(actionListener);
        }
    }

    /**
     * Sets the loading state of the button. This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param loading   true to enter loading state, false to return to idle state.
     * @param busyText  The text to display when in the loading state. If null, the button's text area might appear empty or show the icon only.
     */
    public void setLoading(boolean loading, @Nullable String busyText) {
        assert SwingUtilities.isEventDispatchThread() : "LoadingButton.setLoading must be called on the EDT";

        if (loading) {
            // idleText, idleIcon, idleTooltip are already up-to-date via overridden setters
            // or from construction if no setters were called while enabled.

            var spinnerIcon = SpinnerIconUtil.getSpinner(chrome);
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
        }
    }

    @Override
    public void setText(String text) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        super.setText(text);
        if (isEnabled()) { // Only update idleText if not in loading state (isEnabled is false during loading)
            this.idleText = text;
        }
    }

    @Override
    public void setIcon(Icon icon) {
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
