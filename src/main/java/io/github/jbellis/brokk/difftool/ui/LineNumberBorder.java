package io.github.jbellis.brokk.difftool.ui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import io.github.jbellis.brokk.gui.SwingUtil;
import java.awt.*;

/**
 * LineNumberBorder is a custom border used to display line numbers in a text editor.
 * It extends EmptyBorder and is responsible for rendering the line numbers on the side
 * of the text editor while maintaining proper alignment and spacing.
 */
public class LineNumberBorder extends EmptyBorder {
    private static final Logger logger = LogManager.getLogger(LineNumberBorder.class);
    // Margin space between the line numbers and the text editor
    private static final int MARGIN = 4;

    // Reference to the associated FilePanel containing the text editor
    private final FilePanel filePanel;

    // Colors for the background and line separator
    private Color background;
    private Color lineColor;

    // Font settings for displaying line numbers
    private Font font;
    private int fontWidth;
    private int fontHeight;

    /**
     * Constructs a LineNumberBorder with the specified FilePanel.
     *
     * @param filePanel The FilePanel associated with this border.
     */
    public LineNumberBorder(FilePanel filePanel) {
        // Set the left margin width to accommodate line numbers
        super(0, 40 + MARGIN, 0, 0);

        this.filePanel = filePanel;
        init();
    }

    /**
     * Initializes font and color settings for the line number display.
     */
    private void init() {
        // Use a monospaced font for consistent number alignment
        font = new Font("Monospaced", Font.PLAIN, 10);

        // Retrieve font metrics for calculating character width and height
        FontMetrics fm = filePanel.getEditor().getFontMetrics(font);
        fontWidth     = fm.stringWidth("0"); // Width of a single character
        fontHeight    = fm.getHeight();      // Height of the font

        // Initialize the colours once; they will be refreshed on every paint call
        updateColors();
        // Ensure background and lineColor are initialized even if updateColors() doesn't set them due to some theme issue
        if (this.background == null) this.background = Color.LIGHT_GRAY;
        if (this.lineColor == null) this.lineColor = Color.DARK_GRAY;
    }

    /**
     * Refreshes background and separator colours so that they always follow
     * the current Look-and-Feel / theme.
     */
    private void updateColors() {
        var baseColor = Colors.getPanelBackground();
        lineColor     = ColorUtil.darker(baseColor);
        background    = ColorUtil.brighter(baseColor);
    }

    /**
     * Paints the background area where the line numbers will be displayed.
     *
     * @param g The Graphics object used for drawing.
     */
    public void paintBefore(Graphics g) {
        // Re-evaluate colours for the active theme
        updateColors();

        Rectangle clip = g.getClipBounds();

        // Set background color and fill the left margin area
        g.setColor(background);
        g.fillRect(0, clip.y, left - MARGIN, clip.y + clip.height);
    }

    /**
     * Paints the line numbers and the separator line.
     *
     * @param g            The Graphics object used for drawing.
     * @param startOffset  The start offset of the visible text.
     * @param endOffset    The end offset of the visible text.
     */
    public void paintAfter(Graphics g, int startOffset, int endOffset) {
        // Ensure colours match the active theme
        updateColors();

        Rectangle clip;
        int startLine, endLine;
        int y, lineHeight;
        String s;
        int heightCorrection;
        Rectangle r1;
        JTextArea textArea;

        clip = g.getClipBounds();

        try {
            // Retrieve the text area from the FilePanel
            textArea = filePanel.getEditor();

            // Determine the first and last visible line numbers
            startLine = textArea.getLineOfOffset(startOffset);
            endLine = textArea.getLineOfOffset(endOffset);

            // Get the pixel coordinates of the first visible line (modern API)
            r1 = SwingUtil.modelToView(textArea, startOffset);
            if (r1 == null) {
                 // This can happen if the text area is not yet displayable or has no content
                logger.warn("modelToView returned null for startOffset {}, cannot paint line numbers.", startOffset);
                return;
            }
            y = r1.y;
            lineHeight = r1.height;
            heightCorrection = (lineHeight - fontHeight) / 2;

            // Draw vertical separator line
            g.setColor(lineColor);
            g.drawLine(left - MARGIN, clip.y, left - MARGIN, clip.y + clip.height);

            // Set font properties for rendering line numbers
            g.setFont(font);
            g.setColor(Color.black);

            // Iterate through visible lines and draw the corresponding numbers
            for (int line = startLine; line <= endLine; line++) {
                y += lineHeight;
                s = Integer.toString(line + 1);
                g.drawString(s, left - (fontWidth * s.length()) - 1 - MARGIN, y - heightCorrection);
            }
        } catch (BadLocationException ex) {
            // This indicates an issue with offset calculation or document state
            throw new RuntimeException("Error calculating view for offset in LineNumberBorder", ex);
        }
    }
}
