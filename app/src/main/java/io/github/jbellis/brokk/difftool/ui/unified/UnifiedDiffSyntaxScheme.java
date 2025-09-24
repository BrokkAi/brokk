package io.github.jbellis.brokk.difftool.ui.unified;

import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Custom syntax scheme for unified diff display that provides appropriate coloring for different types of diff lines
 * while preserving underlying syntax highlighting.
 */
public class UnifiedDiffSyntaxScheme {

    /**
     * Create appropriate diff colors based on the current theme. Uses UIManager colors and creates diff-specific colors
     * if not available.
     *
     * @param isDark true if dark theme is active
     * @return DiffColors instance with theme-appropriate colors
     */
    public static DiffColors createDiffColors(boolean isDark) {
        // Base colors from UIManager
        Color defaultText = UIManager.getColor("Label.foreground");
        Color defaultBackground = UIManager.getColor("Panel.background");
        // Note: selectionBackground could be used for future enhancements

        // Create diff-specific colors
        Color additionFg, additionBg, deletionFg, deletionBg, headerFg, headerBg;

        if (isDark) {
            // Dark theme colors
            additionFg = new Color(144, 238, 144); // Light green
            additionBg = new Color(0, 64, 0); // Dark green background
            deletionFg = new Color(255, 182, 193); // Light red
            deletionBg = new Color(64, 0, 0); // Dark red background
            headerFg = new Color(173, 216, 230); // Light blue
            headerBg = new Color(0, 32, 64); // Dark blue background
        } else {
            // Light theme colors
            additionFg = new Color(0, 128, 0); // Dark green
            additionBg = new Color(220, 255, 220); // Light green background
            deletionFg = new Color(178, 34, 34); // Dark red
            deletionBg = new Color(255, 220, 220); // Light red background
            headerFg = new Color(0, 0, 139); // Dark blue
            headerBg = new Color(220, 220, 255); // Light blue background
        }

        // Fallback if UIManager colors are null
        if (defaultText == null) {
            defaultText = isDark ? Color.WHITE : Color.BLACK;
        }
        if (defaultBackground == null) {
            defaultBackground = isDark ? Color.DARK_GRAY : Color.WHITE;
        }

        return new DiffColors(
                defaultText, defaultBackground, additionFg, additionBg, deletionFg, deletionBg, headerFg, headerBg);
    }

    /** Record to hold diff-specific colors. */
    public record DiffColors(
            Color defaultForeground,
            Color defaultBackground,
            Color additionForeground,
            Color additionBackground,
            Color deletionForeground,
            Color deletionBackground,
            Color headerForeground,
            Color headerBackground) {}

    /**
     * Get the appropriate style for a diff line based on its prefix and theme.
     *
     * @param line The line content including diff prefix
     * @param colors The diff colors to use
     * @param baseFont The base font to use
     * @return Style configuration for the line
     */
    public static Style getStyleForDiffLine(String line, DiffColors colors, Font baseFont) {
        var style = new Style();
        style.font = baseFont;

        if (line.startsWith("+")) {
            // Addition line
            style.foreground = colors.additionForeground();
            style.background = colors.additionBackground();
        } else if (line.startsWith("-")) {
            // Deletion line
            style.foreground = colors.deletionForeground();
            style.background = colors.deletionBackground();
        } else if (line.startsWith("@@")) {
            // Hunk header
            style.foreground = colors.headerForeground();
            style.font = baseFont.deriveFont(Font.BOLD);
            style.background = colors.headerBackground();
        } else if (line.startsWith("...")) {
            // Omitted lines indicator
            Color secondaryText = UIManager.getColor("Label.disabledForeground");
            if (secondaryText == null) {
                secondaryText = colors.defaultForeground().darker();
            }
            style.foreground = secondaryText;
            style.font = baseFont.deriveFont(Font.ITALIC);
        } else {
            // Context line or normal content
            style.foreground = colors.defaultForeground();
            style.background = null; // Use default background
        }

        return style;
    }

    /**
     * Get color for a diff line type.
     *
     * @param lineType The type of diff line
     * @param colors The diff colors to use
     * @return Appropriate foreground color
     */
    public static Color getColorForLineType(UnifiedDiffDocument.LineType lineType, DiffColors colors) {
        return switch (lineType) {
            case ADDITION -> colors.additionForeground();
            case DELETION -> colors.deletionForeground();
            case HEADER -> colors.headerForeground();
            case OMITTED_LINES -> {
                Color secondary = UIManager.getColor("Label.disabledForeground");
                yield secondary != null ? secondary : colors.defaultForeground().darker();
            }
            case CONTEXT -> colors.defaultForeground();
        };
    }

    /**
     * Get background color for a diff line type.
     *
     * @param lineType The type of diff line
     * @param colors The diff colors to use
     * @return Appropriate background color, or null for default
     */
    @Nullable
    public static Color getBackgroundColorForLineType(UnifiedDiffDocument.LineType lineType, DiffColors colors) {
        return switch (lineType) {
            case ADDITION -> colors.additionBackground();
            case DELETION -> colors.deletionBackground();
            case HEADER -> colors.headerBackground();
            case OMITTED_LINES, CONTEXT -> null; // Use default background
        };
    }

    /**
     * Check if a line type should use bold font.
     *
     * @param lineType The type of diff line
     * @return true if the line should be bold
     */
    public static boolean shouldUseBoldFont(UnifiedDiffDocument.LineType lineType) {
        return lineType == UnifiedDiffDocument.LineType.HEADER;
    }

    /**
     * Check if a line type should use italic font.
     *
     * @param lineType The type of diff line
     * @return true if the line should be italic
     */
    public static boolean shouldUseItalicFont(UnifiedDiffDocument.LineType lineType) {
        return lineType == UnifiedDiffDocument.LineType.OMITTED_LINES;
    }

    /**
     * Create a font variant based on the line type.
     *
     * @param baseFont The base font
     * @param lineType The type of diff line
     * @return Modified font appropriate for the line type
     */
    public static Font getFontForLineType(Font baseFont, UnifiedDiffDocument.LineType lineType) {
        int style = Font.PLAIN;

        if (shouldUseBoldFont(lineType)) {
            style |= Font.BOLD;
        }

        if (shouldUseItalicFont(lineType)) {
            style |= Font.ITALIC;
        }

        return baseFont.deriveFont(style);
    }
}
