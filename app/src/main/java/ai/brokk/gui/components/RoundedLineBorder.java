package ai.brokk.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.border.AbstractBorder;

public class RoundedLineBorder extends AbstractBorder {
    private final Color color;
    private final int thickness;
    private final int arc; // if < 0, compute from component height for a pill-like shape

    public RoundedLineBorder(Color color, int thickness, int arc) {
        this.color = color;
        this.thickness = Math.max(1, thickness);
        this.arc = arc;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(thickness, thickness, thickness, thickness);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.top = insets.left = insets.bottom = insets.right = thickness;
        return insets;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thickness));

            float stroke = thickness;
            float offset = stroke / 2f;
            float w = width - stroke;
            float h = height - stroke;

            int arcwh = (arc > 0) ? arc : Math.round(Math.min(w, h)); // pill-like when arc >= height
            RoundRectangle2D.Float rr = new RoundRectangle2D.Float(x + offset, y + offset, w, h, arcwh, arcwh);
            g2.draw(rr);
        } finally {
            g2.dispose();
        }
    }
}
