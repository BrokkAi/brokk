package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

public class TokenUsageBar extends JComponent implements ThemeAware {

    private int currentTokens = 0;
    private int maxTokens = 1; // Avoid division by zero
    @Nullable
    private Runnable onClick = null;

    private static final float WARN_THRESHOLD = 0.5f;
    private static final float DANGER_THRESHOLD = 0.9f;

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
        });
        // Removed applyTheme(null) which was causing a NullAway build error.
        // Initial colors are sourced from UIManager on first paint.
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

        // Draw text
        g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));
        drawText(g2d, width, height);

        g2d.dispose();
    }

    private void drawText(Graphics2D g2d, int width, int height) {
        String currentText = formatTokens(currentTokens);
        String maxText = formatTokens(maxTokens);

        FontMetrics fm = g2d.getFontMetrics();
        int textHeight = fm.getAscent();
        int textY = (height - textHeight) / 2 + fm.getAscent();
        int padding = 6;

        g2d.setColor(getForegroundColor());

        // Draw current tokens on the left
        g2d.drawString(currentText, padding, textY);

        // Draw max tokens on the right
        int maxTextWidth = fm.stringWidth(maxText);
        g2d.drawString(maxText, width - maxTextWidth - padding, textY);
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
        var color = UIManager.getColor("ProgressBar.background");
        return color == null ? Color.LIGHT_GRAY : color;
    }

    private Color getForegroundColor() {
        var color = UIManager.getColor("ProgressBar.foreground");
        return color == null ? Color.BLACK : color;
    }

    private boolean isDarkTheme() {
        return UIManager.getBoolean("laf.dark");
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
        // Colors are UIManager-based, so just need to trigger a repaint
        repaint();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }
}
