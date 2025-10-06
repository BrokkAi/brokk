package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffColorResolver;
import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffDocument;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.Nullable;

/**
 * Unified gutter component for both unified diff display and side-by-side diff display. For unified mode, it shows dual
 * columns with actual source line numbers. For side-by-side mode, it shows a single column of sequential line numbers
 * with diff highlighting.
 */
public class DiffGutterComponent extends JComponent {
    private static final Logger logger = LogManager.getLogger(DiffGutterComponent.class);

    /** Display mode for the gutter component */
    public enum DisplayMode {
        UNIFIED_DUAL_COLUMN, // Shows both left and right line numbers for unified diff
        SIDE_BY_SIDE_SINGLE // Shows sequential line numbers for side-by-side diff
    }

    @Nullable
    private UnifiedDiffDocument unifiedDocument;

    private final RSyntaxTextArea textArea;
    private boolean isDarkTheme = false;
    private UnifiedDiffDocument.ContextMode contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
    private DisplayMode displayMode = DisplayMode.UNIFIED_DUAL_COLUMN;

    // Side-by-side mode specific fields
    @Nullable
    private List<DiffHighlightInfo> diffHighlights;

    /** Information about diff highlighting for a line in side-by-side mode */
    public static class DiffHighlightInfo {
        private final int lineNumber;
        private final Color backgroundColor;

        public DiffHighlightInfo(int lineNumber, Color backgroundColor) {
            this.lineNumber = lineNumber;
            this.backgroundColor = backgroundColor;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public Color getBackgroundColor() {
            return backgroundColor;
        }
    }

    /**
     * Create a gutter component for unified diff display (dual column mode).
     *
     * @param textArea The text area to provide line numbers for
     */
    public DiffGutterComponent(RSyntaxTextArea textArea) {
        this(textArea, DisplayMode.UNIFIED_DUAL_COLUMN);
    }

    /**
     * Create a gutter component with specified display mode.
     *
     * @param textArea The text area to provide line numbers for
     * @param displayMode The display mode (unified or side-by-side)
     */
    public DiffGutterComponent(RSyntaxTextArea textArea, DisplayMode displayMode) {
        this.textArea = textArea;
        this.displayMode = displayMode;
        setOpaque(true);
        // Use theme-aware colors from utility
        setBackground(UnifiedDiffColorResolver.getDefaultGutterBackground(isDarkTheme));
        setForeground(UnifiedDiffColorResolver.getDefaultGutterForeground(isDarkTheme));

        // Add scroll listener to ensure we repaint when the text area scrolls
        setupScrollListener();
    }

    /**
     * Set the display mode for this gutter component.
     *
     * @param mode The display mode to use
     */
    public void setDisplayMode(DisplayMode mode) {
        if (this.displayMode != mode) {
            this.displayMode = mode;
            repaint();
        }
    }

    /**
     * Get the current display mode.
     *
     * @return The current display mode
     */
    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    /**
     * Set diff highlight information for side-by-side mode.
     *
     * @param highlights List of diff highlight information
     */
    public void setDiffHighlights(@Nullable List<DiffHighlightInfo> highlights) {
        this.diffHighlights = highlights;
        if (displayMode == DisplayMode.SIDE_BY_SIDE_SINGLE) {
            repaint();
        }
    }

    /**
     * Update the theme setting for color-coded line numbers.
     *
     * @param isDark Whether the current theme is dark
     */
    public void setDarkTheme(boolean isDark) {
        this.isDarkTheme = isDark;
        // Update component colors based on theme using utility
        setBackground(UnifiedDiffColorResolver.getDefaultGutterBackground(isDark));
        setForeground(UnifiedDiffColorResolver.getDefaultGutterForeground(isDark));
        repaint();
    }

    /**
     * Set the context mode for coordinate calculation optimization (unified mode only).
     *
     * @param contextMode The context mode (STANDARD_3_LINES or FULL_CONTEXT)
     */
    public void setContextMode(UnifiedDiffDocument.ContextMode contextMode) {
        if (this.contextMode != contextMode) {
            this.contextMode = contextMode;
            repaint();
        }
    }

