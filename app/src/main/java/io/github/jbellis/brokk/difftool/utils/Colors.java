package io.github.jbellis.brokk.difftool.utils;

import java.awt.*;
import javax.swing.*;

public class Colors {
    // Light Theme Colors
    private static final Color LIGHT_ADDED = new Color(240, 255, 240);
    private static final Color LIGHT_CHANGED = new Color(240, 245, 255);
    private static final Color LIGHT_DELETED = new Color(255, 245, 245);

    // Dark Theme Colors (User specified)
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
