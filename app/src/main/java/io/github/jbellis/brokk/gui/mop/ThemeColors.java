package io.github.jbellis.brokk.gui.mop;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for managing theme-specific colors throughout the application.
 */
public class ThemeColors {
    // Color constants for dark theme
    private static final Map<String, Color> DARK_COLORS = new HashMap<>();
    // Color constants for light theme
    private static final Map<String, Color> LIGHT_COLORS = new HashMap<>();

    static {
        // Initialize dark theme colors
        DARK_COLORS.put("chat_background", new Color(37, 37, 37));
        DARK_COLORS.put("message_background", new Color(64, 64, 64));
        DARK_COLORS.put("chat_text", new Color(212, 212, 212));
        DARK_COLORS.put("chat_header_text", new Color(114, 159, 207));
        DARK_COLORS.put("message_border_custom", new Color(46, 100, 55));
        DARK_COLORS.put("message_border_ai", new Color(86,142,130));
        DARK_COLORS.put("message_border_user", new Color(94, 125, 175));

        // Code and text colors
        DARK_COLORS.put("code_block_background", new Color(50, 50, 50));
        DARK_COLORS.put("code_block_border", new Color(80, 80, 80));
        DARK_COLORS.put("plain_text_foreground", new Color(230, 230, 230));
        DARK_COLORS.put("custom_message_background", new Color(60, 60, 60));
        DARK_COLORS.put("custom_message_foreground", new Color(220, 220, 220));

        // HTML specific colors as hex strings
        DARK_COLORS.put("link_color_hex", Color.decode("#678cb1"));
        DARK_COLORS.put("border_color_hex", Color.decode("#555555"));
        DARK_COLORS.put("codeHighlight", new Color(125, 140, 111));
        DARK_COLORS.put("rsyntax_background", new Color(50, 50, 50));

        // Initialize light theme colors
        LIGHT_COLORS.put("chat_background", new Color(240, 240, 240));
        LIGHT_COLORS.put("message_background", new Color(250, 250, 250));
        LIGHT_COLORS.put("chat_text", new Color(30, 30, 30));
        LIGHT_COLORS.put("chat_header_text", new Color(51, 103, 214));
        LIGHT_COLORS.put("message_border_custom", new Color(46, 100, 55));
        LIGHT_COLORS.put("message_border_ai", new Color(86,142,130));
        LIGHT_COLORS.put("message_border_user", new Color(94, 125, 175));

        // Code and text colors
        LIGHT_COLORS.put("code_block_background", new Color(240, 240, 240));
        LIGHT_COLORS.put("code_block_border", Color.GRAY);
        LIGHT_COLORS.put("plain_text_foreground", Color.BLACK);
        LIGHT_COLORS.put("custom_message_background", new Color(245, 245, 245));
        LIGHT_COLORS.put("custom_message_foreground", new Color(30, 30, 30));

        // HTML specific colors as hex strings
        LIGHT_COLORS.put("link_color_hex", Color.decode("#678cb1"));
        LIGHT_COLORS.put("border_color_hex", Color.decode("#dddddd"));
        LIGHT_COLORS.put("codeHighlight", new Color(125, 140, 111));
        LIGHT_COLORS.put("rsyntax_background", Color.WHITE);

        // Git status colors for Commit tab
        DARK_COLORS.put("git_status_new", new Color(88, 203, 63));
        DARK_COLORS.put("git_status_modified", new Color(71, 239, 230));
        DARK_COLORS.put("git_status_deleted", new Color(147, 99, 63));
        DARK_COLORS.put("git_status_unknown", new Color(128, 128, 128));
        DARK_COLORS.put("git_status_added", new Color(88, 203, 63));      // Same as new
        LIGHT_COLORS.put("git_status_new", new Color(42, 119, 34));
        LIGHT_COLORS.put("git_status_modified", new Color(60, 118, 202));
        LIGHT_COLORS.put("git_status_deleted", new Color(67, 100, 109));
        LIGHT_COLORS.put("git_status_unknown", new Color(180, 180, 180));
        LIGHT_COLORS.put("git_status_added", new Color(42, 119, 34));     // Same as new

        // Git changed lines color
        DARK_COLORS.put("git_changed", new Color(239, 202, 8));       // Amber/Yellow
        LIGHT_COLORS.put("git_changed", new Color(204, 143, 0));      // Darker Amber/Yellow

        // File reference badge colors (same for both themes for now)
        Color badgeBorder = new Color(66, 139, 202);
        Color badgeForeground = new Color(66, 139, 202);
        Color badgeHoverBorder = new Color(51, 122, 183);
        Color selectedBadgeBorder = Color.BLACK;
        Color selectedBadgeForeground = Color.BLACK;

        DARK_COLORS.put("badge_border", badgeBorder);
        DARK_COLORS.put("badge_foreground", badgeForeground);
        DARK_COLORS.put("badge_hover_border", badgeHoverBorder);
        DARK_COLORS.put("selected_badge_border", selectedBadgeBorder);
        DARK_COLORS.put("selected_badge_foreground", selectedBadgeForeground);

        LIGHT_COLORS.put("badge_border", badgeBorder);
        LIGHT_COLORS.put("badge_foreground", badgeForeground);
        LIGHT_COLORS.put("badge_hover_border", badgeHoverBorder);
        LIGHT_COLORS.put("selected_badge_border", selectedBadgeBorder);
        LIGHT_COLORS.put("selected_badge_foreground", selectedBadgeForeground);

        // Filter box colors
        DARK_COLORS.put("filter_unselected_foreground", new Color(0xFF8800)); // IntellJ-like Orange
        DARK_COLORS.put("filter_selected_foreground", Color.WHITE);
        DARK_COLORS.put("filter_icon_hover_background", new Color(255, 255, 255, 64)); // Semi-transparent white

        LIGHT_COLORS.put("filter_unselected_foreground", new Color(0xFF6600)); // Slightly darker orange for light theme
        LIGHT_COLORS.put("filter_selected_foreground", Color.BLACK);
        LIGHT_COLORS.put("filter_icon_hover_background", new Color(0, 0, 0, 32)); // Semi-transparent black
    }

    /**
     * Gets a color for the specified theme and key.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @param key         the color key
     * @return the Color for the specified theme and key
     * @throws IllegalArgumentException if the key doesn't exist
     */
    public static Color getColor(boolean isDarkTheme, String key) {
        Map<String, Color> colors = isDarkTheme ? DARK_COLORS : LIGHT_COLORS;
        Color color = colors.get(key);

        if (color == null) {
            throw new IllegalArgumentException("Color key not found: " + key);
        }

        return color;
    }

    /**
     * Gets a color as a hex string (e.g., "#rrggbb") for use in HTML/CSS.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @param key         the color key ending with "_hex"
     * @return the color as a hex string
     */
    public static String getColorHex(boolean isDarkTheme, String key) {
        Color color = getColor(isDarkTheme, key);
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Adds or updates a color in the theme maps.
     *
     * @param key        the color key
     * @param darkColor  the color for dark theme
     * @param lightColor the color for light theme
     */
    public static void setColor(String key, Color darkColor, Color lightColor) {
        DARK_COLORS.put(key, darkColor);
        LIGHT_COLORS.put(key, lightColor);
    }
}
