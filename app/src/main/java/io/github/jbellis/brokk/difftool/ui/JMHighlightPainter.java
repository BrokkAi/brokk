package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.utils.Colors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import io.github.jbellis.brokk.gui.SwingUtil;
import java.awt.*;

/**
 * Highlight painter for diff views.
 * Instances are created dynamically with theme-appropriate colors.
 * Search highlighters remain static as they are currently theme-independent.
 */
public class JMHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {
    private static final Logger logger = LogManager.getLogger(JMHighlightPainter.class);

    // Performance thresholds for highlighting optimizations
    private static final int LONG_LINE_THRESHOLD = 10000;
    private static final int LARGE_DOCUMENT_THRESHOLD = 1000000;

    // Static painters for Search (currently theme-independent)
    public static final JMHighlightPainter SEARCH;
    public static final JMHighlightPainter CURRENT_SEARCH;

    static {
        SEARCH = new JMHighlightPainter(Colors.SEARCH);
        CURRENT_SEARCH = new JMHighlightPainter(Colors.CURRENT_SEARCH);
    }

    protected final Color color;
    protected final boolean paintFullLine;

    /**
     * Creates a painter for a specific color, painting only the text bounds.
     * @param color The highlight color.
     */
    public JMHighlightPainter(Color color) {
        this(color, false);
    }

    /**
     * Creates a painter for a specific color.
     * @param color The highlight color.
     * @param paintFullLine If true, paints the entire line background; otherwise, paints only the text bounds.
     */
    protected JMHighlightPainter(Color color, boolean paintFullLine) {
        super(color); // Pass color to super, though we override paint
        this.color = color;
        this.paintFullLine = paintFullLine;
    }