    /**
     * Set the unified diff document to use for line number lookup (unified mode only).
     *
     * @param document The unified diff document containing line metadata
     */
    public void setUnifiedDocument(UnifiedDiffDocument document) {
        this.unifiedDocument = document;
        // Force immediate repaint to ensure line numbers are updated
        javax.swing.SwingUtilities.invokeLater(() -> repaint());
    }

    /** Clear the unified diff document reference. */
    public void clearUnifiedDocument() {
        this.unifiedDocument = null;
        repaint();
    }

    /** Set up scroll listener to ensure the gutter repaints when the text area scrolls. */
    private void setupScrollListener() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            // Listen to text area viewport changes
            if (textArea.getParent() instanceof javax.swing.JViewport textAreaViewport) {
                textAreaViewport.addChangeListener(e -> repaint());
            }

            // Also listen to our own parent viewport changes (row header viewport)
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (getParent() instanceof javax.swing.JViewport rowHeaderViewport) {
                    rowHeaderViewport.addChangeListener(e -> repaint());
                }
            });
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (displayMode == DisplayMode.UNIFIED_DUAL_COLUMN) {
            paintUnifiedDiffLineNumbers(g);
        } else {
            paintSideBySideLineNumbers(g);
        }
    }

    /** Paint line numbers for unified diff mode (dual column). */
    private void paintUnifiedDiffLineNumbers(Graphics g) {
        if (unifiedDocument == null) {
            super.paintComponent(g);
            return;
        }

        // textArea is never null as it's passed in constructor

        var clipBounds = g.getClipBounds();
        if (clipBounds == null || clipBounds.isEmpty()) {
            return;
        }

        // Use the same colors and fonts as the parent line number list
        Color fg = getForeground();
        if (fg == null) {
            fg = Color.GRAY;
        }

        Color bg = getBackground();
        if (bg == null) {
            bg = Color.WHITE;
        }

        // Fill default background first
        g.setColor(bg);
        g.fillRect(clipBounds.x, clipBounds.y, clipBounds.width, clipBounds.height);
        g.setColor(fg);

        FontMetrics fm = g.getFontMetrics();
        int fontAscent = fm.getAscent();

        try {
            var textAreaViewport = textArea.getParent();
            if (textAreaViewport instanceof javax.swing.JViewport) {
                int textAreaStartY = clipBounds.y;
                int textAreaEndY = clipBounds.y + clipBounds.height;

                int startOffset = textArea.viewToModel2D(new Point(0, textAreaStartY));
                int endOffset = textArea.viewToModel2D(new Point(0, textAreaEndY));

                int startLine = textArea.getLineOfOffset(startOffset);
                int endLine = textArea.getLineOfOffset(endOffset);

                startLine = Math.max(0, startLine);
                endLine = Math.min(textArea.getLineCount() - 1, endLine);

                if (unifiedDocument != null) {
                    int diffDocumentLines = unifiedDocument.getFilteredLines().size();
                    endLine = Math.min(endLine, diffDocumentLines - 1);
                }

                // Paint line numbers for visible lines with color-coded backgrounds
                for (int documentLine = startLine; documentLine <= endLine; documentLine++) {
                    if (unifiedDocument != null) {
                        var diffLine = getDiffLineForTextLine(documentLine);

                        if (diffLine != null) {
                            // Calculate line position in text area coordinates
                            var lineStartOffset = textArea.getLineStartOffset(documentLine);
                            var lineRect = textArea.modelToView2D(lineStartOffset);

                            if (lineRect != null) {
                                int textAreaLineY = (int) lineRect.getY();
                                int lineY = textAreaLineY;
                                int lineHeight = (int) lineRect.getHeight();

                                boolean coordsReasonable =
                                        (lineY > -1000 && lineY < getHeight() + 1000 && lineHeight > 0);
                                if (!coordsReasonable) {
                                    continue;
                                }

                                // Paint background color based on line type
                                paintLineBackground(g, documentLine, lineY, lineHeight);

                                // Format and paint line numbers
                                var lineNumbers = formatLineNumbers(diffLine);
                                if (lineNumbers != null) {
                                    paintDualColumnNumbers(g, lineNumbers, lineY, fontAscent, fm);
                                }
                            }
                        } else {
                            break;
                        }
                    }
                }
            }

        } catch (BadLocationException e) {
            logger.warn("Error painting unified diff line numbers: {}", e.getMessage());
        }

        // Always paint the full-height border on the right edge for visual separation
        g.setColor(UnifiedDiffColorResolver.getGutterBorderColor(isDarkTheme));
        g.drawLine(getWidth() - 1, clipBounds.y, getWidth() - 1, clipBounds.y + clipBounds.height - 1);
    }

    /** Paint line numbers for side-by-side mode (single column with sequential numbering). */
    private void paintSideBySideLineNumbers(Graphics g) {
        // textArea is never null as it's passed in constructor

        var clipBounds = g.getClipBounds();
        if (clipBounds == null || clipBounds.isEmpty()) {
            return;
        }

        // Use theme-aware colors
        Color fg = UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme);
        Color bg = getBackground();

        // Fill default background
        g.setColor(bg);
        g.fillRect(clipBounds.x, clipBounds.y, clipBounds.width, clipBounds.height);

        FontMetrics fm = g.getFontMetrics();
        int fontAscent = fm.getAscent();

        try {
            // Calculate visible line range
            int textAreaStartY = clipBounds.y;
            int textAreaEndY = clipBounds.y + clipBounds.height;

            int startOffset = textArea.viewToModel2D(new Point(0, textAreaStartY));
            int endOffset = textArea.viewToModel2D(new Point(0, textAreaEndY));

            int startLine = textArea.getLineOfOffset(startOffset);
            int endLine = textArea.getLineOfOffset(endOffset);

            startLine = Math.max(0, startLine);
            endLine = Math.min(textArea.getLineCount() - 1, endLine);

            // Paint line numbers for visible lines
            for (int documentLine = startLine; documentLine <= endLine; documentLine++) {
                var lineStartOffset = textArea.getLineStartOffset(documentLine);
                var lineRect = textArea.modelToView2D(lineStartOffset);

                if (lineRect != null) {
                    int lineY = (int) lineRect.getY();
                    int lineHeight = (int) lineRect.getHeight();

                    // Paint diff background if available
                    paintSideBySideLineBackground(g, documentLine, lineY, lineHeight);

                    // Paint line number (1-based)
                    String lineNumber = String.valueOf(documentLine + 1);
                    int gutterWidth = getWidth();
                    int rightPadding = 8; // Space before the border
                    int textX = gutterWidth - fm.stringWidth(lineNumber) - rightPadding;

                    g.setColor(fg);
                    g.drawString(lineNumber, Math.max(4, textX), lineY + fontAscent);
                }
            }

        } catch (BadLocationException e) {
            logger.warn("Error painting side-by-side line numbers: {}", e.getMessage());
        }

        // Always paint the full-height border on the right edge
        g.setColor(UnifiedDiffColorResolver.getGutterBorderColor(isDarkTheme));
        g.drawLine(getWidth() - 1, clipBounds.y, getWidth() - 1, clipBounds.y + clipBounds.height - 1);
    }

    /** Paint background color for a line in side-by-side mode based on diff highlights. */
    private void paintSideBySideLineBackground(Graphics g, int documentLine, int lineY, int lineHeight) {
        if (diffHighlights == null) {
            return;
        }

        // Find highlight for this line (documentLine is 0-based, highlights use 1-based)
        int lineNumber = documentLine + 1;
        for (var highlight : diffHighlights) {
            if (highlight.getLineNumber() == lineNumber) {
                g.setColor(highlight.getBackgroundColor());
                g.fillRect(0, lineY, getWidth(), lineHeight);
                break;
            }
        }
    }

    /** Paint the background color for a line based on its diff type (unified mode). */
    private void paintLineBackground(Graphics g, int documentLine, int lineY, int lineHeight) {
        if (unifiedDocument == null) {
            return;
        }

        var diffLine = unifiedDocument.getDiffLine(documentLine);
        if (diffLine == null) {
            return;
        }

        // Use same colors as text area highlights for consistency (no enhancement)
        Color backgroundColor = UnifiedDiffColorResolver.getBackgroundColor(diffLine.getType(), isDarkTheme);

        if (backgroundColor != null) {
            g.setColor(backgroundColor);
            g.fillRect(0, lineY, getWidth(), lineHeight);
        }
    }

    /** Format line numbers for display using GitHub-style dual column approach (unified mode). */
    @Nullable
    private String[] formatLineNumbers(UnifiedDiffDocument.DiffLine diffLine) {
        int leftLine = diffLine.getLeftLineNumber();
        int rightLine = diffLine.getRightLineNumber();

        return switch (diffLine.getType()) {
            case CONTEXT -> {
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case ADDITION -> {
                String leftText = "    ";
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case DELETION -> {
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = "    ";
                yield new String[] {leftText, rightText};
            }
            case HEADER -> {
                yield new String[] {"    ", "    "};
            }
            case OMITTED_LINES -> {
                yield new String[] {"    ", "    "};
            }
        };
    }

    /** Paint dual column line numbers in GitHub style (unified mode). */
    private void paintDualColumnNumbers(Graphics g, String[] lineNumbers, int lineY, int fontAscent, FontMetrics fm) {
        g.setColor(UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme));

        int textY = lineY + fontAscent;
        int gutterWidth = getWidth();

        int columnWidth = fm.stringWidth("9999");
        int columnGap = 4;
        int leftPadding = 4;
        int rightPadding = 6;

        int leftColumnX = leftPadding;
        int rightColumnX = leftPadding + columnWidth + columnGap;

        int totalNeededWidth = leftPadding + columnWidth + columnGap + columnWidth + rightPadding;
        if (gutterWidth < totalNeededWidth) {
            int centerX = gutterWidth / 2;
            leftColumnX = centerX - columnWidth - columnGap / 2;
            rightColumnX = centerX + columnGap / 2;
        }

        // Paint left column
        String leftText = lineNumbers[0];
        if (!leftText.trim().isEmpty()) {
            int leftTextX = leftColumnX + columnWidth - fm.stringWidth(leftText);
            g.drawString(leftText, Math.max(0, leftTextX), textY);
        }

        // Paint right column
        String rightText = lineNumbers[1];
        if (!rightText.trim().isEmpty()) {
            int rightTextX = rightColumnX + columnWidth - fm.stringWidth(rightText);
            g.drawString(rightText, Math.max(0, rightTextX), textY);
        }
    }

    /** Map a text area line to the corresponding DiffLine object (unified mode). */
    @Nullable
    private UnifiedDiffDocument.DiffLine getDiffLineForTextLine(int textAreaLine) {
        if (unifiedDocument == null || textAreaLine < 0) {
            return null;
        }

        var filteredLines = unifiedDocument.getFilteredLines();
        if (filteredLines.isEmpty()) {
            return null;
        }

        if (textAreaLine < filteredLines.size()) {
            return filteredLines.get(textAreaLine);
        }

        logger.warn(
                "Text area line {} exceeds diff document size {} - this indicates a mapping problem",
                textAreaLine,
                filteredLines.size());
        return null;
    }

    /** Get the preferred width for the gutter component. */
    public int getPreferredWidth() {
        if (displayMode == DisplayMode.SIDE_BY_SIDE_SINGLE) {
            // Simple width calculation for side-by-side mode

            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int maxLineNumber = textArea.getLineCount();
            int maxDigits = String.valueOf(maxLineNumber).length();
            int numberWidth = fm.stringWidth("9") * Math.max(3, maxDigits); // At least 3 digits wide
            int leftPadding = 4;
            int rightPadding = 8;

            return leftPadding + numberWidth + rightPadding;
        } else {
            // Dual column width calculation for unified mode
            if (unifiedDocument == null) {
                return 80; // Default width for dual columns
            }

            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int columnWidth = fm.stringWidth("9999");
            int columnGap = 4;
            int leftPadding = 4;
            int rightPadding = 6;

            return leftPadding + columnWidth + columnGap + columnWidth + rightPadding;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getPreferredWidth();
        int height = textArea.getPreferredSize().height;
        return new Dimension(width, height);
    }
}
