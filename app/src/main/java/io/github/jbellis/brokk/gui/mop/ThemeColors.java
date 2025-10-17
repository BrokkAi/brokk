package io.github.jbellis.brokk.gui.mop;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import javax.swing.UIManager;

/**
 * Helper class for managing theme-specific colors throughout the application.
 * Uses UIManager colors from FlatLaf where appropriate, with custom colors for app-specific needs.
 * Thread-safe: uses volatile immutable maps with atomic swap for concurrent access.
 */
public class ThemeColors {
    // Color key constants - use these instead of string literals for compile-time safety
    // Chat and messaging colors
    public static final String CHAT_BACKGROUND = "chat_background";
    public static final String MESSAGE_BACKGROUND = "message_background";
    public static final String CHAT_TEXT = "chat_text";
    public static final String CHAT_HEADER_TEXT = "chat_header_text";
    public static final String MESSAGE_BORDER_CUSTOM = "message_border_custom";
    public static final String MESSAGE_BORDER_AI = "message_border_ai";
    public static final String MESSAGE_BORDER_USER = "message_border_user";
    public static final String CUSTOM_MESSAGE_BACKGROUND = "custom_message_background";
    public static final String CUSTOM_MESSAGE_FOREGROUND = "custom_message_foreground";

    // Code and text colors
    public static final String CODE_BLOCK_BACKGROUND = "code_block_background";
    public static final String CODE_BLOCK_BORDER = "code_block_border";
    public static final String PLAIN_TEXT_FOREGROUND = "plain_text_foreground";
    public static final String CODE_HIGHLIGHT = "codeHighlight";
    public static final String RSYNTAX_BACKGROUND = "rsyntax_background";

    // HTML specific colors
    public static final String LINK_COLOR_HEX = "link_color_hex";
    public static final String BORDER_COLOR_HEX = "border_color_hex";

    // Git status colors
    public static final String GIT_STATUS_NEW = "git_status_new";
    public static final String GIT_STATUS_MODIFIED = "git_status_modified";
    public static final String GIT_STATUS_DELETED = "git_status_deleted";
    public static final String GIT_STATUS_UNKNOWN = "git_status_unknown";
    public static final String GIT_STATUS_ADDED = "git_status_added";
    public static final String GIT_CHANGED = "git_changed";

    // Git tab badge colors
    public static final String GIT_BADGE_BACKGROUND = "git_badge_background";
    public static final String GIT_BADGE_TEXT = "git_badge_text";

    // File reference badge colors
    public static final String BADGE_BORDER = "badge_border";
    public static final String BADGE_FOREGROUND = "badge_foreground";
    public static final String BADGE_HOVER_BORDER = "badge_hover_border";
    public static final String SELECTED_BADGE_BORDER = "selected_badge_border";
    public static final String SELECTED_BADGE_FOREGROUND = "selected_badge_foreground";

    // Filter box colors
    public static final String FILTER_UNSELECTED_FOREGROUND = "filter_unselected_foreground";
    public static final String FILTER_SELECTED_FOREGROUND = "filter_selected_foreground";
    public static final String FILTER_ICON_HOVER_BACKGROUND = "filter_icon_hover_background";

    // Diff chevron colors
    public static final String CHEVRON_NORMAL = "chevron_normal";
    public static final String CHEVRON_HOVER = "chevron_hover";

    // Notification colors
    public static final String NOTIF_ERROR_BG = "notif_error_bg";
    public static final String NOTIF_ERROR_FG = "notif_error_fg";
    public static final String NOTIF_ERROR_BORDER = "notif_error_border";
    public static final String NOTIF_CONFIRM_BG = "notif_confirm_bg";
    public static final String NOTIF_CONFIRM_FG = "notif_confirm_fg";
    public static final String NOTIF_CONFIRM_BORDER = "notif_confirm_border";
    public static final String NOTIF_COST_BG = "notif_cost_bg";
    public static final String NOTIF_COST_FG = "notif_cost_fg";
    public static final String NOTIF_COST_BORDER = "notif_cost_border";
    public static final String NOTIF_INFO_BG = "notif_info_bg";
    public static final String NOTIF_INFO_FG = "notif_info_fg";
    public static final String NOTIF_INFO_BORDER = "notif_info_border";