    @Override
    public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
        Rectangle bounds = shape.getBounds();
        try {
            Rectangle r1 = SwingUtil.modelToView(comp, p0);
            Rectangle r2 = SwingUtil.modelToView(comp, p1);
            if (r1 == null || r2 == null) {
                return; // Avoid NPE if modelToView returns null
            }

            g.setColor(this.color);

            if (paintFullLine) {
                int y = r1.y;
                int height = r1.height;
                // If the highlight spans multiple lines, adjust height
                if (r1.y != r2.y) {
                    height = r2.y + r2.height - r1.y;
                }
                g.fillRect(0, y, bounds.x + bounds.width, height);
            } else {
                // Paint within text bounds (potentially multi-line)
                if (r1.y == r2.y) {
                    // Single line fragment - check for very long line case
                    int highlightRange = p1 - p0;
                    if (highlightRange > LONG_LINE_THRESHOLD) {
                        // Very long single line - use viewport-aware highlighting
                        paintLongSingleLine(g, r1, r2, comp);
                    } else {
                        // Normal single line highlighting
                        g.fillRect(r1.x, r1.y, r2.x - r1.x, r1.height);
                    }
                } else {
                    // Multi-line fragment - use optimized approach with performance safeguards
                    // First line: from start x to end of bounds
                    g.fillRect(r1.x, r1.y, bounds.x + bounds.width - r1.x, r1.height);

                    // Check if the range is reasonable for enhanced highlighting
                    int highlightRange = p1 - p0;
                    int docLength = comp.getDocument().getLength();
                    boolean useEnhancedHighlighting = highlightRange < LONG_LINE_THRESHOLD && docLength < LARGE_DOCUMENT_THRESHOLD;

                    if (useEnhancedHighlighting) {
                        try {
                            // Get only the text range we need, not the entire document
                            int textStart = Math.max(0, p0);
                            int textEnd = Math.min(docLength, p1 + 100); // Small buffer for safety
                            String text = comp.getDocument().getText(textStart, textEnd - textStart);

                            int currentOffset = p0;
                            int relativeOffset = 0; // Offset within the extracted text

                            // Find newlines in the extracted text (handle different line endings)
                            while (relativeOffset < text.length() && currentOffset < p1) {
                                int nextNewline = -1;

                                // Find next line ending (\n, \r\n, or \r)
                                for (int i = relativeOffset; i < text.length(); i++) {
                                    char c = text.charAt(i);
                                    if (c == '\n' || c == '\r') {
                                        nextNewline = i;
                                        break;
                                    }
                                }

                                if (nextNewline == -1) break; // No more newlines

                                // Calculate the actual document offset
                                int nextLineStart = textStart + nextNewline + 1;
                                if (text.charAt(nextNewline) == '\r' && nextNewline + 1 < text.length() && text.charAt(nextNewline + 1) == '\n') {
                                    nextLineStart++; // Skip \r\n
                                }

                                if (nextLineStart >= p1) break;

                                // Find the end of this line or the highlight end
                                int nextLineEnd = p1;
                                for (int i = nextNewline + 1; i < text.length(); i++) {
                                    char c = text.charAt(i);
                                    if (c == '\n' || c == '\r') {
                                        nextLineEnd = Math.min(textStart + i, p1);
                                        break;
                                    }
                                }

                                // Get the actual view rectangle for this line
                                Rectangle lineStart = SwingUtil.modelToView(comp, nextLineStart);
                                if (lineStart != null) {
                                    if (nextLineEnd == p1) {
                                        // Last line: from bounds start to end x
                                        g.fillRect(bounds.x, lineStart.y, r2.x - bounds.x, lineStart.height);
                                    } else {
                                        // Middle line: full bounds width
                                        g.fillRect(bounds.x, lineStart.y, bounds.width, lineStart.height);
                                    }
                                }

                                currentOffset = nextLineStart;
                                relativeOffset = nextLineStart - textStart;
                            }
                        } catch (BadLocationException ex) {
                            useEnhancedHighlighting = false; // Fall back to arithmetic method
                        }
                    }

                    if (!useEnhancedHighlighting) {
                        // Fallback to arithmetic calculation for large ranges or on error
                        int startLineY = r1.y;
                        int lineHeight = r1.height;
                        int totalHeight = r2.y + r2.height - startLineY;
                        int lineCount = Math.max(1, (totalHeight + lineHeight - 1) / lineHeight);

                        int y = startLineY + lineHeight; // Skip first line already painted
                        for (int i = 1; i < lineCount; i++, y += lineHeight) {
                            int x = bounds.x;
                            int width = (i == lineCount - 1) ? r2.x - x : bounds.width;
                            g.fillRect(x, y, width, lineHeight);
                        }
                    }
                }
            }
        } catch (BadLocationException ex) {
            // Should not happen with valid offsets
            logger.warn("Error painting highlight: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Paint highlighting for very long single lines using viewport-aware approach.
     * Only highlights the visible portion of the line to avoid performance issues.
     */
    private void paintLongSingleLine(Graphics g, Rectangle r1, Rectangle r2, JTextComponent comp) {
        // Get viewport bounds to determine what's actually visible
        Rectangle visibleRect = comp.getVisibleRect();

        // Calculate the intersection of the highlight area with the visible viewport
        int highlightStartX = r1.x;
        int highlightEndX = r2.x;

        // Clamp to visible area - only paint what's actually visible
        int visibleStartX = Math.max(highlightStartX, visibleRect.x);
        int visibleEndX = Math.min(highlightEndX, visibleRect.x + visibleRect.width);

        // Only paint if there's a visible intersection
        if (visibleStartX < visibleEndX) {
            g.fillRect(visibleStartX, r1.y, visibleEndX - visibleStartX, r1.height);

            // Add visual indicators for long line highlighting
            addLongLineIndicators(g, r1, visibleRect, highlightStartX, highlightEndX);
        }
    }

    /**
     * Add visual indicators to help users understand when long line highlighting is active.
     * Shows subtle indicators at the edges when highlighting extends beyond the viewport.
     */
    private void addLongLineIndicators(Graphics g, Rectangle lineRect, Rectangle visibleRect,
                                     int highlightStartX, int highlightEndX) {
        Color originalColor = g.getColor();

        // Use a slightly darker version of the highlight color for indicators
        Color indicatorColor = color.darker();
        g.setColor(indicatorColor);

        // Left edge indicator - show if highlighting starts before visible area
        if (highlightStartX < visibleRect.x) {
            int indicatorX = visibleRect.x;
            // Draw a subtle vertical line to indicate more content to the left
            g.drawLine(indicatorX, lineRect.y, indicatorX, lineRect.y + lineRect.height - 1);
            g.drawLine(indicatorX + 1, lineRect.y, indicatorX + 1, lineRect.y + lineRect.height - 1);
        }

        // Right edge indicator - show if highlighting extends beyond visible area
        if (highlightEndX > visibleRect.x + visibleRect.width) {
            int indicatorX = visibleRect.x + visibleRect.width - 2;
            // Draw a subtle vertical line to indicate more content to the right
            g.drawLine(indicatorX, lineRect.y, indicatorX, lineRect.y + lineRect.height - 1);
            g.drawLine(indicatorX + 1, lineRect.y, indicatorX + 1, lineRect.y + lineRect.height - 1);
        }

        g.setColor(originalColor);
    }

    // --- Subclasses for Specific Paint Styles ---

    /**
     * Painter that highlights the full line background and visually adds a newline space at the end.
     * (Simulates the effect of a highlighted newline character).
     */
    public static class JMHighlightNewLinePainter extends JMHighlightPainter {

        public JMHighlightNewLinePainter(Color color) {
            // Always paints the full line for this type
            super(color, true);
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
            Rectangle bounds = shape.getBounds();
            try {
                Rectangle r1 = SwingUtil.modelToView(comp, p0);
                Rectangle r2 = SwingUtil.modelToView(comp, p1);
                if (r1 == null || r2 == null) return;

                g.setColor(this.color);

                int y = r1.y;
                int height = r1.height;
                // If the highlight spans multiple lines, adjust height
                if (r1.y != r2.y) {
                    height = r2.y + r2.height - r1.y;
                }
                // Add extra height to simulate the newline
                height += r1.height;
                g.fillRect(0, y, bounds.x + bounds.width, height);

            } catch (BadLocationException ex) {
                logger.warn("Error painting newline highlight: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * Painter that draws a horizontal line at the start offset's line.
     * Useful for indicating deleted lines without filling the background.
     */
    public static class JMHighlightLinePainter extends JMHighlightPainter {

        public JMHighlightLinePainter(Color color) {
            // Doesn't paint background fill, uses its own paint logic
            super(color, false);
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
            Rectangle bounds = shape.getBounds();
            try {
                Rectangle r1 = SwingUtil.modelToView(comp, p0);
                 if (r1 == null) return;

                g.setColor(this.color);
                int yLine = r1.y; // Draw line at the top of the starting line's view
                g.drawLine(0, yLine, bounds.x + bounds.width, yLine);
            } catch (BadLocationException ex) {
                logger.warn("Error painting line highlight: {}", ex.getMessage(), ex);
            }
        }
    }

}
