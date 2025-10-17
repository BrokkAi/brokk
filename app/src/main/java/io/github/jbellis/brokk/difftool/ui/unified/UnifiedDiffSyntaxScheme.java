package io.github.jbellis.brokk.difftool.ui.unified;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.jetbrains.annotations.Nullable;

/**
 * Custom syntax scheme for unified diff display that provides appropriate coloring for different types of diff lines
 * while preserving underlying syntax highlighting.
 */
public class UnifiedDiffSyntaxScheme {

    /**
     * Create appropriate diff colors based on the current theme. Uses the same color system as the side-by-side diff
     * panel for consistency.
     *
     * @param isDark true if dark theme is active
     * @return DiffColors instance with theme-appropriate colors
     */
    public static DiffColors createDiffColors(boolean isDark) {
        // Base colors from UIManager
        Color defaultText = UIManager.getColor("Label.foreground");
        Color defaultBackground = UIManager.getColor("Panel.background");

        // Use the same colors as side-by-side diff for consistency
        Color basedAddedColor = ThemeColors.getDiffAdded(isDark);
        Color baseDeletedColor = ThemeColors.getDiffDeleted(isDark);
        Color baseChangedColor = ThemeColors.getDiffChanged(isDark);

        // Create foreground and background colors for unified diff display
        // Background uses the base colors, foreground uses darker/contrasting versions
        Color additionBg = basedAddedColor;
        Color additionFg = deriveContrastingForeground(basedAddedColor);

        Color deletionBg = baseDeletedColor;
        Color deletionFg = deriveContrastingForeground(baseDeletedColor);

        Color headerBg = baseChangedColor;
        Color headerFg = deriveContrastingForeground(baseChangedColor);

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

    /**
     * Derive a contrasting foreground color from a background color for good readability.
     *
     * @param backgroundColor The background color
     * @return A contrasting foreground color
     */
    private static Color deriveContrastingForeground(Color backgroundColor) {
        // Calculate relative luminance to determine if we need dark or light text
        double luminance = 0.299 * backgroundColor.getRed()
                + 0.587 * backgroundColor.getGreen()
                + 0.114 * backgroundColor.getBlue();

        if (luminance > 128) {
            // Light background, use dark text
            return backgroundColor.darker().darker();
        } else {
            // Dark background, use light text
            return backgroundColor.brighter().brighter();
        }
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

    /**
     * Create a custom SyntaxScheme for RSyntaxTextArea that includes diff coloring. This scheme extends the default
     * scheme with diff-specific token colors.
     *
     * @param textArea The RSyntaxTextArea to configure
     * @param colors The diff colors to use
     * @return The configured SyntaxScheme
     */
    public static SyntaxScheme createDiffSyntaxScheme(RSyntaxTextArea textArea, DiffColors colors) {
        // Start with the current syntax scheme
        var scheme = textArea.getSyntaxScheme();
        if (scheme == null) {
            scheme = new SyntaxScheme(true); // Create new scheme with default colors
        } else {
            scheme = (SyntaxScheme) scheme.clone(); // Clone to avoid modifying original
        }

        var baseFont = textArea.getFont();

        // Configure diff-specific token types using standard RSyntaxTextArea token types
        // Addition lines (using RESERVED_WORD_2 token type)
        var additionStyle = new Style(colors.additionForeground(), colors.additionBackground(), baseFont);
        scheme.setStyle(TokenTypes.RESERVED_WORD_2, additionStyle);

        // Deletion lines (using ERROR_IDENTIFIER token type)
        var deletionStyle = new Style(colors.deletionForeground(), colors.deletionBackground(), baseFont);
        scheme.setStyle(TokenTypes.ERROR_IDENTIFIER, deletionStyle);

        // Header lines (using COMMENT_KEYWORD token type)
        var headerFont = baseFont.deriveFont(Font.BOLD);
        var headerStyle = new Style(colors.headerForeground(), colors.headerBackground(), headerFont);
        scheme.setStyle(TokenTypes.COMMENT_KEYWORD, headerStyle);

        // Context lines (using IDENTIFIER token type)
        var contextStyle = new Style(colors.defaultForeground(), null, baseFont);
        scheme.setStyle(TokenTypes.IDENTIFIER, contextStyle);

        return scheme;
    }
}
