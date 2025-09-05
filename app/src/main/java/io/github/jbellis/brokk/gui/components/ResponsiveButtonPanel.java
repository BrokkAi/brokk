package io.github.jbellis.brokk.gui.components;

import java.awt.*;
import javax.swing.*;

/**
 * A FlowLayout-based panel that reports a preferred height tall enough to show wrapped rows of components, ensuring
 * buttons never get clipped when the parent narrows.
 */
public final class ResponsiveButtonPanel extends JPanel {
    private final int hgap;
    private final int vgap;

    public ResponsiveButtonPanel(int hgap, int vgap) {
        super(new FlowLayout(FlowLayout.LEFT, hgap, vgap));
        this.hgap = hgap;
        this.vgap = vgap;
        setOpaque(false);
    }

    @Override
    public Dimension getPreferredSize() {
        var base = super.getPreferredSize();
        int availableWidth = getAvailableWidth();
        if (availableWidth <= 0) {
            return base;
        }

        var insets = getInsets();
        int maxWidth = Math.max(0, availableWidth - insets.left - insets.right);

        int x = 0;
        int rowHeight = 0;
        int totalHeight = insets.top + insets.bottom;
        int rows = 0;

        for (Component comp : getComponents()) {
            if (!comp.isVisible()) continue;
            var pref = comp.getPreferredSize();
            int cw = pref.width;
            int ch = pref.height;

            if (x == 0) {
                // First in row
                x = cw;
                rowHeight = ch;
                rows++;
            } else if (x + hgap + cw > maxWidth) {
                // Wrap to next row
                totalHeight += rowHeight + vgap;
                x = cw;
                rowHeight = ch;
                rows++;
            } else {
                x += hgap + cw;
                rowHeight = Math.max(rowHeight, ch);
            }
        }

        if (rows > 0) {
            totalHeight += rowHeight + vgap * 2; // account for FlowLayout top and bottom vgap
        }

        int prefWidth = Math.max(base.width, availableWidth);
        return new Dimension(prefWidth, totalHeight);
    }

    private int getAvailableWidth() {
        var p = getParent();
        if (p != null) {
            return p.getWidth();
        }
        return getWidth();
    }
}
