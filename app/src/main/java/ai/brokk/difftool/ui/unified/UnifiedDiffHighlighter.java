package ai.brokk.difftool.ui.unified;

import ai.brokk.difftool.ui.JMHighlightPainter;
import ai.brokk.difftool.ui.JMHighlighter;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Utility class for applying diff highlights to unified diff text in RSyntaxTextArea. Similar to DeltaHighlighter but
 * designed for unified diff format parsing.
 */
public final class UnifiedDiffHighlighter {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffHighlighter.class);

    private UnifiedDiffHighlighter() {} // Utility class

    /**
     * Apply diff highlights to unified diff content using document LineType (preferred).
     *
     * @param textArea The RSyntaxTextArea containing unified diff content
     * @param highlighter The JMHighlighter to apply highlights with
     * @param document The UnifiedDiffDocument providing line type information
     * @param isDarkTheme Whether to use dark theme colors
     */
    public static void applyHighlights(
            RSyntaxTextArea textArea, JMHighlighter highlighter, UnifiedDiffDocument document, boolean isDarkTheme) {
        try {
            var filteredLines = document.getFilteredLines();
            if (filteredLines.isEmpty()) {
                return;
            }

            int currentOffset = 0;
            for (int lineIndex = 0; lineIndex < filteredLines.size(); lineIndex++) {
                var diffLine = filteredLines.get(lineIndex);
                String content = diffLine.getContent();

                int lineStart = currentOffset;
                int lineEnd = currentOffset + content.length();

                // Apply highlight based on LineType
                applyLineHighlightByType(highlighter, diffLine.getType(), lineStart, lineEnd, isDarkTheme);

                // Move to next line
                currentOffset = lineEnd + (content.endsWith("\n") ? 0 : 1);
            }

        } catch (Exception e) {
            logger.warn("Error applying unified diff highlights: {}", e.getMessage(), e);
        }
    }

    /**
     * Apply highlight to a line based on its LineType.
     */
    private static void applyLineHighlightByType(
            JMHighlighter highlighter,
            UnifiedDiffDocument.LineType lineType,
            int lineStart,
            int lineEnd,
            boolean isDarkTheme) {

        JMHighlightPainter painter = null;

        switch (lineType) {
            case ADDITION -> {
                var addedColor = ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_ADDED);
                painter = new JMHighlightPainter.JMHighlightFullLinePainter(addedColor);
            }
            case DELETION -> {
                var deletedColor = ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_DELETED);
                painter = new JMHighlightPainter.JMHighlightFullLinePainter(deletedColor);
            }
            case HEADER -> {
                var separatorColor = isDarkTheme ? new Color(140, 140, 140) : new Color(160, 160, 160);
                painter = new JMHighlightPainter.JMHighlightWavyLinePainter(separatorColor);
            }
            case CONTEXT, OMITTED_LINES -> {
                // No highlighting needed
            }
        }

        if (painter != null) {
            try {
                highlighter.addHighlight(JMHighlighter.LAYER0, lineStart, lineEnd, painter);
            } catch (BadLocationException e) {
                logger.warn("Failed to add highlight at offset {} to {}: {}", lineStart, lineEnd, e.getMessage());
            }
        }
    }

    /**
     * Remove all diff highlights from the highlighter. This clears highlights from all layers used for diff
     * highlighting.
     *
     * @param highlighter The JMHighlighter to clear
     */
    public static void removeHighlights(JMHighlighter highlighter) {
        highlighter.removeHighlights(JMHighlighter.LAYER0);
        highlighter.removeHighlights(JMHighlighter.LAYER1);
        highlighter.removeHighlights(JMHighlighter.LAYER2);
    }

    /**
     * Check if a line is a diff content line that should be highlighted. This excludes file headers and other
     * non-content lines.
     *
     * @param line The line to check
     * @return true if the line should receive diff highlighting
     */
    public static boolean isHighlightableLine(String line) {
        if (line.isEmpty()) {
            return false;
        }

        char prefix = line.charAt(0);
        return switch (prefix) {
            case '+', '-' -> true; // Addition/deletion lines
            case ' ' -> false; // Context lines don't need diff highlighting
            default -> false; // Unknown prefixes (HEADER lines detected via LineType)
        };
    }
}
