package io.github.jbellis.brokk.gui.mop;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import javax.swing.UIManager;

/**
 * Helper class for managing theme-specific colors throughout the application.
 * Uses UIManager colors from FlatLaf where appropriate, with custom colors for app-specific needs.
 */
public class ThemeColors {
    // Color constants for dark theme
    private static final Map<String, Color> DARK_COLORS = new HashMap<>();
    // Color constants for light theme
    private static final Map<String, Color> LIGHT_COLORS = new HashMap<>();

    /**
     * Gets a color from UIManager with a fallback.
     *
     * @param key the UIManager key
     * @param fallback the fallback color if key is not found
     * @return the color from UIManager or fallback
     */
    private static Color getUIManagerColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    static {
        // Initialize dark theme colors using UIManager where available
        DARK_COLORS.put("chat_background", getUIManagerColor("Panel.background", new Color(37, 37, 37)));
        DARK_COLORS.put("message_background", getUIManagerColor("TextField.background", new Color(64, 64, 64)));
        DARK_COLORS.put("chat_text", getUIManagerColor("Label.foreground", new Color(212, 212, 212)));
        DARK_COLORS.put("chat_header_text", getUIManagerColor("Component.linkColor", new Color(114, 159, 207)));

        // Custom message border colors (app-specific, no UIManager equivalent)
        DARK_COLORS.put("message_border_custom", new Color(46, 100, 55));
        DARK_COLORS.put("message_border_ai", new Color(86, 142, 130));
        DARK_COLORS.put("message_border_user", new Color(94, 125, 175));

        // Code and text colors
        DARK_COLORS.put("code_block_background", getUIManagerColor("EditorPane.background", new Color(50, 50, 50)));
        DARK_COLORS.put("code_block_border", getUIManagerColor("Component.borderColor", new Color(80, 80, 80)));
        DARK_COLORS.put("plain_text_foreground", getUIManagerColor("TextArea.foreground", new Color(230, 230, 230)));
        DARK_COLORS.put("custom_message_background", getUIManagerColor("TextField.background", new Color(60, 60, 60)));
        DARK_COLORS.put(
                "custom_message_foreground", getUIManagerColor("TextField.foreground", new Color(220, 220, 220)));

        // HTML specific colors
        DARK_COLORS.put("link_color_hex", getUIManagerColor("Component.linkColor", Color.decode("#678cb1")));
        DARK_COLORS.put("border_color_hex", getUIManagerColor("Component.borderColor", Color.decode("#555555")));
        DARK_COLORS.put("codeHighlight", new Color(125, 140, 111)); // Custom syntax highlight color
        DARK_COLORS.put("rsyntax_background", getUIManagerColor("EditorPane.background", new Color(50, 50, 50)));

        // Initialize light theme colors using UIManager where available
        LIGHT_COLORS.put("chat_background", getUIManagerColor("Panel.background", new Color(240, 240, 240)));
        LIGHT_COLORS.put("message_background", getUIManagerColor("TextField.background", new Color(250, 250, 250)));
        LIGHT_COLORS.put("chat_text", getUIManagerColor("Label.foreground", new Color(30, 30, 30)));
        LIGHT_COLORS.put("chat_header_text", getUIManagerColor("Component.linkColor", new Color(51, 103, 214)));

        // Custom message border colors (app-specific, no UIManager equivalent)
        LIGHT_COLORS.put("message_border_custom", new Color(46, 100, 55));
        LIGHT_COLORS.put("message_border_ai", new Color(86, 142, 130));
        LIGHT_COLORS.put("message_border_user", new Color(94, 125, 175));

        // Code and text colors
        LIGHT_COLORS.put("code_block_background", getUIManagerColor("EditorPane.background", new Color(240, 240, 240)));
        LIGHT_COLORS.put("code_block_border", getUIManagerColor("Component.borderColor", Color.GRAY));
        LIGHT_COLORS.put("plain_text_foreground", getUIManagerColor("TextArea.foreground", Color.BLACK));
        LIGHT_COLORS.put(
                "custom_message_background", getUIManagerColor("TextField.background", new Color(245, 245, 245)));
        LIGHT_COLORS.put("custom_message_foreground", getUIManagerColor("TextField.foreground", new Color(30, 30, 30)));

        // HTML specific colors
        LIGHT_COLORS.put("link_color_hex", getUIManagerColor("Component.linkColor", Color.decode("#2675BF")));
        LIGHT_COLORS.put("border_color_hex", getUIManagerColor("Component.borderColor", Color.decode("#dddddd")));
        LIGHT_COLORS.put("codeHighlight", new Color(125, 140, 111)); // Custom syntax highlight color
        LIGHT_COLORS.put("rsyntax_background", getUIManagerColor("EditorPane.background", Color.WHITE));

        // Git status colors for Commit tab - use FlatLaf Actions colors
        DARK_COLORS.put("git_status_new", getUIManagerColor("Actions.Green", new Color(88, 203, 63)));
        DARK_COLORS.put("git_status_modified", getUIManagerColor("Actions.Blue", new Color(71, 239, 230)));
        DARK_COLORS.put("git_status_deleted", getUIManagerColor("Actions.Red", new Color(147, 99, 63)));
        DARK_COLORS.put("git_status_unknown", getUIManagerColor("Actions.Grey", new Color(128, 128, 128)));
        DARK_COLORS.put("git_status_added", getUIManagerColor("Actions.Green", new Color(88, 203, 63))); // Same as new
        LIGHT_COLORS.put("git_status_new", getUIManagerColor("Actions.Green", new Color(42, 119, 34)));
        LIGHT_COLORS.put("git_status_modified", getUIManagerColor("Actions.Blue", new Color(60, 118, 202)));
        LIGHT_COLORS.put("git_status_deleted", getUIManagerColor("Actions.Red", new Color(67, 100, 109)));
        LIGHT_COLORS.put("git_status_unknown", getUIManagerColor("Actions.Grey", new Color(180, 180, 180)));
        LIGHT_COLORS.put("git_status_added", getUIManagerColor("Actions.Green", new Color(42, 119, 34))); // Same as new

        // Git changed lines color
        DARK_COLORS.put("git_changed", getUIManagerColor("Actions.Yellow", new Color(239, 202, 8)));
        LIGHT_COLORS.put("git_changed", getUIManagerColor("Actions.Yellow", new Color(204, 143, 0)));

        // Git tab badge colors - use semantic button colors
        DARK_COLORS.put(
                "git_badge_background", getUIManagerColor("Button.default.background", Color.decode("#007ACC")));
        DARK_COLORS.put("git_badge_text", getUIManagerColor("Button.default.foreground", Color.WHITE));
        LIGHT_COLORS.put("git_badge_background", getUIManagerColor("Actions.Red", Color.decode("#DC3545")));
        LIGHT_COLORS.put("git_badge_text", Color.WHITE);

        // File reference badge colors - use link/accent colors
        DARK_COLORS.put("badge_border", getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        DARK_COLORS.put("badge_foreground", getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        DARK_COLORS.put("badge_hover_border", getUIManagerColor("Component.focusColor", new Color(51, 122, 183)));
        DARK_COLORS.put("selected_badge_border", getUIManagerColor("Label.foreground", Color.BLACK));
        DARK_COLORS.put("selected_badge_foreground", getUIManagerColor("Label.foreground", Color.BLACK));

        LIGHT_COLORS.put("badge_border", getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        LIGHT_COLORS.put("badge_foreground", getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        LIGHT_COLORS.put("badge_hover_border", getUIManagerColor("Component.focusColor", new Color(51, 122, 183)));
        LIGHT_COLORS.put("selected_badge_border", getUIManagerColor("Label.foreground", Color.BLACK));
        LIGHT_COLORS.put("selected_badge_foreground", getUIManagerColor("Label.foreground", Color.BLACK));

        // Filter box colors - use warning/accent colors
        DARK_COLORS.put("filter_unselected_foreground", getUIManagerColor("Actions.Yellow", new Color(0xFF8800)));
        DARK_COLORS.put("filter_selected_foreground", getUIManagerColor("Label.foreground", Color.WHITE));
        DARK_COLORS.put(
                "filter_icon_hover_background",
                new Color(255, 255, 255, 64)); // Semi-transparent (no UIManager equivalent)

        LIGHT_COLORS.put("filter_unselected_foreground", getUIManagerColor("Actions.Yellow", new Color(0xFF6600)));
        LIGHT_COLORS.put("filter_selected_foreground", getUIManagerColor("Label.foreground", Color.BLACK));
        LIGHT_COLORS.put(
                "filter_icon_hover_background", new Color(0, 0, 0, 32)); // Semi-transparent (no UIManager equivalent)

        // Diff chevron colors - use label/text colors
        DARK_COLORS.put("chevron_normal", getUIManagerColor("Label.disabledForeground", new Color(200, 200, 200)));
        DARK_COLORS.put("chevron_hover", getUIManagerColor("Label.foreground", Color.WHITE));

        LIGHT_COLORS.put("chevron_normal", getUIManagerColor("Label.disabledForeground", new Color(80, 80, 80)));
        LIGHT_COLORS.put("chevron_hover", getUIManagerColor("Label.foreground", new Color(40, 40, 40)));

        // Notification colors (bg/fg/border) by role
        // Error
        DARK_COLORS.put("notif_error_bg", new Color(0x3B1E20));
        DARK_COLORS.put("notif_error_fg", new Color(0xFFB4B4));
        DARK_COLORS.put("notif_error_border", Color.decode("#B71C1C"));
        LIGHT_COLORS.put("notif_error_bg", new Color(0xFFEBEE));
        LIGHT_COLORS.put("notif_error_fg", Color.decode("#B71C1C"));
        LIGHT_COLORS.put("notif_error_border", new Color(0xEF9A9A));

        // Confirm/Success
        DARK_COLORS.put("notif_confirm_bg", new Color(0x1E3323));
        DARK_COLORS.put("notif_confirm_fg", new Color(0xC3E8C8));
        DARK_COLORS.put("notif_confirm_border", Color.decode("#2E7D32"));
        LIGHT_COLORS.put("notif_confirm_bg", new Color(0xE8F5E9));
        LIGHT_COLORS.put("notif_confirm_fg", Color.decode("#1B5E20"));
        LIGHT_COLORS.put("notif_confirm_border", new Color(0xA5D6A7));

        // Cost/Info-Primary
        DARK_COLORS.put("notif_cost_bg", new Color(0x1B2A3A));
        DARK_COLORS.put("notif_cost_fg", new Color(0xB7D8FF));
        DARK_COLORS.put("notif_cost_border", Color.decode("#007ACC"));
        LIGHT_COLORS.put("notif_cost_bg", new Color(0xE3F2FD));
        LIGHT_COLORS.put("notif_cost_fg", Color.decode("#0D47A1"));
        LIGHT_COLORS.put("notif_cost_border", new Color(0x90CAF9));

        // Info/Warning
        DARK_COLORS.put("notif_info_bg", new Color(0x332B1E));
        DARK_COLORS.put("notif_info_fg", new Color(0xFFD79A));
        DARK_COLORS.put("notif_info_border", Color.decode("#FF9800"));
        LIGHT_COLORS.put("notif_info_bg", new Color(0xFFF3E0));
        LIGHT_COLORS.put("notif_info_fg", Color.decode("#E65100"));
        LIGHT_COLORS.put("notif_info_border", new Color(0xFFCC80));

        // Diff viewer colors (background highlights for added/changed/deleted lines)
        DARK_COLORS.put("diff_added", new Color(60, 80, 60));
        DARK_COLORS.put("diff_changed", new Color(49, 75, 101));
        DARK_COLORS.put("diff_deleted", new Color(80, 60, 60));
        LIGHT_COLORS.put("diff_added", new Color(220, 250, 220));
        LIGHT_COLORS.put("diff_changed", new Color(220, 235, 250));
        LIGHT_COLORS.put("diff_deleted", new Color(250, 220, 220));

        // Search highlight colors (theme-independent for now)
        DARK_COLORS.put("search_highlight", Color.yellow);
        DARK_COLORS.put("search_current", new Color(255, 165, 0)); // Orange
        LIGHT_COLORS.put("search_highlight", Color.yellow);
        LIGHT_COLORS.put("search_current", new Color(255, 165, 0)); // Orange
    }

    /**
     * Gets a color for the specified theme and key.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @param key the color key
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
     * @param key the color key ending with "_hex"
     * @return the color as a hex string
     */
    public static String getColorHex(boolean isDarkTheme, String key) {
        Color color = getColor(isDarkTheme, key);
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Gets all colors for the specified theme.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return a copy of the color map for the specified theme
     */
    public static Map<String, Color> getAllColors(boolean isDarkTheme) {
        // Return an immutable view; callers shouldn’t be able to mutate our theme state
        return Map.copyOf(isDarkTheme ? DARK_COLORS : LIGHT_COLORS);
    }

    /**
     * Adds or updates a color in the theme maps.
     *
     * @param key the color key
     * @param darkColor the color for dark theme
     * @param lightColor the color for light theme
     */
    public static void setColor(String key, Color darkColor, Color lightColor) {
        DARK_COLORS.put(key, darkColor);
        LIGHT_COLORS.put(key, lightColor);
    }

    // Compatibility helpers for migrating from difftool.utils.Colors

    /**
     * Gets the diff added color for the specified theme.
     * Compatibility method for transitioning from Colors.getAdded().
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return the added color
     */
    public static Color getDiffAdded(boolean isDarkTheme) {
        return getColor(isDarkTheme, "diff_added");
    }

    /**
     * Gets the diff changed color for the specified theme.
     * Compatibility method for transitioning from Colors.getChanged().
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return the changed color
     */
    public static Color getDiffChanged(boolean isDarkTheme) {
        return getColor(isDarkTheme, "diff_changed");
    }

    /**
     * Gets the diff deleted color for the specified theme.
     * Compatibility method for transitioning from Colors.getDeleted().
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return the deleted color
     */
    public static Color getDiffDeleted(boolean isDarkTheme) {
        return getColor(isDarkTheme, "diff_deleted");
    }

    /**
     * Gets the search highlight color.
     * Compatibility method for transitioning from Colors.SEARCH.
     *
     * @return the search highlight color
     */
    public static Color getSearchHighlight() {
        return getColor(false, "search_highlight"); // Theme-independent for now
    }

    /**
     * Gets the current search highlight color.
     * Compatibility method for transitioning from Colors.CURRENT_SEARCH.
     *
     * @return the current search highlight color
     */
    public static Color getCurrentSearchHighlight() {
        return getColor(false, "search_current"); // Theme-independent for now
    }
}
