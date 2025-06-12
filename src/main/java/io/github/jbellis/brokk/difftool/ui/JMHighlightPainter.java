package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.utils.Colors;
import org.jetbrains.annotations.Nullable;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * Highlight painter for diff views.
 * Instances are created dynamically with theme-appropriate colors.
 * Search highlighters remain static as they are currently theme-independent.
 */
public class JMHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {

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
            Rectangle r1 = comp.modelToView(p0);
            Rectangle r2 = comp.modelToView(p1);
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
                    // Single line fragment
                    g.fillRect(r1.x, r1.y, r2.x - r1.x, r1.height);
                } else {
                    // Multi-line fragment
                    int startLineY = r1.y;
                    int lineHeight = r1.height;
                    int totalHeight = r2.y + r2.height - startLineY;
                    int lineCount = (totalHeight + lineHeight - 1) / lineHeight; // Ceiling division

                    int y = startLineY;
                    for (int i = 0; i < lineCount; i++, y += lineHeight) {
                        int x;
                        int width;
                        if (i == 0) {
                            // First line: from start x to end of bounds
                            x = r1.x;
                            width = bounds.x + bounds.width - x; // Use component bounds for width reference
                        } else if (i == lineCount - 1) {
                            // Last line: from bounds start to end x
                            x = bounds.x;
                            width = r2.x - x;
                        } else {
                            // Middle lines: full bounds width
                            x = bounds.x;
                            width = bounds.width;
                        }
                        g.fillRect(x, y, width, lineHeight);
                    }
                }
            }
        } catch (BadLocationException ex) {
            // Should not happen with valid offsets
            System.err.println("Error painting highlight: " + ex);
        }
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
                Rectangle r1 = comp.modelToView(p0);
                Rectangle r2 = comp.modelToView(p1);
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
                System.err.println("Error painting newline highlight: " + ex);
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
                Rectangle r1 = comp.modelToView(p0);
                 if (r1 == null) return;

                g.setColor(this.color);
                int yLine = r1.y; // Draw line at the top of the starting line's view
                g.drawLine(0, yLine, bounds.x + bounds.width, yLine);
            } catch (BadLocationException ex) {
                 System.err.println("Error painting line highlight: " + ex);
            }
        }
    }

}
