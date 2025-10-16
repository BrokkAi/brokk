package io.github.jbellis.brokk.difftool.utils;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import java.awt.*;
import javax.swing.*;

/**
 * Compatibility shim that delegates to {@link ThemeColors}.
 * New code should use ThemeColors directly.
 */
public class Colors {
    // Search colors (currently theme-independent)
    public static final Color SEARCH = ThemeColors.getSearchHighlight();
    public static final Color CURRENT_SEARCH = ThemeColors.getCurrentSearchHighlight();

    // --- Theme-aware Getters ---

    public static Color getAdded(boolean isDark) {
        return ThemeColors.getDiffAdded(isDark);
    }

    public static Color getChanged(boolean isDark) {
        return ThemeColors.getDiffChanged(isDark);
    }

    public static Color getDeleted(boolean isDark) {
        return ThemeColors.getDiffDeleted(isDark);
    }

    // --- Other Colors ---

    public static Color getPanelBackground() {
        return new JPanel().getBackground();
    }
}
