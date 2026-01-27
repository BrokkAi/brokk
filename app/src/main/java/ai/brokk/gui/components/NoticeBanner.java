package ai.brokk.gui.components;

import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable notification panel for displaying staleness or status warnings.
 */
public class NoticeBanner extends JPanel implements ThemeAware {
    private final JLabel label;
    private boolean isWarning = false;

    public NoticeBanner() {
        super(new BorderLayout());
        setOpaque(true);
        setVisible(false);
        setBorder(new EmptyBorder(4, 8, 4, 8));

        label = new JLabel();
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        add(label, BorderLayout.CENTER);
    }

    public void setMessage(@Nullable String message) {
        setMessage(message, false);
    }

    public void setMessage(@Nullable String message, boolean warning) {
        this.isWarning = warning;
        if (message == null || message.isBlank()) {
            setVisible(false);
        } else {
            label.setText(message);
            // Component might not have a theme applied yet if it's just been created.
            // But applyTheme will be called by the parent hierarchy.
            setVisible(true);
        }
    }

    private void updateColors(GuiTheme theme) {
        boolean isDark = theme.isDarkTheme();
        if (isWarning) {
            setBackground(ThemeColors.getColor(isDark, "warning_bg"));
            label.setForeground(ThemeColors.getColor(isDark, "warning_fg"));
        } else {
            setBackground(ThemeColors.getColor(isDark, "info_bg"));
            label.setForeground(ThemeColors.getColor(isDark, "info_fg"));
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        updateColors(guiTheme);
    }
}
