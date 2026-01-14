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
     * Apply diff highlights to unified diff content in the text area. Parses the unified diff text line by line and
     * applies appropriate highlights based on prefix characters.
     *
     * @param textArea The RSyntaxTextArea containing unified diff content
     * @param highlighter The JMHighlighter to apply highlights with
     * @param isDarkTheme Whether to use dark theme colors
     * @deprecated Use {@link #applyHighlights(RSyntaxTextArea, JMHighlighter, UnifiedDiffDocument, boolean)} instead
     */
    @Deprecated
    public static void applyHighlights(RSyntaxTextArea textArea, JMHighlighter highlighter, boolean isDarkTheme) {
        try {
            String content = textArea.getText();
            if (content == null || content.isEmpty()) {
                return;
            }

            String[] lines = content.split("\n", -1); // Include empty trailing strings

            int currentOffset = 0;
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex];
                int lineLength = line.length();

                // Calculate line bounds for highlighting
                int lineStart = currentOffset;
                int lineEnd = currentOffset + lineLength;

                // Apply highlight based on line prefix
                applyLineHighlight(highlighter, line, lineStart, lineEnd, isDarkTheme);

                // Move to next line (add 1 for newline character, except for last line)
                currentOffset = lineEnd + (lineIndex < lines.length - 1 ? 1 : 0);
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
     * Apply highlight to a single line based on its prefix.
     *
     * @param highlighter The JMHighlighter to use
     * @param line The line content
     * @param lineStart Start offset in the document
     * @param lineEnd End offset in the document
     * @param isDarkTheme Whether to use dark theme colors
     */
    private static void applyLineHighlight(
            JMHighlighter highlighter, String line, int lineStart, int lineEnd, boolean isDarkTheme) {

        if (line.isEmpty()) {
            return; // Skip empty lines
        }

        char prefix = line.charAt(0);
        JMHighlightPainter painter = null;

        switch (prefix) {
            case '+' -> {
                // Addition line - highlight in green with full line width
                var addedColor = ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_ADDED);
                painter = new JMHighlightPainter.JMHighlightFullLinePainter(addedColor);
                logger.trace("Highlighting addition line: {}", line.substring(0, Math.min(50, line.length())));
            }
            case '-' -> {
                // Deletion line - highlight in red with full line width
                var deletedColor = ThemeColors.getColor(isDarkTheme, ThemeColors.DIFF_DELETED);
                painter = new JMHighlightPainter.JMHighlightFullLinePainter(deletedColor);
                logger.trace("Highlighting deletion line: {}", line.substring(0, Math.min(50, line.length())));
            }
            // Note: HEADER lines are now detected via LineType, not prefix character
            case ' ' -> {
                // Context line - no highlighting needed, handled by syntax highlighting
                logger.trace("Context line (no diff highlight): {}", line.substring(0, Math.min(50, line.length())));
            }
            default -> {
                // Unknown prefix - no highlighting
                logger.trace("Unknown line prefix '{}', no highlighting", prefix);
            }
        }

        // Apply the highlight if we determined one is needed
        if (painter != null) {
            try {
                highlighter.addHighlight(JMHighlighter.LAYER0, lineStart, lineEnd, painter);
                logger.trace("Added highlight from {} to {}", lineStart, lineEnd);
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
