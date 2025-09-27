package io.github.jbellis.brokk.difftool.ui.unified;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.Nullable;

/**
 * Custom line number component for unified diff display that shows actual source line numbers instead of sequential
 * document line numbers. This class handles different line types appropriately: context and addition lines show right
 * line numbers, deletion lines show left line numbers, and header lines show no numbers.
 */
public class UnifiedDiffLineNumberList extends JComponent {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffLineNumberList.class);

    @Nullable
    private UnifiedDiffDocument unifiedDocument;

    private final RSyntaxTextArea textArea;
    private boolean isDarkTheme = false;
    private UnifiedDiffDocument.ContextMode contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
    private static int paintSequence = 0;

    /**
     * Create a unified diff line number list.
     *
     * @param textArea The text area to provide line numbers for
     */
    public UnifiedDiffLineNumberList(RSyntaxTextArea textArea) {
        this.textArea = textArea;
        setOpaque(true);
        // Use theme-aware colors from utility
        setBackground(UnifiedDiffColorResolver.getDefaultGutterBackground(isDarkTheme));
        setForeground(UnifiedDiffColorResolver.getDefaultGutterForeground(isDarkTheme));

        // Add scroll listener to ensure we repaint when the text area scrolls
        setupScrollListener();

        logger.debug("Created UnifiedDiffLineNumberList for unified diff display");
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
     * Set the context mode for coordinate calculation optimization.
     *
     * @param contextMode The context mode (STANDARD_3_LINES or FULL_CONTEXT)
     */
    public void setContextMode(UnifiedDiffDocument.ContextMode contextMode) {
        if (this.contextMode != contextMode) {
            logger.debug("Context mode changed from {} to {} in line number component", this.contextMode, contextMode);
            this.contextMode = contextMode;
            repaint(); // Repaint since coordinate calculations may change
        }
    }

    /**
     * Set the unified diff document to use for line number lookup.
     *
     * @param document The unified diff document containing line metadata
     */
    public void setUnifiedDocument(UnifiedDiffDocument document) {
        this.unifiedDocument = document;
        logger.debug("Set unified document for line number lookup");
        // Force immediate repaint to ensure line numbers are updated
        javax.swing.SwingUtilities.invokeLater(() -> repaint());
    }

    /** Clear the unified diff document reference. */
    public void clearUnifiedDocument() {
        this.unifiedDocument = null;
        logger.debug("Cleared unified document reference");
        repaint(); // Refresh display
    }

    /**
     * Set up scroll listener to ensure the gutter repaints when the text area scrolls. This is crucial for maintaining
     * synchronization during scroll events.
     */
    private void setupScrollListener() {
        // For a row header component in JScrollPane, we need to listen for scroll events differently
        // We'll add listeners both to the text area viewport and to our own parent viewport
        javax.swing.SwingUtilities.invokeLater(() -> {
            // Listen to text area viewport changes
            if (textArea.getParent() instanceof javax.swing.JViewport textAreaViewport) {
                textAreaViewport.addChangeListener(e -> {
                    logger.trace("Text area viewport change detected, triggering gutter repaint");
                    repaint();
                });
                logger.debug("Scroll listener attached to text area viewport");
            }

            // Also listen to our own parent viewport changes (row header viewport)
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (getParent() instanceof javax.swing.JViewport rowHeaderViewport) {
                    rowHeaderViewport.addChangeListener(e -> {
                        logger.trace("Row header viewport change detected, triggering gutter repaint");
                        repaint();
                    });
                    logger.debug("Scroll listener attached to row header viewport");
                } else {
                    logger.debug("Row header parent is not a JViewport, listener not attached");
                }
            });
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        logger.debug("paintComponent called - component size: {}x{}", getWidth(), getHeight());

        if (unifiedDocument == null) {
            logger.debug("No unified document set, using default painting");
            // Fallback to default behavior if no unified document is set
            super.paintComponent(g);
            return;
        }

        // Custom painting logic for unified diff line numbers
        paintUnifiedDiffLineNumbers(g);
    }

    /**
     * Paint line numbers specific to unified diff content. Shows actual source line numbers instead of sequential
     * numbers. Optimized for JScrollPane row header viewport synchronization.
     */
    private void paintUnifiedDiffLineNumbers(Graphics g) {
        int currentPaint = ++paintSequence;
        logger.debug("paintUnifiedDiffLineNumbers #{} starting", currentPaint);

        if (textArea == null) {
            logger.debug("paintUnifiedDiffLineNumbers #{} - textArea is null", currentPaint);
            return;
        }

        var clipBounds = g.getClipBounds();
        if (clipBounds == null || clipBounds.isEmpty()) {
            logger.debug("paintUnifiedDiffLineNumbers #{} - clipBounds is null/empty", currentPaint);
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

        // Fill default background first - this will be overridden by line-specific backgrounds
        g.setColor(bg);
        g.fillRect(clipBounds.x, clipBounds.y, clipBounds.width, clipBounds.height);
        g.setColor(fg);

        FontMetrics fm = g.getFontMetrics();
        int fontAscent = fm.getAscent();

        try {
            // For row header in JScrollPane, the clipBounds are already in the correct coordinate space
            // The JScrollPane automatically synchronizes the row header with the main viewport
            var textAreaViewport = textArea.getParent();
            if (textAreaViewport instanceof javax.swing.JViewport viewport) {
                var viewPosition = viewport.getViewPosition();

                logger.debug(
                        "Scroll Debug: clipBounds=({},{}) size={}x{}, viewPosition=({},{})",
                        clipBounds.x,
                        clipBounds.y,
                        clipBounds.width,
                        clipBounds.height,
                        viewPosition.x,
                        viewPosition.y);

                // Fix synchronization: Use row header clipBounds directly in text area coordinate space
                // For JScrollPane row header, clipBounds.y should already correspond to text area coordinates

                // The key insight: clipBounds.y in row header = absolute text area Y position
                int textAreaStartY = clipBounds.y;
                int textAreaEndY = clipBounds.y + clipBounds.height;

                logger.debug(
                        "Paint #{}: Direct mapping - clipBounds.y={} -> textAreaStartY={} (no viewPosition offset)",
                        currentPaint,
                        clipBounds.y,
                        textAreaStartY);

                int startOffset = textArea.viewToModel2D(new java.awt.Point(0, textAreaStartY));
                int endOffset = textArea.viewToModel2D(new java.awt.Point(0, textAreaEndY));

                int startLine = textArea.getLineOfOffset(startOffset);
                int endLine = textArea.getLineOfOffset(endOffset);

                // Ensure bounds are valid for both text area and unified document
                startLine = Math.max(0, startLine);
                endLine = Math.min(textArea.getLineCount() - 1, endLine);

                // Also limit to actual unified document size to avoid null getDiffLine() calls
                if (unifiedDocument != null && unifiedDocument.getFilteredLines() != null) {
                    int diffDocumentLines = unifiedDocument.getFilteredLines().size();
                    endLine = Math.min(endLine, diffDocumentLines - 1);
                    logger.debug(
                            "Paint #{}: Limited endLine to {} based on diff document size ({})",
                            currentPaint,
                            endLine,
                            diffDocumentLines);
                }

                logger.debug(
                        "Paint #{}: Visible lines: {} to {} (offsets: {} to {}), clipBounds={}x{} at ({},{})",
                        currentPaint,
                        startLine,
                        endLine,
                        startOffset,
                        endOffset,
                        clipBounds.width,
                        clipBounds.height,
                        clipBounds.x,
                        clipBounds.y);

                // Paint line numbers for visible lines with color-coded backgrounds
                // We need to map text area lines to DiffLine objects correctly
                // since OMITTED_LINES entries can cause text lines to not match DiffLine indices 1:1
                for (int documentLine = startLine; documentLine <= endLine; documentLine++) {
                    if (unifiedDocument != null) {
                        var diffLine = getDiffLineForTextLine(documentLine);

                        // Special logging around line 439 to debug the "1 439" issue
                        if (documentLine >= 435 && documentLine <= 445) {
                            logger.info(
                                    "Paint #{} - CRITICAL LINE {}: diffLine={}, type={}, left={}, right={}, contextMode={}",
                                    currentPaint,
                                    documentLine,
                                    diffLine != null ? "exists" : "NULL",
                                    diffLine != null ? diffLine.getType() : "N/A",
                                    diffLine != null ? diffLine.getLeftLineNumber() : "N/A",
                                    diffLine != null ? diffLine.getRightLineNumber() : "N/A",
                                    contextMode);
                        }

                        if (diffLine != null) {
                            // Calculate line position in text area coordinates
                            var lineStartOffset = textArea.getLineStartOffset(documentLine);
                            var lineRect = textArea.modelToView2D(lineStartOffset);

                            if (lineRect != null) {
                                // For row header, use text area coordinates directly
                                // JScrollPane automatically handles the coordinate transformation
                                int textAreaLineY = (int) lineRect.getY();
                                int lineY = textAreaLineY; // No viewport offset needed for row header
                                int lineHeight = (int) lineRect.getHeight();

                                // Special handling for OMITTED_LINES in STANDARD_3_LINES mode
                                // These represent gaps in the source and may need coordinate adjustments
                                if (contextMode == UnifiedDiffDocument.ContextMode.STANDARD_3_LINES
                                        && diffLine.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES) {
                                    logger.trace(
                                            "Line {}: OMITTED_LINES detected in STANDARD_3_LINES mode, applying gap handling",
                                            documentLine);
                                    // OMITTED_LINES represent visual gaps but need to maintain synchronization
                                    // The coordinate calculation should remain the same, but we log this for awareness
                                }

                                logger.trace(
                                        "Line {}: type={}, textAreaY={}, rowHeaderY={}, height={}, clipBounds={}-{}, contextMode={}",
                                        documentLine,
                                        diffLine.getType(),
                                        textAreaLineY,
                                        lineY,
                                        lineHeight,
                                        clipBounds.y,
                                        clipBounds.y + clipBounds.height,
                                        contextMode);

                                // Remove strict visibility check to avoid coordinate drift issues
                                // Since we already use broad range (500px buffers), let graphics clipping handle
                                // visibility
                                // Add coordinate drift detection logging for high line numbers
                                if (documentLine > 1000 && (lineY < -100 || lineY > getHeight() + 100)) {
                                    logger.warn(
                                            "Potential coordinate drift detected: line {} has rowHeaderY={} (componentHeight={})",
                                            documentLine,
                                            lineY,
                                            getHeight());
                                }

                                logger.trace(
                                        "Line {} painting (lineY={}, height={}, clipBounds={}-{}) - no strict visibility check",
                                        documentLine,
                                        lineY,
                                        lineHeight,
                                        clipBounds.y,
                                        clipBounds.y + clipBounds.height);

                                // Use more forgiving coordinate validation to prevent drift issues
                                // Only skip lines that are extremely far outside reasonable bounds
                                boolean coordsReasonable =
                                        (lineY > -1000 && lineY < getHeight() + 1000 && lineHeight > 0);
                                if (!coordsReasonable) {
                                    logger.info(
                                            "Line {} EXTREME COORDINATES, skipping (lineY={}, height={}, componentHeight={})",
                                            documentLine,
                                            lineY,
                                            lineHeight,
                                            getHeight());
                                    continue;
                                }

                                // Paint background color based on line type
                                paintLineBackground(g, documentLine, lineY, lineHeight);

                                // Format and paint line numbers (now handles all line types)
                                var lineNumbers = formatLineNumbers(diffLine);
                                if (lineNumbers != null) {
                                    // Paint dual column line numbers
                                    paintDualColumnNumbers(g, lineNumbers, lineY, fontAscent, fm);
                                } else {
                                    logger.trace("Line {}: formatLineNumbers returned null", documentLine);
                                }
                            } else {
                                logger.trace("Line {}: modelToView2D returned null", documentLine);
                            }
                        } else {
                            // If we still get null after bounds checking, this indicates a real end of document
                            logger.debug(
                                    "Paint #{} - Line {}: getDiffLine returned NULL (beyond diff document)",
                                    currentPaint,
                                    documentLine);
                            // Break out of the loop since we've reached the end of the diff document
                            break;
                        }
                    } else {
                        logger.info("Paint #{} - unifiedDocument is NULL at line {}", currentPaint, documentLine);
                    }
                }

                logger.debug("Paint #{} - Finished painting lines {} to {}", currentPaint, startLine, endLine);
            }

        } catch (BadLocationException e) {
            logger.warn("Paint #{} - Error painting unified diff line numbers: {}", currentPaint, e.getMessage());
        }

        logger.debug("paintUnifiedDiffLineNumbers #{} completed", currentPaint);
    }

    /**
     * Format line numbers for display using GitHub-style dual column approach. Shows both left and right line numbers
     * in their respective columns.
     *
     * @param diffLine The diff line containing line number information
     * @return Array of [leftText, rightText] for dual column display
     */
    @Nullable
    private String[] formatLineNumbers(UnifiedDiffDocument.DiffLine diffLine) {
        int leftLine = diffLine.getLeftLineNumber();
        int rightLine = diffLine.getRightLineNumber();

        return switch (diffLine.getType()) {
            case CONTEXT -> {
                // Context lines (unchanged): show both line numbers
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case ADDITION -> {
                // Addition lines: show only right line number
                String leftText = "    "; // Empty left column
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case DELETION -> {
                // Deletion lines: show only left line number
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = "    "; // Empty right column
                yield new String[] {leftText, rightText};
            }
            case HEADER -> {
                // Header lines: show empty line numbers but still paint the component
                yield new String[] {"    ", "    "};
            }
            case OMITTED_LINES -> {
                // Omitted lines indicator: show empty line numbers since this represents a gap
                // The actual line numbers will resume properly on the next real content line
                yield new String[] {"    ", "    "};
            }
        };
    }

    /**
     * Paint the background color for a line based on its diff type.
     *
     * @param g Graphics context
     * @param documentLine Document line number
     * @param lineY Y position of the line
     * @param lineHeight Height of the line
     */
    private void paintLineBackground(Graphics g, int documentLine, int lineY, int lineHeight) {
        if (unifiedDocument == null) {
            return;
        }

        var diffLine = unifiedDocument.getDiffLine(documentLine);
        if (diffLine == null) {
            return;
        }

        Color backgroundColor = UnifiedDiffColorResolver.getEnhancedBackgroundColor(diffLine.getType(), isDarkTheme);

        if (backgroundColor != null) {
            g.setColor(backgroundColor);
            g.fillRect(0, lineY, getWidth(), lineHeight);
            logger.trace(
                    "Painted background for line {} type {} with color {} at rect(0,{},{},{})",
                    documentLine,
                    diffLine.getType(),
                    backgroundColor,
                    lineY,
                    getWidth(),
                    lineHeight);
        } else {
            logger.trace("No background color for line {} type {} (using default)", documentLine, diffLine.getType());
        }
    }

    /**
     * Paint dual column line numbers in GitHub style. Left column for original file, right column for modified file.
     *
     * @param g Graphics context
     * @param lineNumbers Array of [leftText, rightText]
     * @param lineY Y position of the line
     * @param fontAscent Font ascent for text positioning
     * @param fm Font metrics for measuring text
     */
    private void paintDualColumnNumbers(Graphics g, String[] lineNumbers, int lineY, int fontAscent, FontMetrics fm) {
        g.setColor(UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme));

        int textY = lineY + fontAscent;
        int gutterWidth = getWidth();

        // Calculate column positions with more generous spacing
        int columnWidth = fm.stringWidth("9999"); // Space for 4-digit numbers
        int columnGap = 4; // Gap between columns
        int leftPadding = 4; // Left edge padding
        int rightPadding = 6; // Right edge padding

        // Position columns: [leftPadding][leftColumn][gap][rightColumn][rightPadding]
        int leftColumnX = leftPadding;
        int rightColumnX = leftPadding + columnWidth + columnGap;

        // Ensure we have enough space
        int totalNeededWidth = leftPadding + columnWidth + columnGap + columnWidth + rightPadding;
        if (gutterWidth < totalNeededWidth) {
            // Fallback to simpler layout if not enough space
            int centerX = gutterWidth / 2;
            leftColumnX = centerX - columnWidth - columnGap / 2;
            rightColumnX = centerX + columnGap / 2;
        }

        // Paint left column (original file line number)
        String leftText = lineNumbers[0];
        if (!leftText.trim().isEmpty()) {
            int leftTextX = leftColumnX + columnWidth - fm.stringWidth(leftText); // Right-align in column
            g.drawString(leftText, Math.max(0, leftTextX), textY);
        }

        // Paint right column (modified file line number)
        String rightText = lineNumbers[1];
        if (!rightText.trim().isEmpty()) {
            int rightTextX = rightColumnX + columnWidth - fm.stringWidth(rightText); // Right-align in column
            g.drawString(rightText, Math.max(0, rightTextX), textY);
        }
    }

    /**
     * Map a text area line to the corresponding DiffLine object, accounting for OMITTED_LINES
     * that may have been inserted as actual text content.
     *
     * @param textAreaLine The 0-based line number in the text area
     * @return The corresponding DiffLine, or null if not found
     */
    @Nullable
    private UnifiedDiffDocument.DiffLine getDiffLineForTextLine(int textAreaLine) {
        if (unifiedDocument == null || textAreaLine < 0) {
            return null;
        }

        var filteredLines = unifiedDocument.getFilteredLines();
        if (filteredLines == null || filteredLines.isEmpty()) {
            return null;
        }

        // Debug logging to understand the mapping issue
        if (textAreaLine < 10 || textAreaLine > filteredLines.size() - 10) {
            logger.info("MAPPING DEBUG: textAreaLine={}, filteredLines.size()={}, textArea.getLineCount()={}",
                textAreaLine, filteredLines.size(), textArea != null ? textArea.getLineCount() : "null");
        }

        // The mapping should be 1:1 since we build text from filteredLines
        if (textAreaLine < filteredLines.size()) {
            var diffLine = filteredLines.get(textAreaLine);

            // Log OMITTED_LINES specifically to understand the issue
            if (diffLine.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES) {
                logger.info("OMITTED_LINES found at textAreaLine {}: content='{}'",
                    textAreaLine, diffLine.getContent());
            }

            return diffLine;
        }

        // Handle case where text area has more lines than diff document
        logger.warn("Text area line {} exceeds diff document size {} - this indicates a mapping problem",
            textAreaLine, filteredLines.size());
        return null;
    }

    /**
     * Get the preferred width for the line number gutter. Calculates based on the maximum line number that might be
     * displayed.
     */
    public int getPreferredWidth() {
        if (unifiedDocument == null) {
            return 50; // Default width
        }

        if (textArea == null) {
            return 50; // Default width
        }

        // Find the maximum line number we might display
        int maxLineNumber = 1;
        var filteredLines = unifiedDocument.getFilteredLines();

        for (var diffLine : filteredLines) {
            int leftLine = diffLine.getLeftLineNumber();
            int rightLine = diffLine.getRightLineNumber();

            if (leftLine > maxLineNumber) {
                maxLineNumber = leftLine;
            }
            if (rightLine > maxLineNumber) {
                maxLineNumber = rightLine;
            }
        }

        // Calculate width needed for the GitHub-style dual column format
        FontMetrics fm = textArea.getFontMetrics(textArea.getFont());

        // Width for each column (4-digit numbers)
        int columnWidth = fm.stringWidth("9999");
        int columnGap = 4; // Gap between columns
        int leftPadding = 4; // Left edge padding
        int rightPadding = 6; // Right edge padding

        // Total width: [leftPadding][leftColumn][gap][rightColumn][rightPadding]
        return leftPadding + columnWidth + columnGap + columnWidth + rightPadding;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getPreferredWidth();
        int height = textArea != null ? textArea.getPreferredSize().height : 100;
        return new Dimension(width, height);
    }
}
