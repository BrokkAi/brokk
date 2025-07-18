package io.github.jbellis.brokk.difftool.utils;

import javax.swing.*;
import java.awt.*;

public class Colors {
    // Light Theme Colors
    private static final Color LIGHT_ADDED = new Color(180, 255, 180);
    private static final Color LIGHT_CHANGED = new Color(160, 200, 255);
    private static final Color LIGHT_DELETED = new Color(255, 160, 180);

    // Dark Theme Colors (User specified)
    private static final Color DARK_ADDED = new Color(117, 193, 35).darker().darker();
    private static final Color DARK_CHANGED = new Color(0, 0, 102).brighter();
    private static final Color DARK_DELETED = new Color(77, 46, 8);

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