    // Diff viewer colors
    public static final String DIFF_ADDED = "diff_added";
    public static final String DIFF_CHANGED = "diff_changed";
    public static final String DIFF_DELETED = "diff_deleted";

    // Search highlight colors
    public static final String SEARCH_HIGHLIGHT = "search_highlight";
    public static final String SEARCH_CURRENT = "search_current";

    // Volatile immutable maps for thread-safe access
    // These are replaced atomically during theme reload
    private static volatile Map<String, Color> darkColors;
    private static volatile Map<String, Color> lightColors;

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

    /**
     * Initializes all color mappings. Called at class load time and when theme changes.
     * Returns new immutable maps for atomic replacement.
     */
    private static Map<String, Color>[] initializeColors() {
        // Build mutable maps for initialization
        var darkColorsBuilder = new HashMap<String, Color>();
        var lightColorsBuilder = new HashMap<String, Color>();

        // Initialize dark theme colors using UIManager where available
        darkColorsBuilder.put(CHAT_BACKGROUND, getUIManagerColor("Panel.background", new Color(37, 37, 37)));
        darkColorsBuilder.put(MESSAGE_BACKGROUND, getUIManagerColor("TextField.background", new Color(64, 64, 64)));
        darkColorsBuilder.put(CHAT_TEXT, getUIManagerColor("Label.foreground", new Color(212, 212, 212)));
        darkColorsBuilder.put(CHAT_HEADER_TEXT, getUIManagerColor("Component.linkColor", new Color(114, 159, 207)));

        // Custom message border colors (app-specific, no UIManager equivalent)
        darkColorsBuilder.put(MESSAGE_BORDER_CUSTOM, new Color(46, 100, 55));
        darkColorsBuilder.put(MESSAGE_BORDER_AI, new Color(86, 142, 130));
        darkColorsBuilder.put(MESSAGE_BORDER_USER, new Color(94, 125, 175));

        // Code and text colors
        darkColorsBuilder.put(CODE_BLOCK_BACKGROUND, getUIManagerColor("EditorPane.background", new Color(50, 50, 50)));
        darkColorsBuilder.put(CODE_BLOCK_BORDER, getUIManagerColor("Component.borderColor", new Color(80, 80, 80)));
        darkColorsBuilder.put(
                PLAIN_TEXT_FOREGROUND, getUIManagerColor("TextArea.foreground", new Color(230, 230, 230)));
        darkColorsBuilder.put(
                CUSTOM_MESSAGE_BACKGROUND, getUIManagerColor("TextField.background", new Color(60, 60, 60)));
        darkColorsBuilder.put(
                CUSTOM_MESSAGE_FOREGROUND, getUIManagerColor("TextField.foreground", new Color(220, 220, 220)));

        // HTML specific colors
        darkColorsBuilder.put(LINK_COLOR_HEX, getUIManagerColor("Component.linkColor", Color.decode("#678cb1")));
        darkColorsBuilder.put(BORDER_COLOR_HEX, getUIManagerColor("Component.borderColor", Color.decode("#555555")));
        darkColorsBuilder.put(CODE_HIGHLIGHT, new Color(125, 140, 111)); // Custom syntax highlight color
        darkColorsBuilder.put(RSYNTAX_BACKGROUND, getUIManagerColor("EditorPane.background", new Color(50, 50, 50)));

        // Initialize light theme colors using UIManager where available
        lightColorsBuilder.put(CHAT_BACKGROUND, getUIManagerColor("Panel.background", new Color(240, 240, 240)));
        lightColorsBuilder.put(MESSAGE_BACKGROUND, getUIManagerColor("TextField.background", new Color(250, 250, 250)));
        lightColorsBuilder.put(CHAT_TEXT, getUIManagerColor("Label.foreground", new Color(30, 30, 30)));
        lightColorsBuilder.put(CHAT_HEADER_TEXT, getUIManagerColor("Component.linkColor", new Color(51, 103, 214)));

        // Custom message border colors (app-specific, no UIManager equivalent)
        lightColorsBuilder.put(MESSAGE_BORDER_CUSTOM, new Color(46, 100, 55));
        lightColorsBuilder.put(MESSAGE_BORDER_AI, new Color(86, 142, 130));
        lightColorsBuilder.put(MESSAGE_BORDER_USER, new Color(94, 125, 175));

        // Code and text colors
        lightColorsBuilder.put(
                CODE_BLOCK_BACKGROUND, getUIManagerColor("EditorPane.background", new Color(240, 240, 240)));
        lightColorsBuilder.put(CODE_BLOCK_BORDER, getUIManagerColor("Component.borderColor", Color.GRAY));
        lightColorsBuilder.put(PLAIN_TEXT_FOREGROUND, getUIManagerColor("TextArea.foreground", Color.BLACK));
        lightColorsBuilder.put(
                CUSTOM_MESSAGE_BACKGROUND, getUIManagerColor("TextField.background", new Color(245, 245, 245)));
        lightColorsBuilder.put(
                CUSTOM_MESSAGE_FOREGROUND, getUIManagerColor("TextField.foreground", new Color(30, 30, 30)));

        // HTML specific colors
        lightColorsBuilder.put(LINK_COLOR_HEX, getUIManagerColor("Component.linkColor", Color.decode("#2675BF")));
        lightColorsBuilder.put(BORDER_COLOR_HEX, getUIManagerColor("Component.borderColor", Color.decode("#dddddd")));
        lightColorsBuilder.put(CODE_HIGHLIGHT, new Color(125, 140, 111)); // Custom syntax highlight color
        lightColorsBuilder.put(RSYNTAX_BACKGROUND, getUIManagerColor("EditorPane.background", Color.WHITE));

        // Git status colors for Commit tab - use FlatLaf Actions colors
        darkColorsBuilder.put(GIT_STATUS_NEW, getUIManagerColor("Actions.Green", new Color(88, 203, 63)));
        darkColorsBuilder.put(GIT_STATUS_MODIFIED, getUIManagerColor("Actions.Blue", new Color(71, 239, 230)));
        darkColorsBuilder.put(GIT_STATUS_DELETED, getUIManagerColor("Actions.Red", new Color(147, 99, 63)));
        darkColorsBuilder.put(GIT_STATUS_UNKNOWN, getUIManagerColor("Actions.Grey", new Color(128, 128, 128)));
        darkColorsBuilder.put(
                GIT_STATUS_ADDED, getUIManagerColor("Actions.Green", new Color(88, 203, 63))); // Same as new
        lightColorsBuilder.put(GIT_STATUS_NEW, getUIManagerColor("Actions.Green", new Color(42, 119, 34)));
        lightColorsBuilder.put(GIT_STATUS_MODIFIED, getUIManagerColor("Actions.Blue", new Color(60, 118, 202)));
        lightColorsBuilder.put(GIT_STATUS_DELETED, getUIManagerColor("Actions.Red", new Color(67, 100, 109)));
        lightColorsBuilder.put(GIT_STATUS_UNKNOWN, getUIManagerColor("Actions.Grey", new Color(180, 180, 180)));
        lightColorsBuilder.put(
                GIT_STATUS_ADDED, getUIManagerColor("Actions.Green", new Color(42, 119, 34))); // Same as new

        // Git changed lines color
        darkColorsBuilder.put(GIT_CHANGED, getUIManagerColor("Actions.Yellow", new Color(239, 202, 8)));
        lightColorsBuilder.put(GIT_CHANGED, getUIManagerColor("Actions.Yellow", new Color(204, 143, 0)));

        // Git tab badge colors - use semantic button colors
        darkColorsBuilder.put(
                GIT_BADGE_BACKGROUND, getUIManagerColor("Button.default.background", Color.decode("#007ACC")));
        darkColorsBuilder.put(GIT_BADGE_TEXT, getUIManagerColor("Button.default.foreground", Color.WHITE));
        lightColorsBuilder.put(GIT_BADGE_BACKGROUND, getUIManagerColor("Actions.Red", Color.decode("#DC3545")));
        lightColorsBuilder.put(GIT_BADGE_TEXT, Color.WHITE);

        // File reference badge colors - use link/accent colors
        darkColorsBuilder.put(BADGE_BORDER, getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        darkColorsBuilder.put(BADGE_FOREGROUND, getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        darkColorsBuilder.put(BADGE_HOVER_BORDER, getUIManagerColor("Component.focusColor", new Color(51, 122, 183)));
        darkColorsBuilder.put(SELECTED_BADGE_BORDER, getUIManagerColor("Label.foreground", Color.BLACK));
        darkColorsBuilder.put(SELECTED_BADGE_FOREGROUND, getUIManagerColor("Label.foreground", Color.BLACK));

        lightColorsBuilder.put(BADGE_BORDER, getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        lightColorsBuilder.put(BADGE_FOREGROUND, getUIManagerColor("Component.linkColor", new Color(66, 139, 202)));
        lightColorsBuilder.put(BADGE_HOVER_BORDER, getUIManagerColor("Component.focusColor", new Color(51, 122, 183)));
        lightColorsBuilder.put(SELECTED_BADGE_BORDER, getUIManagerColor("Label.foreground", Color.BLACK));
        lightColorsBuilder.put(SELECTED_BADGE_FOREGROUND, getUIManagerColor("Label.foreground", Color.BLACK));

        // Filter box colors - use warning/accent colors
        darkColorsBuilder.put(FILTER_UNSELECTED_FOREGROUND, getUIManagerColor("Actions.Yellow", new Color(0xFF8800)));
        darkColorsBuilder.put(FILTER_SELECTED_FOREGROUND, getUIManagerColor("Label.foreground", Color.WHITE));
        darkColorsBuilder.put(
                FILTER_ICON_HOVER_BACKGROUND,
                new Color(255, 255, 255, 64)); // Semi-transparent (no UIManager equivalent)

        lightColorsBuilder.put(FILTER_UNSELECTED_FOREGROUND, getUIManagerColor("Actions.Yellow", new Color(0xFF6600)));
        lightColorsBuilder.put(FILTER_SELECTED_FOREGROUND, getUIManagerColor("Label.foreground", Color.BLACK));
        lightColorsBuilder.put(
                FILTER_ICON_HOVER_BACKGROUND, new Color(0, 0, 0, 32)); // Semi-transparent (no UIManager equivalent)

        // Diff chevron colors - use label/text colors
        darkColorsBuilder.put(CHEVRON_NORMAL, getUIManagerColor("Label.disabledForeground", new Color(200, 200, 200)));
        darkColorsBuilder.put(CHEVRON_HOVER, getUIManagerColor("Label.foreground", Color.WHITE));

        lightColorsBuilder.put(CHEVRON_NORMAL, getUIManagerColor("Label.disabledForeground", new Color(80, 80, 80)));
        lightColorsBuilder.put(CHEVRON_HOVER, getUIManagerColor("Label.foreground", new Color(40, 40, 40)));

        // Notification colors (bg/fg/border) by role
        // Error
        darkColorsBuilder.put(NOTIF_ERROR_BG, new Color(0x3B1E20));
        darkColorsBuilder.put(NOTIF_ERROR_FG, new Color(0xFFB4B4));
        darkColorsBuilder.put(NOTIF_ERROR_BORDER, Color.decode("#B71C1C"));
        lightColorsBuilder.put(NOTIF_ERROR_BG, new Color(0xFFEBEE));
        lightColorsBuilder.put(NOTIF_ERROR_FG, Color.decode("#B71C1C"));
        lightColorsBuilder.put(NOTIF_ERROR_BORDER, new Color(0xEF9A9A));

        // Confirm/Success
        darkColorsBuilder.put(NOTIF_CONFIRM_BG, new Color(0x1E3323));
        darkColorsBuilder.put(NOTIF_CONFIRM_FG, new Color(0xC3E8C8));
        darkColorsBuilder.put(NOTIF_CONFIRM_BORDER, Color.decode("#2E7D32"));
        lightColorsBuilder.put(NOTIF_CONFIRM_BG, new Color(0xE8F5E9));
        lightColorsBuilder.put(NOTIF_CONFIRM_FG, Color.decode("#1B5E20"));
        lightColorsBuilder.put(NOTIF_CONFIRM_BORDER, new Color(0xA5D6A7));

        // Cost/Info-Primary
        darkColorsBuilder.put(NOTIF_COST_BG, new Color(0x1B2A3A));
        darkColorsBuilder.put(NOTIF_COST_FG, new Color(0xB7D8FF));
        darkColorsBuilder.put(NOTIF_COST_BORDER, Color.decode("#007ACC"));
        lightColorsBuilder.put(NOTIF_COST_BG, new Color(0xE3F2FD));
        lightColorsBuilder.put(NOTIF_COST_FG, Color.decode("#0D47A1"));
        lightColorsBuilder.put(NOTIF_COST_BORDER, new Color(0x90CAF9));

        // Info/Warning
        darkColorsBuilder.put(NOTIF_INFO_BG, new Color(0x332B1E));
        darkColorsBuilder.put(NOTIF_INFO_FG, new Color(0xFFD79A));
        darkColorsBuilder.put(NOTIF_INFO_BORDER, Color.decode("#FF9800"));
        lightColorsBuilder.put(NOTIF_INFO_BG, new Color(0xFFF3E0));
        lightColorsBuilder.put(NOTIF_INFO_FG, Color.decode("#E65100"));
        lightColorsBuilder.put(NOTIF_INFO_BORDER, new Color(0xFFCC80));

        // Diff viewer colors (background highlights for added/changed/deleted lines)
        darkColorsBuilder.put(DIFF_ADDED, new Color(60, 80, 60));
        darkColorsBuilder.put(DIFF_CHANGED, new Color(49, 75, 101));
        darkColorsBuilder.put(DIFF_DELETED, new Color(80, 60, 60));
        lightColorsBuilder.put(DIFF_ADDED, new Color(220, 250, 220));
        lightColorsBuilder.put(DIFF_CHANGED, new Color(220, 235, 250));
        lightColorsBuilder.put(DIFF_DELETED, new Color(250, 220, 220));

        // Search highlight colors (theme-independent for now)
        darkColorsBuilder.put(SEARCH_HIGHLIGHT, Color.yellow);
        darkColorsBuilder.put(SEARCH_CURRENT, new Color(255, 165, 0)); // Orange
        lightColorsBuilder.put(SEARCH_HIGHLIGHT, Color.yellow);
        lightColorsBuilder.put(SEARCH_CURRENT, new Color(255, 165, 0)); // Orange

        // Return immutable maps in an array [darkColors, lightColors]
        @SuppressWarnings("unchecked")
        var result = (Map<String, Color>[]) new Map<?, ?>[2];
        result[0] = Map.copyOf(darkColorsBuilder);
        result[1] = Map.copyOf(lightColorsBuilder);
        return result;
    }

    static {
        // Initialize colors at class load time with atomic assignment
        var maps = initializeColors();
        darkColors = maps[0];
        lightColors = maps[1];
    }

    /**
     * Reloads all colors from UIManager. Call this after changing the Look and Feel
     * to ensure colors update to match the new theme.
     * Thread-safe: atomically replaces the immutable color maps.
     */
    public static void reloadColors() {
        var maps = initializeColors();
        // Atomic swap of volatile references - thread-safe without locks
        darkColors = maps[0];
        lightColors = maps[1];
    }

    /**
     * Provides fallback colors for critical keys during early class initialization.
     * This prevents NPE if getColor() is called before static initialization completes.
     *
     * @param key the color key
     * @return a fallback color, or bright magenta as an error indicator
     */
    private static Color getFallbackColor(String key) {
        return switch (key) {
            case SEARCH_HIGHLIGHT -> Color.YELLOW;
            case SEARCH_CURRENT -> new Color(255, 165, 0); // Orange
            case DIFF_ADDED -> new Color(220, 250, 220);
            case DIFF_CHANGED -> new Color(220, 235, 250);
            case DIFF_DELETED -> new Color(250, 220, 220);
            default -> Color.MAGENTA; // Bright error color for unexpected keys
        };
    }

    /**
     * Gets a color for the specified theme and key.
     * Thread-safe: reads from volatile immutable maps.
     * Defensive: returns fallback colors if called during early class initialization.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @param key the color key
     * @return the Color for the specified theme and key
     * @throws IllegalArgumentException if the key doesn't exist (only after initialization)
     */
    public static Color getColor(boolean isDarkTheme, String key) {
        // Read volatile reference - guaranteed to see latest published map
        Map<String, Color> colors = isDarkTheme ? darkColors : lightColors;

        // Defensive: if maps not initialized yet, return fallback
        // This can happen during class initialization ordering edge cases
        if (colors == null) {
            return getFallbackColor(key);
        }

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
     * Thread-safe: returns the current immutable map.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return an immutable map of all colors for the specified theme
     */
    public static Map<String, Color> getAllColors(boolean isDarkTheme) {
        // Maps are already immutable, just return the reference
        return isDarkTheme ? darkColors : lightColors;
    }

    /**
     * Adds or updates a color in the theme maps.
     * Thread-safe: creates new immutable maps with the updated color.
     * Note: This is a relatively expensive operation as it rebuilds the entire map.
     *
     * @param key the color key
     * @param darkColor the color for dark theme
     * @param lightColor the color for light theme
     */
    public static synchronized void setColor(String key, Color darkColor, Color lightColor) {
        // Read current maps
        var currentDark = darkColors;
        var currentLight = lightColors;

        // Build new mutable maps with all existing colors
        var newDark = new HashMap<>(currentDark);
        var newLight = new HashMap<>(currentLight);

        // Add/update the color
        newDark.put(key, darkColor);
        newLight.put(key, lightColor);

        // Atomically replace with new immutable maps
        darkColors = Map.copyOf(newDark);
        lightColors = Map.copyOf(newLight);
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
        return getColor(isDarkTheme, DIFF_ADDED);
    }

    /**
     * Gets the diff changed color for the specified theme.
     * Compatibility method for transitioning from Colors.getChanged().
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return the changed color
     */
    public static Color getDiffChanged(boolean isDarkTheme) {
        return getColor(isDarkTheme, DIFF_CHANGED);
    }

    /**
     * Gets the diff deleted color for the specified theme.
     * Compatibility method for transitioning from Colors.getDeleted().
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return the deleted color
     */
    public static Color getDiffDeleted(boolean isDarkTheme) {
        return getColor(isDarkTheme, DIFF_DELETED);
    }

    /**
     * Gets the search highlight color.
     * Compatibility method for transitioning from Colors.SEARCH.
     *
     * @return the search highlight color
     */
    public static Color getSearchHighlight() {
        return getColor(false, SEARCH_HIGHLIGHT); // Theme-independent for now
    }

    /**
     * Gets the current search highlight color.
     * Compatibility method for transitioning from Colors.CURRENT_SEARCH.
     *
     * @return the current search highlight color
     */
    public static Color getCurrentSearchHighlight() {
        return getColor(false, SEARCH_CURRENT); // Theme-independent for now
    }

    // Direct UIManager access convenience methods

    /**
     * Gets the panel background color from UIManager.
     *
     * @return the panel background color
     */
    public static Color getPanelBackground() {
        return UIManager.getColor("Panel.background");
    }

    /**
     * Gets the label foreground color from UIManager.
     *
     * @return the label foreground color
     */
    public static Color getLabelForeground() {
        return UIManager.getColor("Label.foreground");
    }

    /**
     * Gets the component link color from UIManager.
     *
     * @return the component link color
     */
    public static Color getLinkColor() {
        return UIManager.getColor("Component.linkColor");
    }

    /**
     * Gets the component border color from UIManager.
     *
     * @return the component border color
     */
    public static Color getBorderColor() {
        return UIManager.getColor("Component.borderColor");
    }

    /**
     * Gets the text field background color from UIManager.
     *
     * @return the text field background color
     */
    public static Color getTextFieldBackground() {
        return UIManager.getColor("TextField.background");
    }

    /**
     * Gets the text field foreground color from UIManager.
     *
     * @return the text field foreground color
     */
    public static Color getTextFieldForeground() {
        return UIManager.getColor("TextField.foreground");
    }

    /**
     * Gets the editor pane background color from UIManager.
     *
     * @return the editor pane background color
     */
    public static Color getEditorBackground() {
        return UIManager.getColor("EditorPane.background");
    }

    /**
     * Gets a color directly from UIManager.
     *
     * @param key the UIManager key
     * @return the color, or null if not found
     */
    public static Color getUIManagerColorDirect(String key) {
        return UIManager.getColor(key);
    }
}
