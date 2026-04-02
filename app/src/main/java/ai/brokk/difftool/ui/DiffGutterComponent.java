package ai.brokk.difftool.ui;

import ai.brokk.difftool.ui.BlameService.BlameInfo;
import ai.brokk.difftool.ui.unified.UnifiedDiffColorResolver;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.Nullable;

/**
 * Unified gutter component for both unified diff display and side-by-side diff display. For unified mode, it shows dual
 * columns with actual source line numbers. For side-by-side mode, it shows a single column of sequential line numbers
 * with diff highlighting.
 *
 * <p>Extended to optionally render lightweight "git blame" info under the line numbers. Blame data is provided by an
 * external BlameService and set via setBlameLines(...). Rendering is controlled by setShowBlame(boolean).
 */
public class DiffGutterComponent extends JComponent {
    private static final Logger logger = LogManager.getLogger(DiffGutterComponent.class);

    // Layout constants for gutter component
    //
    // LAYOUT MATH DOCUMENTATION:
    //
    // Side-by-side mode (single column):
    //   Total Width = GUTTER_LEFT_PADDING + blameAreaWidth + numberWidth + GUTTER_RIGHT_PADDING_SIDE_BY_SIDE
    //   Where:
    //     - blameAreaWidth = stringWidth(BLAME_TOTAL_WIDTH_SAMPLE) + BLAME_AREA_PADDING (when blame enabled)
    //     - numberWidth = calculated from line count (minimum 3 digits)
    //
    // Unified mode (dual column):
    //   Total Width = GUTTER_LEFT_PADDING + blameAreaWidth + columnWidth + COLUMN_GAP + columnWidth +
    // GUTTER_RIGHT_PADDING_UNIFIED
    //   Where:
    //     - blameAreaWidth = stringWidth(BLAME_TOTAL_WIDTH_SAMPLE) + BLAME_AREA_PADDING (when blame enabled)
    //     - columnWidth = stringWidth("9999") for each line number column
    //
    // Blame rendering (when enabled):
    //   Blame is rendered to the LEFT of line numbers with layout:
    //     authorText (truncated to BLAME_AUTHOR_WIDTH_SAMPLE) + BLAME_SEPARATOR_SPACING + " · " + dateText
    //
    /** Base left padding for gutter content at 12pt font */
    private static final int BASE_GUTTER_LEFT_PADDING = 4;
    /** Base right padding for gutter in unified mode at 12pt font */
    private static final int BASE_GUTTER_RIGHT_PADDING_UNIFIED = 6;
    /** Base right padding for gutter in side-by-side mode at 12pt font */
    private static final int BASE_GUTTER_RIGHT_PADDING_SIDE_BY_SIDE = 8;
    /** Base gap between left and right columns in unified mode at 12pt font */
    private static final int BASE_COLUMN_GAP = 4;
    /** Reference font size for padding calculations (12pt) */
    private static final float REFERENCE_FONT_SIZE = 12f;

    // Blame rendering constants
    /** Maximum character width for author name display (approximately 8 characters) */
    private static final String BLAME_AUTHOR_WIDTH_SAMPLE = "12345678";
    /** Total blame area width sample (approximately 16 characters for author + date + separator) */
    private static final String BLAME_TOTAL_WIDTH_SAMPLE = "1234567890123456";
    /** Padding after blame area at reference font size (space between date and line numbers) */
    private static final int BASE_BLAME_AREA_PADDING = 12;
    /** Spacing between author and date separator */
    private static final int BLAME_SEPARATOR_SPACING = 2;
    /** Blame font size as ratio of main font (1.0 = same as main font) */
    private static final float BLAME_FONT_SIZE_RATIO = 1.0f;

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

