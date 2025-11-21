package ai.brokk.gui;

import ai.brokk.context.ContextFragment;
import ai.brokk.context.SpecialTextType;
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
        TASK_LIST,
        OTHER
    }

    /**
     * Classifies a fragment into EDIT (user-editable), SUMMARY (skeleton outputs), HISTORY, TASK_LIST, or OTHER.
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
        if (fragment instanceof ContextFragment.StringFragment sf
                && SpecialTextType.TASK_LIST
                        .description()
                        .equals(sf.description().renderNowOrNull())) {
            return FragmentKind.TASK_LIST;
        }
        return FragmentKind.OTHER;
    }

    /**
     * Gets the background color for a fragment based on its classification and theme.
     */
    public static Color getBackgroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_EDIT_BACKGROUND);
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_SUMMARY_BACKGROUND);
            case HISTORY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_HISTORY_BACKGROUND);
            case TASK_LIST -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_TASKLIST_BACKGROUND);
            case OTHER -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_OTHER_BACKGROUND);
        };
    }

    /**
     * Gets the foreground (text) color for a fragment based on its classification and theme.
     */
    public static Color getForegroundColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_EDIT_FOREGROUND);
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_SUMMARY_FOREGROUND);
            case HISTORY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_HISTORY_FOREGROUND);
            case TASK_LIST -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_TASKLIST_FOREGROUND);
            case OTHER -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_OTHER_FOREGROUND);
        };
    }

    /**
     * Gets the border color for a fragment based on its classification and theme.
     */
    public static Color getBorderColor(FragmentKind kind, boolean isDarkTheme) {
        return switch (kind) {
            case EDIT -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_EDIT_BORDER);
            case SUMMARY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_SUMMARY_BORDER);
            case HISTORY -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_HISTORY_BORDER);
            case TASK_LIST -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_TASKLIST_BORDER);
            case OTHER -> ThemeColors.getColor(isDarkTheme, ThemeColors.CHIP_OTHER_BORDER);
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
