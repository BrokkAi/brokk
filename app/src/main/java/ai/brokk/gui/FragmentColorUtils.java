package ai.brokk.gui;

import ai.brokk.context.ContextFragment;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.Color;

/**
 * Shared utilities for fragment classification and color styling, used by both WorkspaceItemsChipPanel and
 * TokenUsageBar.
 */
public class FragmentColorUtils {

    public enum FragmentKind {
        EDIT,
        SUMMARY,
        HISTORY,
        OTHER
    }

    /**
     * Classifies a fragment into EDIT (user-editable), SUMMARY (skeleton outputs), HISTORY, or OTHER.
     */
    public static FragmentKind classify(ContextFragment fragment) {
        if (fragment.getType().isEditable()) {
            return FragmentKind.EDIT;
        }
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return FragmentKind.SUMMARY;
        }
        if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
            return FragmentKind.HISTORY;
        }
        return FragmentKind.OTHER;
    }

    /**
     * Gets the background color for a fragment based on its classification and theme.
     */
    public static Color getBackgroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, "chip_edit_bg");
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "chip_summary_bg");
            case HISTORY -> ThemeColors.getColor(isDarkTheme, "chip_history_bg");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "chip_other_bg");
        };
    }

    /**
     * Gets the foreground (text) color for a fragment based on its classification and theme.
     */
    public static Color getForegroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, "chip_edit_fg");
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "chip_summary_fg");
            case HISTORY -> ThemeColors.getColor(isDarkTheme, "chip_history_fg");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "chip_other_fg");
        };
    }

    /**
     * Gets the border color for a fragment based on its classification and theme.
     */
    public static Color getBorderColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, "chip_edit_border");
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, "chip_summary_border");
            case HISTORY -> ThemeColors.getColor(isDarkTheme, "chip_history_border");
            case OTHER -> ThemeColors.getColor(isDarkTheme, "chip_other_border");
        };
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
