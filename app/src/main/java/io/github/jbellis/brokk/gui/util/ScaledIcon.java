package io.github.jbellis.brokk.gui.util;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

/**
 * Icon wrapper that paints its delegate scaled by the given factor.
 */
public final class ScaledIcon implements Icon {
    private final Icon delegate;
    private final double factor;
    private final int width;
    private final int height;

    public ScaledIcon(Icon delegate, double factor)
    {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.factor = factor;
        this.width = (int) Math.round(delegate.getIconWidth() * factor);
        this.height = (int) Math.round(delegate.getIconHeight() * factor);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(x, y);
            g2.scale(factor, factor);
            delegate.paintIcon(c, g2, 0, 0);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }
}
