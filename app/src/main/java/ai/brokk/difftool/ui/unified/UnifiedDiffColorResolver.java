package ai.brokk.difftool.ui.unified;

import ai.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for resolving colors for unified diff display components. This class contains pure functions for color
 * logic that can be tested headlessly, separated from GUI painting concerns.
 */
public final class UnifiedDiffColorResolver {

    private UnifiedDiffColorResolver() {} // Utility class

    /**
     * Get the background color for a given line type in unified diff display. This is the core color mapping logic
     * extracted from GUI painting code.
     *
     * @param lineType The type of diff line
     * @param isDarkTheme Whether using dark theme
     * @return Background color for the line type, or null for default background
     */
    @Nullable
    public static Color getBackgroundColor(UnifiedDiffDocument.LineType lineType, boolean isDarkTheme) {
        return switch (lineType) {
            case ADDITION -> ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_ADDED);
            case DELETION -> ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_DELETED);
            case CONTEXT -> null; // Use default background for context lines
            case HEADER, OMITTED_LINES -> ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_CHANGED);
        };
    }

    /**
     * Get an enhanced version of the background color with better visibility for gutter display. The default Colors are
     * very pale and might not be visible enough in the narrow gutter.
     *
     * @param lineType The type of diff line
     * @param isDarkTheme Whether using dark theme
     * @return Enhanced background color, or null for default background
     */
    @Nullable
    public static Color getEnhancedBackgroundColor(UnifiedDiffDocument.LineType lineType, boolean isDarkTheme) {
        Color originalColor = getBackgroundColor(lineType, isDarkTheme);
        return enhanceColorVisibility(originalColor, isDarkTheme);
    }

    /**
     * Enhance color visibility by making colors more saturated. The default Colors are very pale and might not be
     * visible enough in narrow components.
     *
     * @param originalColor The original color from Colors utility
     * @param isDarkTheme Whether using dark theme
     * @return Enhanced color with better visibility, or null if input was null
     */
    @Nullable
    public static Color enhanceColorVisibility(@Nullable Color originalColor, boolean isDarkTheme) {
        if (originalColor == null) {
            return null;
        }

        // Make colors more saturated for better visibility
        int r = originalColor.getRed();
        int g = originalColor.getGreen();
        int b = originalColor.getBlue();

        if (isDarkTheme) {
            // For dark theme, increase brightness while maintaining hue
            return new Color(
                    Math.min(255, (int) (r * 1.5)), Math.min(255, (int) (g * 1.5)), Math.min(255, (int) (b * 1.5)));
        } else {
            // For light theme, decrease brightness to make colors more visible
            return new Color(
                    Math.max(0, (int) (r * 0.85)), Math.max(0, (int) (g * 0.85)), Math.max(0, (int) (b * 0.85)));
        }
    }

    /**
     * Get appropriate text color for line numbers that contrasts with backgrounds. This ensures line numbers are
     * visible on both colored and default backgrounds.
     *
     * @param isDarkTheme Whether using dark theme
     * @return Text color that provides good contrast
     */
    public static Color getLineNumberTextColor(boolean isDarkTheme) {
        // Use high contrast colors that work well on both colored and default backgrounds
        return isDarkTheme ? new Color(200, 200, 200) : new Color(80, 80, 80);
    }

    /**
     * Get the default gutter background color for the given theme.
     *
     * @param isDarkTheme Whether using dark theme
     * @return Default background color for the gutter
     */
    public static Color getDefaultGutterBackground(boolean isDarkTheme) {
        return isDarkTheme ? new Color(45, 45, 45) : new Color(240, 240, 240);
    }

    /**
     * Get the default gutter foreground color for the given theme.
     *
     * @param isDarkTheme Whether using dark theme
     * @return Default foreground color for the gutter
     */
    public static Color getDefaultGutterForeground(boolean isDarkTheme) {
        return isDarkTheme ? new Color(200, 200, 200) : new Color(60, 60, 60);
    }

    /**
     * Get the gutter border color for visual separation between gutter and content.
     *
     * @param isDarkTheme Whether using dark theme
     * @return Border color for the gutter edge
     */
    public static Color getGutterBorderColor(boolean isDarkTheme) {
        return isDarkTheme ? new Color(70, 70, 70) : new Color(200, 200, 200);
    }
}
