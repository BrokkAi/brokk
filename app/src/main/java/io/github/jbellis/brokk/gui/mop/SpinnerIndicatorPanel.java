package io.github.jbellis.brokk.gui.mop;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A simple panel displaying a spinner icon and a message.
 * Handles its own background color based on the theme.
 */
public class SpinnerIndicatorPanel extends JPanel {
    private final JLabel textLabel;

    /**
     * Creates a spinner panel.
     *
     * @param message         The text message to display next to the spinner.
     * @param isDarkTheme     Whether the dark theme is active.
     * @param backgroundColor The background color to apply (usually matching the parent's text area).
     */
    public SpinnerIndicatorPanel(String message, boolean isDarkTheme, @Nullable Color backgroundColor) {
        setLayout(new FlowLayout(FlowLayout.LEFT, 20, 10));
        setAlignmentX(LEFT_ALIGNMENT);
        var spinnerIcon = new ImageIcon(Objects.requireNonNull(getClass().getResource(
                "/icons/" + (isDarkTheme ? "spinner_dark.gif" : "spinner_white.gif"))));
        var spinnerLabel = new JLabel(spinnerIcon);
        spinnerLabel.setFont(spinnerLabel.getFont().deriveFont(Font.BOLD));

        textLabel = new JLabel(getDefaultMessageIfEmpty(message));

        add(spinnerLabel);
        add(textLabel);

        if (backgroundColor != null) {
            setBackground(backgroundColor);
        } else {
            // Fallback background if none provided
            setBackground(isDarkTheme ? new Color(40, 40, 40) : Color.WHITE);
        }
    }

    private String getDefaultMessageIfEmpty(String message) {
        return !message.isBlank() ? message : "Please wait...";
    }

    /**
     * Updates the message displayed next to the spinner.
     */
    public void setMessage(String message) {
        textLabel.setText(getDefaultMessageIfEmpty(message));
    }

    /**
     * Updates the background color of the panel.
     */
    public void updateBackgroundColor(@Nullable Color backgroundColor) {
        if (backgroundColor != null) {
            setBackground(backgroundColor);
        }
    }
}