    // ---- Blame support ----
    // Whether blame rendering is enabled for this gutter
    private volatile boolean showBlame = false;
    // Whether blame data is stale (file edited but not saved)
    private volatile boolean blameStale = false;
    // Map: 1-based line number -> BlameInfo (for right/new file)
    // Using volatile + immutable maps ensures atomic visibility for paint operations
    private volatile Map<Integer, BlameInfo> rightBlameLines = Map.of();
    // Map: 1-based line number -> BlameInfo (for left/old file, used for deletions)
    // Using volatile + immutable maps ensures atomic visibility for paint operations
    private volatile Map<Integer, BlameInfo> leftBlameLines = Map.of();
    // Font used for blame display (small, derived)
    private @Nullable Font blameFont = null;

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
        SwingUtilities.invokeLater(() -> repaint());
    }

    /** Clear the unified diff document reference. */
    public void clearUnifiedDocument() {
        this.unifiedDocument = null;
        repaint();
    }

    /**
     * Clear blame data and hide blame (useful when file changes).
     *
     * <p>Atomically replaces blame maps with empty immutable maps to ensure concurrent paint operations see a coherent
     * state.
     */
    public void clearBlame() {
        assert SwingUtilities.isEventDispatchThread() : "clearBlame must be called on EDT";
        rightBlameLines = Map.of();
        leftBlameLines = Map.of();
        showBlame = false;
        blameStale = false;
        invalidate();
        if (getParent() != null) {
            getParent().revalidate();
        }
        repaint();
    }

    /**
     * Mark blame data as stale (file has been edited but not saved).
     *
     * <p>This keeps blame visible but grayed out to indicate the line numbers may no longer match.
     */
    public void markBlameStale() {
        assert SwingUtilities.isEventDispatchThread() : "markBlameStale must be called on EDT";
        blameStale = true;
        repaint();
    }

    /** Enable or disable blame rendering in this gutter. */
    public void setShowBlame(boolean show) {
        assert SwingUtilities.isEventDispatchThread() : "setShowBlame must be called on EDT";
        if (this.showBlame != show) {
            this.showBlame = show;
            // Trigger SYNCHRONOUS resize by invalidating preferred size and forcing immediate validation
            // Using validate() instead of revalidate() ensures layout completes before caller proceeds
            // This prevents race conditions where subsequent operations trigger layout with stale preferred size
            invalidate();
            if (getParent() != null) {
                getParent().validate();
            }
            repaint();
        }
    }

    /**
     * Set blame lines for the right/new file (1-based line numbers) for rendering.
     *
     * <p>Atomically replaces the entire blame map with an immutable copy to ensure concurrent paint operations see a
     * coherent state (never a partially populated map).
     *
     * @param lines The blame data to set (non-null, may be empty)
     */
    public void setBlameLines(Map<Integer, BlameInfo> lines) {
        assert SwingUtilities.isEventDispatchThread() : "setBlameLines must be called on EDT";
        // Atomic replacement with immutable map ensures paint thread sees coherent state
        rightBlameLines = lines.isEmpty() ? Map.of() : Map.copyOf(lines);
        blameStale = false; // Fresh data, clear stale flag
        invalidate();
        if (getParent() != null) {
            getParent().validate();
        }
        repaint();
    }

    /**
     * Set blame lines for the left/old file (1-based line numbers) for rendering deletions.
     *
     * <p>Atomically replaces the entire blame map with an immutable copy to ensure concurrent paint operations see a
     * coherent state (never a partially populated map).
     *
     * @param lines The blame data to set (non-null, may be empty)
     */
    public void setLeftBlameLines(Map<Integer, BlameInfo> lines) {
        assert SwingUtilities.isEventDispatchThread() : "setLeftBlameLines must be called on EDT";
        // Atomic replacement with immutable map ensures paint thread sees coherent state
        leftBlameLines = lines.isEmpty() ? Map.of() : Map.copyOf(lines);
        blameStale = false; // Fresh data, clear stale flag
        invalidate();
        if (getParent() != null) {
            getParent().validate();
        }
        repaint();
    }

    /**
     * Get the current right/new file blame lines.
     *
     * @return Immutable map of line numbers to blame info (never null, may be empty)
     */
    public Map<Integer, BlameInfo> getRightBlameLines() {
        return rightBlameLines;
    }

    /** Set up scroll listener to ensure the gutter repaints when the text area scrolls. */
    private void setupScrollListener() {
        SwingUtilities.invokeLater(() -> {
            // Listen to text area viewport changes
            if (textArea.getParent() instanceof JViewport textAreaViewport) {
                textAreaViewport.addChangeListener(e -> repaint());
            }

            // Also listen to our own parent viewport changes (row header viewport)
            SwingUtilities.invokeLater(() -> {
                if (getParent() instanceof JViewport rowHeaderViewport) {
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
            if (textAreaViewport instanceof JViewport) {
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
                                    paintDualColumnNumbers(g, lineNumbers, lineY, fontAscent, fm, lineHeight, diffLine);
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
                    float fontSize = textArea.getFont().getSize2D();
                    int rightPadding = scalePadding(BASE_GUTTER_RIGHT_PADDING_SIDE_BY_SIDE, fontSize);
                    int leftPadding = scalePadding(BASE_GUTTER_LEFT_PADDING, fontSize);
                    int textX = gutterWidth - fm.stringWidth(lineNumber) - rightPadding;

                    g.setColor(fg);
                    g.drawString(lineNumber, Math.max(leftPadding, textX), lineY + fontAscent);

                    // Paint blame (if enabled)
                    if (showBlame) {
                        paintBlameForLine(g, documentLine + 1, lineY, lineHeight);
                    }
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

    @SuppressWarnings("UnusedVariable")
    private void paintDualColumnNumbers(
            Graphics g,
            String[] lineNumbers,
            int lineY,
            int fontAscent,
            FontMetrics fm,
            int lineHeight,
            UnifiedDiffDocument.DiffLine diffLine) {
        g.setColor(UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme));

        int textY = lineY + fontAscent;
        int gutterWidth = getWidth();
        float fontSize = textArea.getFont().getSize2D();

        int columnWidth = fm.stringWidth("9999");
        int columnGap = scalePadding(BASE_COLUMN_GAP, fontSize);
        int baseLeftPadding = scalePadding(BASE_GUTTER_LEFT_PADDING, fontSize);
        int rightPadding = scalePadding(BASE_GUTTER_RIGHT_PADDING_UNIFIED, fontSize);

        // Compute blame font metrics (will be used later if line has valid blame)
        Font blameDrawFont = null;
        FontMetrics bfm = null;
        if (showBlame) {
            blameDrawFont = (blameFont != null)
                    ? blameFont
                    : getFont().deriveFont(getFont().getSize2D() * BLAME_FONT_SIZE_RATIO);
            bfm = g.getFontMetrics(blameDrawFont);
        }

        // Reserve fixed width for blame area based on sample text
        int blameAreaWidth = 0;
        if (showBlame && bfm != null) {
            int blamePadding = scalePadding(BASE_BLAME_AREA_PADDING, fontSize);
            blameAreaWidth = bfm.stringWidth(BLAME_TOTAL_WIDTH_SAMPLE) + blamePadding;
        }

        // Left padding includes blame area width
        int leftPadding = baseLeftPadding + blameAreaWidth;

        int leftColumnX = leftPadding;
        int rightColumnX = leftPadding + columnWidth + columnGap;

        int totalNeededWidth = leftPadding + columnWidth + columnGap + columnWidth + rightPadding;
        if (gutterWidth < totalNeededWidth) {
            int centerX = gutterWidth / 2;
            leftColumnX = centerX - columnWidth - columnGap / 2;
            rightColumnX = centerX + columnGap / 2;
        }

        // Paint blame to the LEFT of line numbers, with fixed-width author column
        if (showBlame && bfm != null) {
            // Use actual file line number from DiffLine, not sequential document position
            // For deletions and context, use left blame (from HEAD)
            // For additions, use right blame (from working tree)
            boolean isAddition = diffLine.getType() == UnifiedDiffDocument.LineType.ADDITION;
            boolean isDeletion = diffLine.getType() == UnifiedDiffDocument.LineType.DELETION;
            int fileLineNumber = isAddition ? diffLine.getRightLineNumber() : diffLine.getLeftLineNumber();
            var blameMap = isAddition ? rightBlameLines : leftBlameLines;

            if (fileLineNumber > 0) {
                var info = blameMap.get(fileLineNumber);
                // Don't show blame for NOT_COMMITTED_YET on any line type
                if (info != null && !BlameService.NOT_COMMITTED_YET.equals(info.author())) {
                    Font oldFont = g.getFont();
                    g.setFont(blameDrawFont);
                    Color original = g.getColor();
                    Color tinted = isDarkTheme
                            ? original.brighter()
                            : original.darker().darker();
                    g.setColor(tinted);

                    String author = info.author();
                    String date = formatDate(info.authorTime());

                    // Draw author (truncated to fit in fixed width)
                    int authorX = baseLeftPadding;
                    int authorMaxWidth = bfm.stringWidth(BLAME_AUTHOR_WIDTH_SAMPLE);
                    String displayAuthor = truncateToWidth(author, authorMaxWidth, bfm);
                    g.drawString(displayAuthor, authorX, textY);

                    // Draw separator and date at fixed position (aligned)
                    if (!date.isBlank()) {
                        int separatorX = baseLeftPadding + authorMaxWidth + BLAME_SEPARATOR_SPACING;
                        g.drawString(" · " + date, separatorX, textY);
                    }

                    g.setColor(original);
                    g.setFont(oldFont);
                }
            }
        }

        // Paint left column (numbers)
        g.setColor(UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme));
        String leftText = lineNumbers[0];
        if (!leftText.trim().isEmpty()) {
            int leftTextX = leftColumnX + columnWidth - fm.stringWidth(leftText);
            g.drawString(leftText, Math.max(0, leftTextX), textY);
        }

        // Paint right column (numbers)
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

    /**
     * Paint the blame snippet for a given 1-based document line number (side-by-side mode).
     *
     * <p>ASSUMPTION: In side-by-side mode, the document shows the FULL file content, so document line numbers directly
     * correspond to file line numbers (1:1 mapping). If this assumption is violated (e.g., by line folding, filtering,
     * or diff-only display), blame will be displayed incorrectly.
     *
     * @param oneBasedLine The 1-based document line number (must equal file line number)
     */
    @SuppressWarnings("UnusedVariable")
    private void paintBlameForLine(Graphics g, int oneBasedLine, int lineY, int lineHeight) {
        if (!showBlame) return;

        // In side-by-side mode, document line == file line (full file is displayed)
        // If we don't find blame for this line, it might indicate the assumption is violated
        var info = rightBlameLines.get(oneBasedLine);
        if (info == null || BlameService.NOT_COMMITTED_YET.equals(info.author())) return;

        // Prepare small font lazily
        if (blameFont == null) {
            blameFont = getFont().deriveFont(getFont().getSize2D() * BLAME_FONT_SIZE_RATIO);
        }

        Font oldFont = g.getFont();
        g.setFont(blameFont);
        FontMetrics bf = g.getFontMetrics();
        FontMetrics mainFm = g.getFontMetrics(oldFont);

        String author = info.author();
        String date = formatDate(info.authorTime());

        // Position blame on same baseline as line number
        int textY = lineY + mainFm.getAscent();
        Color original = g.getColor();
        Color tinted = isDarkTheme ? original.brighter() : original.darker().darker();

        // If blame is stale (file edited but not saved), gray it out further to indicate uncertainty
        if (blameStale) {
            tinted = new Color(
                    tinted.getRed(), tinted.getGreen(), tinted.getBlue(), 80 // Much more transparent to show staleness
                    );
        }

        g.setColor(tinted);

        // Draw author (truncated to fit in fixed width)
        float fontSize = textArea.getFont().getSize2D();
        int authorX = scalePadding(BASE_GUTTER_LEFT_PADDING, fontSize);
        int authorMaxWidth = bf.stringWidth(BLAME_AUTHOR_WIDTH_SAMPLE);
        String displayAuthor = truncateToWidth(author, authorMaxWidth, bf);
        g.drawString(displayAuthor, authorX, textY);

        // Draw separator and date at fixed position (aligned)
        if (!date.isBlank()) {
            int separatorX = authorX + authorMaxWidth + BLAME_SEPARATOR_SPACING;
            g.drawString(" · " + date, separatorX, textY);
        }

        g.setColor(original);
        g.setFont(oldFont);
    }

    /** Truncate string to fit within maxWidth pixels, adding "…" if truncated */
    private String truncateToWidth(String s, int maxWidth, FontMetrics fm) {
        if (fm.stringWidth(s) <= maxWidth) {
            return s;
        }
        // Binary search for the right length
        int len = s.length();
        while (len > 0 && fm.stringWidth(s.substring(0, len) + "…") > maxWidth) {
            len--;
        }
        return len > 0 ? s.substring(0, len) + "…" : "";
    }

    /** Format timestamp as relative (≤7 days) or absolute date (>7 days). Uses 0L as sentinel for missing timestamp. */
    private String formatDate(long timestampSeconds) {
        if (timestampSeconds == 0L) {
            return "";
        }

        Instant commitTime = Instant.ofEpochSecond(timestampSeconds);
        Instant now = Instant.now();
        Duration duration = Duration.between(commitTime, now);

        long days = duration.toDays();
        long hours = duration.toHours();
        long minutes = duration.toMinutes();

        // Relative format for ≤ 7 days
        if (days <= 7) {
            if (days > 0) {
                return days + "d ago";
            } else if (hours > 0) {
                return hours + "h ago";
            } else if (minutes > 0) {
                return minutes + "m ago";
            } else {
                return "now";
            }
        }

        // Absolute format for > 7 days using locale short date
        DateTimeFormatter formatter =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
        return formatter.format(commitTime);
    }

    /**
     * Allow external callers to set the font used to render blame snippets. The gutter keeps an internal derived font
     * for blame rendering; exposing this setter makes it possible to sync the blame font with a requested editor font
     * size.
     */
    public void setBlameFont(Font f) {
        // Assign the blameFont used for rendering blame lines and refresh UI
        this.blameFont = f;
        // Ensure layout and painting update with the new font
        revalidate();
        repaint();
    }

    /**
     * Scale a padding value based on current font size relative to reference font size (12pt). Uses linear scaling so
     * padding shrinks/grows proportionally with font size.
     */
    private int scalePadding(int basePadding, float fontSize) {
        if (fontSize <= 0) return basePadding;
        float ratio = fontSize / REFERENCE_FONT_SIZE;
        return Math.max(1, Math.round(basePadding * ratio));
    }

    /** Get the preferred width for the gutter component. */
    public int getPreferredWidth() {
        float fontSize = textArea.getFont().getSize2D();

        if (displayMode == DisplayMode.SIDE_BY_SIDE_SINGLE) {
            // Simple width calculation for side-by-side mode

            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int maxLineNumber = textArea.getLineCount();
            int maxDigits = String.valueOf(maxLineNumber).length();
            // Use actual digits needed, no arbitrary minimums
            int numberWidth = fm.stringWidth("9") * maxDigits;
            int leftPadding = scalePadding(BASE_GUTTER_LEFT_PADDING, fontSize);
            int rightPadding = scalePadding(BASE_GUTTER_RIGHT_PADDING_SIDE_BY_SIDE, fontSize);

            // Reserve width for blame based on sample text
            int blameExtra = 0;
            if (showBlame) {
                Font bf = getFont().deriveFont(getFont().getSize2D() * BLAME_FONT_SIZE_RATIO);
                FontMetrics bfm = textArea.getFontMetrics(bf);
                int blamePadding = scalePadding(BASE_BLAME_AREA_PADDING, fontSize);
                blameExtra = bfm.stringWidth(BLAME_TOTAL_WIDTH_SAMPLE) + blamePadding;
            }

            return leftPadding + blameExtra + numberWidth + rightPadding;
        } else {
            // Dual column width calculation for unified mode
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int columnWidth = fm.stringWidth("9999");
            int columnGap = scalePadding(BASE_COLUMN_GAP, fontSize);
            int baseLeftPadding = scalePadding(BASE_GUTTER_LEFT_PADDING, fontSize);
            int rightPadding = scalePadding(BASE_GUTTER_RIGHT_PADDING_UNIFIED, fontSize);

            // Reserve width for blame based on sample text (even when unifiedDocument is null)
            int blameExtra = 0;
            if (showBlame) {
                Font bf = getFont().deriveFont(getFont().getSize2D() * BLAME_FONT_SIZE_RATIO);
                FontMetrics bfm = textArea.getFontMetrics(bf);
                int blamePadding = scalePadding(BASE_BLAME_AREA_PADDING, fontSize);
                blameExtra = bfm.stringWidth(BLAME_TOTAL_WIDTH_SAMPLE) + blamePadding;
            }

            return baseLeftPadding + blameExtra + columnWidth + columnGap + columnWidth + rightPadding;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getPreferredWidth();
        int height = textArea.getPreferredSize().height;
        return new Dimension(width, height);
    }
}
