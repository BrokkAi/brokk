/*
 * WrapLayout – a FlowLayout that correctly reports its preferred size after the
 * components have wrapped onto multiple rows.  Based on the well-known implementation
 * by Rob Camick (public domain).
 */
package ai.brokk.gui.components;

import java.awt.*;

/**
 * A {@code FlowLayout} that wraps and whose preferred height grows to fit all rows so components below it are pushed
 * down instead of being over-painted.
 *
 * <p>Only {@link #preferredLayoutSize(Container)} is overridden; minimum size just delegates to preferred, and maximum
 * is left to the parent layout manager.
 */
public class WrapLayout extends FlowLayout {
    public WrapLayout() {
        super();
    }

    public WrapLayout(int align) {
        super(align);
    }

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            int maxWidth = target.getWidth();
            // 0 means the container hasn't been sized yet; use Integer.MAX_VALUE so every
            // component is placed on the same row and the width is computed correctly.
            if (maxWidth == 0) {
                maxWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxRowWidth = maxWidth - horizontalInsetsAndGap;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int nmembers = target.getComponentCount();

            for (int i = 0; i < nmembers; i++) {
                Component m = target.getComponent(i);

                if (!m.isVisible()) {
                    continue;
                }

                Dimension d = m.getPreferredSize();

                if (rowWidth + d.width > maxRowWidth) {
                    // start new row
                    dim.width = Math.max(dim.width, rowWidth);
                    dim.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }

                rowWidth += d.width + hgap;
                rowHeight = Math.max(rowHeight, d.height);
            }

            dim.width = Math.max(dim.width, rowWidth);
            dim.height += rowHeight;

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + vgap * 2;

            return dim;
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return preferredLayoutSize(target);
    }
}
