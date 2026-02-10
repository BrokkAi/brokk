package ai.brokk.gui.components;

import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable notification label for displaying staleness or status warnings.
 */
public class NoticeBanner extends JPanel implements ThemeAware {
    private final JLabel label;

    public NoticeBanner() {
        super(new BorderLayout(8, 0));
        setOpaque(true);
        setVisible(false);
        setBorder(new EmptyBorder(8, 12, 8, 12));

        label = new JLabel();
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        add(label, BorderLayout.CENTER);
    }

    public void setIcon(@Nullable Icon icon) {
        label.setIcon(icon);
    }

    public void setMessage(@Nullable String message) {
        if (message == null || message.isBlank()) {
            setVisible(false);
        } else {
            label.setText(message);
            setVisible(true);
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Use a warning/staleness color scheme
        setBackground(new Color(0xFFD700)); // Yellow/Gold
        label.setForeground(Color.BLACK);
    }
}
