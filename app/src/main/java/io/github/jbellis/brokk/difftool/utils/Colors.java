package io.github.jbellis.brokk.difftool.utils;

import java.awt.*;
import javax.swing.*;

public class Colors {
    // Light Theme Colors. These are BACKGROUND colors for highlighting (pastel/soft colors)
    private static final Color LIGHT_ADDED = new Color(220, 250, 220);
    private static final Color LIGHT_CHANGED = new Color(220, 235, 250);
    private static final Color LIGHT_DELETED = new Color(250, 220, 220);

    // Dark Theme Colors. These are BACKGROUND colors for highlighting
    private static final Color DARK_ADDED = new Color(60, 80, 60);
    private static final Color DARK_CHANGED = new Color(49, 75, 101);
    private static final Color DARK_DELETED = new Color(80, 60, 60);

    // Search colors (currently theme-independent)
    public static final Color SEARCH = Color.yellow;
    public static final Color CURRENT_SEARCH = new Color(255, 165, 0); // Orange

    // --- Theme-aware Getters ---

    public static Color getAdded(boolean isDark) {
        return isDark ? DARK_ADDED : LIGHT_ADDED;
    }

    public static Color getChanged(boolean isDark) {
        return isDark ? DARK_CHANGED : LIGHT_CHANGED;
    }

    public static Color getDeleted(boolean isDark) {
        return isDark ? DARK_DELETED : LIGHT_DELETED;
    }

    // --- Other Colors ---

    public static Color getPanelBackground() {
        // This might need theme awareness too, but using default for now
        return new JPanel().getBackground();
    }
}
