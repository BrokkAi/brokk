package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.theme.GuiTheme;
import java.awt.*;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * A composite Icon that displays a base icon with an optional badge overlay in the bottom-right corner. The badge
 * displays a count number in a circle with white text for maximum readability. Colors dynamically adjust based on the
 * current theme.
 */
public class BadgedIcon implements Icon {
    private final Icon baseIcon;
    private int count;
    private final GuiTheme themeManager;

    @Nullable
    private Font cachedBadgeFont;

    /** Creates a new BadgedIcon with the specified base icon. */
    public BadgedIcon(Icon baseIcon, GuiTheme themeManager) {
        this.baseIcon = baseIcon;
        this.count = 0;
        this.themeManager = themeManager;
    }

    /**
     * Updates the badge count. If count is 0 or negative, no badge is displayed.
     *
     * @param count The number to display in the badge
     * @param container The container component that needs revalidation if icon size changes
     */
    public void setCount(int count, @Nullable Container container) {
        boolean previouslyVisible = this.count > 0;
        boolean nowVisible = count > 0;

        this.count = count;

        // If badge visibility changed, the icon size changed, so revalidate layout
        if (previouslyVisible != nowVisible && container != null) {
            container.revalidate();
        }
    }

    /**
     * Updates the badge count. If count is 0 or negative, no badge is displayed. Note: This version doesn't trigger
     * layout revalidation. Use setCount(int, Container) if the badge visibility change might affect layout.
     */
    public void setCount(int count) {
        this.count = count;
    }

    /**
     * Gets the current badge count.
     *
     * @return The current count
     */
    public int getCount() {
        return count;
    }

    /** Gets or creates a cached badge font scaled appropriately for the icon size and display. */
    private Font getBadgeFont(@Nullable Component c) {
        if (cachedBadgeFont == null) {
            // Scale font based on icon size for HiDPI support
            int iconSize = Math.max(baseIcon.getIconWidth(), baseIcon.getIconHeight());
            int fontSize = Math.max(7, iconSize / 3); // Minimum 7pt, scale with icon size

            // Use system default font as base for better platform integration
            Font baseFont = c != null ? c.getFont() : UIManager.getFont("Label.font");
            if (baseFont == null) {
                baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            }

            cachedBadgeFont = baseFont.deriveFont(Font.PLAIN, (float) fontSize);
        }
        return cachedBadgeFont;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        // Draw the base icon first
        baseIcon.paintIcon(c, g, x, y);

        // Only draw badge if count is greater than 0
        if (count <= 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            // Determine badge text (show "99+" for counts over 99)
            String badgeText = count > 99 ? "99+" : String.valueOf(count);

            // Calculate badge dimensions using cached, scaled font
            Font badgeFont = getBadgeFont(c);
            g2.setFont(badgeFont);
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(badgeText);
            int textHeight = fm.getHeight();

            // Badge circle should be slightly larger than the text
            int badgeSize = Math.max(textWidth + 4, textHeight + 2);
            badgeSize = Math.max(badgeSize, 12); // Minimum size of 12 pixels

            // Position badge mostly within icon bounds with minimal overlap to avoid hiding icon details
            int badgeX = x + baseIcon.getIconWidth() - badgeSize;
            int badgeY = y + baseIcon.getIconHeight() - badgeSize + 2;

            // Use theme-managed badge colors
            boolean isDarkTheme = themeManager.isDarkTheme(); // Get current theme state
            Color badgeBackgroundColor = ThemeColors.getColor(isDarkTheme, ThemeColors.GIT_BADGE_BACKGROUND);
            Color textColor = ThemeColors.getColor(isDarkTheme, ThemeColors.GIT_BADGE_TEXT);

            // Draw badge as solid circle with high-contrast background (VSCode style)
            g2.setColor(badgeBackgroundColor);
            g2.fillOval(badgeX, badgeY, badgeSize, badgeSize);

            // Optional: Add subtle border for definition
            g2.setStroke(new BasicStroke(1.4f));
            g2.setColor(badgeBackgroundColor.darker());
            g2.drawOval(badgeX, badgeY, badgeSize, badgeSize);

            // Draw badge text perfectly centered in the circle
            g2.setColor(textColor);
            int textX = badgeX + (badgeSize - textWidth) / 2;
            // Center the text vertically by positioning baseline at circle center + half of text's visual height
            int textY = badgeY + (badgeSize / 2) + ((fm.getAscent() - fm.getDescent()) / 2);
            g2.drawString(badgeText, textX, textY);

        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        // Reserve just 2 pixels for minimal badge overlap
        return baseIcon.getIconWidth() + (count > 0 ? 2 : 0);
    }

    @Override
    public int getIconHeight() {
        // Reserve just 2 pixels for minimal badge overlap
        return baseIcon.getIconHeight() + (count > 0 ? 2 : 0);
    }
}
