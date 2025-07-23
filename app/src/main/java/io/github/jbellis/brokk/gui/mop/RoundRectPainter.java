package io.github.jbellis.brokk.gui.mop;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for drawing rounded rectangles with optional fill and stroke.
 */
final class RoundRectPainter {

    /**
     * Paints a rounded rectangle with optional fill and stroke.
     *
     * @param g2      The Graphics2D context.
     * @param x       The x coordinate of the rectangle.
     * @param y       The y coordinate of the rectangle.
     * @param w       The width of the rectangle.
     * @param h       The height of the rectangle.
     * @param radius  The corner radius (arc width and height).
     * @param fill    The fill color (null for no fill).
     * @param stroke  The stroke color (null for no stroke).
     * @param strokeW The stroke width (ignored if stroke is null).
     */
    static void paint(Graphics2D g2, int x, int y, int w, int h,
                      int radius, @Nullable Color fill, @Nullable Color stroke, int strokeW)
    {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Create the shape slightly inset if stroking, so the stroke aligns better
        // BasicStroke centers the stroke on the geometry path.
        float strokeAdjust = (strokeW > 0 && stroke != null) ? (float)strokeW / 2 : 0;
        var rr = new RoundRectangle2D.Float(x + strokeAdjust, y + strokeAdjust, 
                                            w - strokeAdjust * 2, h - strokeAdjust * 2, 
                                            radius, radius);
                                            
        // Fill first, if requested
        if (fill != null) {
            g2.setColor(fill);
            g2.fill(rr);
        }
        
        // Then stroke, if requested
        if (strokeW > 0 && stroke != null) {
            g2.setStroke(new BasicStroke(strokeW));
            g2.setColor(stroke);
            g2.draw(rr);
        }
    }

    private RoundRectPainter() {} // Prevent instantiation
}
