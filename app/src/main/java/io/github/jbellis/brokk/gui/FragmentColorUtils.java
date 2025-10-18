package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import javax.swing.UIManager;

/**
 * Shared utilities for fragment classification and color styling, used by both WorkspaceItemsChipPanel and
 * TokenUsageBar.
 */
public class FragmentColorUtils {

    public enum FragmentKind {
        EDIT,
        SUMMARY,
        OTHER
    }

    /**
     * Classifies a fragment into EDIT (user-editable), SUMMARY (skeleton outputs), or OTHER.
     */
    public static FragmentKind classify(ContextFragment fragment) {
        if (fragment.getType().isEditable()) {
            return FragmentKind.EDIT;
        }
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return FragmentKind.SUMMARY;
        }
        return FragmentKind.OTHER;
    }

    /**
     * Gets the background color for a fragment based on its classification and theme.
     */
    public static Color getBackgroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> {
                Color bg = UIManager.getColor("Component.accentColor");
                if (bg == null) {
                    bg = UIManager.getColor("Component.linkColor");
                }
                if (bg == null) {
                    bg = ThemeColors.getColor(isDarkTheme, "git_badge_background");
                }
                // Lighten in light mode
                if (!isDarkTheme) {
                    bg = lighten(bg, 0.7f);
                }
                yield bg;
            }
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "notif_cost_bg");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "notif_info_bg");
        };
    }

    /**
     * Gets the foreground (text) color for a fragment based on its background and theme.
     */
    public static Color getForegroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> contrastingText(getBackgroundColor(kind, isDarkTheme));
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "notif_cost_fg");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "notif_info_fg");
        };
    }

    /**
     * Gets the border color for a fragment based on its classification and theme.
     */
    public static Color getBorderColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> {
                Color border = UIManager.getColor("Component.borderColor");
                yield border != null ? border : Color.GRAY;
            }
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "notif_cost_border");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "notif_info_border");
        };
    }

    /**
     * Determines text color (white or dark) based on background luminance.
     */
    private static Color contrastingText(Color bg) {
        return isDarkColor(bg) ? Color.WHITE : Color.BLACK;
    }

    /**
     * Checks if a color is dark using ITU-R BT.709 relative luminance.
     */
    private static boolean isDarkColor(Color c) {
        double r = c.getRed() / 255.0;
        double g = c.getGreen() / 255.0;
        double b = c.getBlue() / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum < 0.5;
    }

    /**
     * Lighten a color by blending towards white by the given fraction (0..1).
     */
    public static Color lighten(Color c, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        int r = c.getRed() + Math.round((255 - c.getRed()) * fraction);
        int g = c.getGreen() + Math.round((255 - c.getGreen()) * fraction);
        int b = c.getBlue() + Math.round((255 - c.getBlue()) * fraction);
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), c.getAlpha());
    }
}
