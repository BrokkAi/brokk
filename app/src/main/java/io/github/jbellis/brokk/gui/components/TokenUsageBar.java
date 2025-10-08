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
        setMinimumSize(new Dimension(50, 24));
        setPreferredSize(new Dimension(75, 24));
        setMaximumSize(new Dimension(125, 24));
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

            // Draw text on top: current tokens in white, aligned to the fill's east edge
            g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));
            drawText(g2d, width, height, fillWidth);
        } finally {
            g2d.dispose();
        }
    }

    private void drawText(Graphics2D g2d, int width, int height, int fillWidth) {
        String currentText = formatTokens(currentTokens);

        FontMetrics fm = g2d.getFontMetrics();
        int textHeight = fm.getAscent();
        int textY = (height - textHeight) / 2 + fm.getAscent();
        int padding = 6;
        int textWidth = fm.stringWidth(currentText);

        int x;
        // Check if the text with padding can fit inside the filled portion of the bar
        if (fillWidth >= textWidth + 2 * padding) {
            // Yes: position text inside the filled bar, right-aligned
            x = fillWidth - textWidth - padding;
        } else {
            // No: try to position text outside the filled bar, to its right...
            if (fillWidth + padding + textWidth <= width) {
                // It fits without being clipped.
                x = fillWidth + padding;
            } else {
                // It doesn't fit outside, so place it inside without padding to avoid splitting.
                x = Math.max(0, fillWidth - textWidth);
            }
        }

        // Always white text
        g2d.setColor(Color.WHITE);
        g2d.drawString(currentText, x, textY);
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
            return getWarningColor(dark);
        }
        return getOkColor(dark);
    }

    private Color getTrackColor() {
        // Prefer a panel-derived subtle track to better match theme rather than flat gray
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = isDarkTheme();
        if (panel != null) {
            // Make the unfilled track darker on light theme to improve white text contrast at low fill levels
            return dark ? lighten(panel, 0.08f) : darken(panel, 0.18f);
        }
        Color pb = UIManager.getColor("ProgressBar.background");
        if (pb != null) return pb;
        // Fallback colors if UI defaults are missing
        return dark ? new Color(0x2B2B2B) : new Color(0xC0C4C8);
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

    private Color getOkColor(boolean dark) {
        // Use slightly different shades for better contrast in each theme
        return dark ? new Color(0x2EA043) : new Color(0x1F883D); // green
    }

    private Color getWarningColor(boolean dark) {
        return dark ? new Color(0xD29922) : new Color(0x9A6700); // amber
    }

    private Color getDangerColor(boolean dark) {
        return dark ? new Color(0xC93C37) : new Color(0xCF222E); // red
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
