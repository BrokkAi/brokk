package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;

/**
 * RevisionBar is the thin vertical bar at the left or right side
 * that gives a top-to-bottom overview of diff changes.
 */
public class RevisionBar extends JComponent
{
    private static final long serialVersionUID = 1L;

    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanel;
    private final boolean original; // true if showing the "original" side (left), false if "revised" side (right)

    public RevisionBar(BufferDiffPanel diffPanel, FilePanel filePanel, boolean original)
    {
        this.diffPanel = diffPanel;
        this.filePanel = filePanel;
        this.original = original;

        // Apply a border color that follows the current Look and Feel
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = UIManager.getColor("Separator.foreground");
        }
        if (borderColor == null) {
            // Fallback: derive from panel background
            borderColor = ColorUtil.darker(Colors.getPanelBackground());
        }
        setBorder(BorderFactory.createLineBorder(borderColor));
        addMouseListener(getMouseListener());
        setPreferredSize(new Dimension(20, 200));
        setMinimumSize(new Dimension(10, 100));
    }

    @Override
    public void paintComponent(Graphics g)
    {
        super.paintComponent(g);

        Rectangle r = getDrawableRectangle();
        // Use the current theme's background color
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            bg = getBackground();
        }
        g.setColor(bg);
        g.fillRect(r.x, r.y, r.width, r.height);

        Patch<String> patch = diffPanel.getPatch();
        if (patch == null) {
            return;
        }

        BufferDocumentIF doc = filePanel.getBufferDocument();
        if (doc == null) {
            return;
        }

        int numberOfLines = doc.getNumberOfLines();
        if (numberOfLines <= 0) {
            return;
        }

        // Paint each delta as a small colored block
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> chunk = original ? delta.getSource() : delta.getTarget();
            int anchor = chunk.getPosition();
            int size   = chunk.size();

            int y = r.y + (r.height * anchor) / numberOfLines;
            int height = (r.height * size) / numberOfLines;
            if (height <= 0) {
                height = 1;
            }

            g.setColor(ColorUtil.getColor(delta, diffPanel.isDarkTheme()));
            g.fillRect(r.x, y, r.width, height);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply border with theme-aware color whenever LAF changes
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = UIManager.getColor("Separator.foreground");
        }
        if (borderColor == null) {
            borderColor = ColorUtil.darker(Colors.getPanelBackground());
        }
        setBorder(BorderFactory.createLineBorder(borderColor));
    }

    /**
     * Returns the area we can actually use for painting diffs,
     * ignoring space used by the scrollbar arrows if present.
     */
    private Rectangle getDrawableRectangle()
    {
        JScrollBar sb = filePanel.getScrollPane().getVerticalScrollBar();
        Rectangle r = sb.getBounds();
        int buttonHeight = 0;

        // Attempt to subtract space for the up/down arrow buttons
        for (Component c : sb.getComponents()) {
            if (c instanceof AbstractButton) {
                buttonHeight = c.getHeight();
                break;
            }
        }
        r.x = 0;
        r.y = 0;
        r.height -= 2 * buttonHeight;
        r.y += buttonHeight;
        return r;
    }

    /**
     * Mouse click: map the Y coordinate to an approximate line,
     * then see if there's a delta anchor in a small tolerance range.
     * If so, jump to that delta. Otherwise, jump to that line directly.
     */
    private MouseListener getMouseListener()
    {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                Rectangle r = getDrawableRectangle();
                if (r.height <= 0) {
                    return;
                }

                Patch<String> patch = diffPanel.getPatch();
                if (patch == null) {
                    return;
                }

                BufferDocumentIF bd = filePanel.getBufferDocument();
                if (bd == null) {
                    return;
                }

                int numberOfLines = bd.getNumberOfLines();
                if (numberOfLines <= 0) {
                    return;
                }

                int y = e.getY() - r.y;
                // Convert to approximate line:
                int line = (y * numberOfLines) / r.height;
                if (line < 0) line = 0;
                if (line > numberOfLines) line = numberOfLines;

                // tolerance range
                int lineBefore = ((y - 3) * numberOfLines) / r.height;
                int lineAfter  = ((y + 3) * numberOfLines) / r.height;

                for (AbstractDelta<String> delta : patch.getDeltas()) {
                    Chunk<String> chunk = original ? delta.getSource() : delta.getTarget();
                    int anchor = chunk.getPosition();
                    // If anchor is in the tolerance range
                    if (anchor > lineBefore && anchor < lineAfter) {
                        // "Jump" to that delta: set it selected, then show on left side
                        diffPanel.setSelectedDelta(delta);
                        diffPanel.getScrollSynchronizer().showDelta(delta);
                        return;
                    }
                }

                // If none found, just scroll to that line
                if (original) {
                    // If this is the original side, we consider the left panel
                    diffPanel.getScrollSynchronizer().scrollToLine(
                            diffPanel.getFilePanel(BufferDiffPanel.LEFT),
                            line
                    );
                } else {
                    // Otherwise, scroll the right panel
                    diffPanel.getScrollSynchronizer().scrollToLine(
                            diffPanel.getFilePanel(BufferDiffPanel.RIGHT),
                            line
                    );
                }
            }
        };
    }
}
