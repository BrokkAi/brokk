package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class TokenUsageBar extends JComponent implements ThemeAware {

    private int currentTokens = 0;
    private int maxTokens = 1; // Avoid division by zero

    @Nullable
    private Runnable onClick = null;

    private static final float WARN_THRESHOLD = 0.5f;
    private static final float DANGER_THRESHOLD = 0.9f;

    // Hover state for highlight
    private boolean hovered = false;

    public TokenUsageBar() {
        setOpaque(false);
        setMinimumSize(new Dimension(100, 24));
        setPreferredSize(new Dimension(150, 24));
        setMaximumSize(new Dimension(250, 24));
        setToolTipText("Shows Workspace token usage.");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onClick != null && e.getButton() == MouseEvent.BUTTON1) {
                    onClick.run();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    public void setTokens(int current, int max) {
        this.currentTokens = Math.max(0, current);
        this.maxTokens = Math.max(1, max); // Ensure max is at least 1
        repaint();
    }

    public void setTooltip(String text) {
        setToolTipText(text);
    }

    public void setOnClick(@Nullable Runnable onClick) {
        this.onClick = onClick;
        setCursor(onClick != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int arc = 8;

            // Draw background track
            g2d.setColor(getTrackColor());
            g2d.fillRoundRect(0, 0, width, height, arc, arc);

            // Draw filled segment
            float ratio = (float) currentTokens / maxTokens;
            int fillWidth = (int) (width * Math.min(1.0f, ratio));
            g2d.setColor(getFillColor(ratio));
            g2d.fillRoundRect(0, 0, fillWidth, height, arc, arc);

            // Hover affordance (subtle overlay + outline). Only when clickable and enabled.
            if (hovered && isEnabled() && onClick != null) {
                // Subtle translucent overlay to "lift" the component
                g2d.setComposite(AlphaComposite.SrcOver.derive(0.10f));
                g2d.setColor(getAccentColor());
                g2d.fillRoundRect(0, 0, width, height, arc, arc);

                // Reset alpha and draw a thin rounded outline
                g2d.setComposite(AlphaComposite.SrcOver);
                g2d.setColor(getAccentColor());
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
            }

            // Draw text on top with adaptive contrast per region
            g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));
            drawText(g2d, width, height, fillWidth, ratio);
        } finally {
            g2d.dispose();
        }
    }

    private void drawText(Graphics2D g2d, int width, int height, int fillWidth, float ratio) {
        String currentText = formatTokens(currentTokens);
        String maxText = formatTokens(maxTokens);

        FontMetrics fm = g2d.getFontMetrics();
        int textHeight = fm.getAscent();
        int textY = (height - textHeight) / 2 + fm.getAscent();
        int padding = 6;

        // Background colors of each side
        Color trackBg = getTrackColor();
        Color fillBg = getFillColor(ratio);

        // Left/current text region
        int leftTextX = padding;
        int leftTextWidth = fm.stringWidth(currentText);
        int leftTextEnd = leftTextX + leftTextWidth;
        boolean leftOverFill = fillWidth >= (leftTextEnd - 1); // mostly or fully over fill
        Color leftBg = leftOverFill ? fillBg : trackBg;
        g2d.setColor(getContrastingTextColor(leftBg));
        g2d.drawString(currentText, leftTextX, textY);

        // Right/max text region
        int rightTextWidth = fm.stringWidth(maxText);
        int rightTextX = width - rightTextWidth - padding;
        int rightTextCenter = rightTextX + (rightTextWidth / 2);
        boolean rightOverFill = fillWidth > rightTextCenter; // consider dominant background
        Color rightBg = rightOverFill ? fillBg : trackBg;
        g2d.setColor(getContrastingTextColor(rightBg));
        g2d.drawString(maxText, rightTextX, textY);
    }

    private String formatTokens(int tokens) {
        if (tokens < 1000) {
            return String.valueOf(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format(Locale.US, "%.1fK", tokens / 1000.0);
        }
        return String.format(Locale.US, "%.1fM", tokens / 1_000_000.0);
    }

    private Color getFillColor(float ratio) {
        boolean dark = isDarkTheme();
        if (ratio > DANGER_THRESHOLD) {
            return getDangerColor(dark);
        }
        if (ratio > WARN_THRESHOLD) {
            return getWarningColor();
        }
        return getOkColor();
    }

    private Color getTrackColor() {
        // Prefer a panel-derived subtle track to better match theme rather than flat gray
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = isDarkTheme();
        if (panel != null) {
            return dark ? lighten(panel, 0.08f) : darken(panel, 0.06f);
        }
        Color pb = UIManager.getColor("ProgressBar.background");
        if (pb != null) return pb;
        return dark ? new Color(0x2B2B2B) : new Color(0xE6E8EA);
    }

    // Choose black or white text to maximize contrast against the provided background
    private Color getContrastingTextColor(Color background) {
        double contrastWithBlack = contrastRatio(background, Color.BLACK);
        double contrastWithWhite = contrastRatio(background, Color.WHITE);
        // Prefer the higher contrast option. If equal, default to black for light backgrounds.
        return (contrastWithWhite >= contrastWithBlack) ? Color.WHITE : Color.BLACK;
    }

    private static double contrastRatio(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color c) {
        double r = srgbToLinear(c.getRed() / 255.0);
        double g = srgbToLinear(c.getGreen() / 255.0);
        double b = srgbToLinear(c.getBlue() / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double srgbToLinear(double channel) {
        if (channel <= 0.04045) {
            return channel / 12.92;
        }
        return Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private boolean isDarkTheme() {
        return UIManager.getBoolean("laf.dark");
    }

    private Color getAccentColor() {
        Color c = UIManager.getColor("Component.focusColor");
        if (c == null) c = UIManager.getColor("Focus.color");
        if (c == null) c = UIManager.getColor("List.selectionBackground");
        if (c == null) c = new Color(0x1F6FEB); // fallback blue
        return c;
    }

    private static Color lighten(Color base, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.min(255, Math.round(base.getRed() + (255 - base.getRed()) * amount));
        int g = Math.min(255, Math.round(base.getGreen() + (255 - base.getGreen()) * amount));
        int b = Math.min(255, Math.round(base.getBlue() + (255 - base.getBlue()) * amount));
        return new Color(r, g, b);
    }

    private static Color darken(Color base, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.max(0, Math.round(base.getRed() * (1f - amount)));
        int g = Math.max(0, Math.round(base.getGreen() * (1f - amount)));
        int b = Math.max(0, Math.round(base.getBlue() * (1f - amount)));
        return new Color(r, g, b);
    }

    private Color getOkColor() {
        return new Color(0x2EA043); // green
    }

    private Color getWarningColor() {
        return new Color(0xD29922); // amber
    }

    private Color getDangerColor(boolean dark) {
        return dark ? new Color(0xC93C37) : new Color(0xDA3633); // red
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        // Colors are UIManager-based or computed from UI colors; just need to trigger a repaint
        repaint();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }
}
