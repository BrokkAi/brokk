package io.github.jbellis.brokk.gui.components;

import java.awt.*;
import javax.swing.*;

/**
 * JLabel that renders its text vertically (top-to-bottom) by drawing one character per line. This avoids the clipping
 * problems seen with long captions such as “Project Files”.
 */
public final class VerticalLabel extends JLabel {
    private static final int PAD_V = 4;
    private static final int PAD_H = 2;

    public VerticalLabel(String text) {
        super(text);
        setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
        setFont(UIManager.getFont("TabbedPane.font"));
        setHorizontalAlignment(SwingConstants.CENTER);
        setVerticalAlignment(SwingConstants.CENTER);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int stringWidth = fm.stringWidth(getText());
        int stringHeight = fm.getHeight();

        Insets insets = getInsets();
        // After rotation the logical width is the font height; height is the string width
        // Width is font height plus horizontal padding
        int width = stringHeight + PAD_H;
        int height = stringWidth + insets.top + insets.bottom;
        return new Dimension(width, height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Paint background
        if (isOpaque()) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        FontMetrics fm = g2.getFontMetrics();
        Insets insets = getInsets();

        // Rotate 90° counter-clockwise to paint text sideways
        g2.rotate(Math.toRadians(-90));
        // After rotation, translate so the text starts inside the original component bounds
        g2.translate(-getHeight() + insets.top, insets.left);

        g2.setColor(getForeground());
        g2.drawString(getText(), 0, fm.getAscent());

        g2.dispose();
    }

    /**
     * Replace each tab title with a vertically-painted label so the text reads sideways when the tabs are placed on the
     * LEFT.
     */
    public static void applyVerticalTabLabels(JTabbedPane tabbedPane) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            var title = tabbedPane.getTitleAt(i);
            var vertLabel = new VerticalLabel(title);
            tabbedPane.setTabComponentAt(i, vertLabel);
        }
    }
}
