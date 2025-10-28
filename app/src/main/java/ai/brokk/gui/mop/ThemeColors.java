package ai.brokk.gui.mop;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import javax.swing.UIManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class for managing theme-specific colors throughout the application.
 * All custom colors are now defined in theme JSON files under the "Brokk.*" namespace.
 * Thread-safe: reads directly from UIManager which is thread-safe after initialization.
 */
public class ThemeColors {
    private static final Logger logger = LogManager.getLogger(ThemeColors.class);

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

    // Mode indicator colors
    public static final String MODE_ANSWER_BG = "mode_answer_bg";
    public static final String MODE_ANSWER_FG = "mode_answer_fg";
    public static final String MODE_ANSWER_ACCENT = "mode_answer_accent";
    public static final String MODE_CODE_BG = "mode_code_bg";
    public static final String MODE_CODE_FG = "mode_code_fg";
    public static final String MODE_CODE_ACCENT = "mode_code_accent";
    public static final String MODE_LUTZ_BG = "mode_lutz_bg";
    public static final String MODE_LUTZ_FG = "mode_lutz_fg";
    public static final String MODE_LUTZ_ACCENT = "mode_lutz_accent";

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

    // Chip colors
    public static final String CHIP_EDIT_BACKGROUND = "chip_edit_bg";
    public static final String CHIP_EDIT_FOREGROUND = "chip_edit_fg";
    public static final String CHIP_EDIT_BORDER = "chip_edit_border";
    public static final String CHIP_SUMMARY_BACKGROUND = "chip_summary_bg";
    public static final String CHIP_SUMMARY_FOREGROUND = "chip_summary_fg";
    public static final String CHIP_SUMMARY_BORDER = "chip_summary_border";
    public static final String CHIP_HISTORY_BACKGROUND = "chip_history_bg";
    public static final String CHIP_HISTORY_FOREGROUND = "chip_history_fg";
    public static final String CHIP_HISTORY_BORDER = "chip_history_border";
    public static final String CHIP_OTHER_BACKGROUND = "chip_other_bg";
    public static final String CHIP_OTHER_FOREGROUND = "chip_other_fg";
    public static final String CHIP_OTHER_BORDER = "chip_other_border";

    /**
     * Provides fallback colors for critical keys if UIManager doesn't have them.
     * This prevents NPE if colors are accessed before theme initialization.
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
            case CHAT_BACKGROUND -> new Color(37, 37, 37);
            case CHAT_TEXT -> new Color(212, 212, 212);
            default -> {
                logger.warn("No fallback color defined for key: {}", key);
                yield Color.MAGENTA; // Bright error color for unexpected keys
            }
        };
    }

    /**
     * Gets a color for the specified theme and key by reading from UIManager.
     * Thread-safe: UIManager.getColor is thread-safe after initialization.
     *
     * @param key the color key (without "Brokk." prefix)
     * @return the Color for the specified key
     */
    public static Color getColor(String key) {
        // Read from UIManager using Brokk namespace
        String uiKey = "Brokk." + key;
        Color color = UIManager.getColor(uiKey);

        if (color == null) {
            logger.warn("Color key '{}' not found in UIManager, using fallback", uiKey);
            return getFallbackColor(key);
        }

        return color;
    }

    /**
     * Gets a color for the specified theme and key by reading from UIManager.
     * Thread-safe: UIManager.getColor is thread-safe after initialization.
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
     * @param key the color key (without "Brokk." prefix)
     * @return the Color for the specified key
     */
    public static Color getColor(boolean isDarkTheme, String key) {
        return getColor(key);
    }

    /**
     * Gets a color as a hex string (e.g., "#rrggbb") for use in HTML/CSS.
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
     * @param key the color key (without "Brokk." prefix)
     * @return the color as a hex string
     */
    public static String getColorHex(boolean isDarkTheme, String key) {
        Color color = getColor(isDarkTheme, key);
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Reloads all colors from UIManager. Call this after changing the Look and Feel
     * to ensure colors update to match the new theme.
     * Note: With the new JSON-based approach, this is a no-op since UIManager
     * is automatically updated by FlatLaf when themes change.
     */
    public static void reloadColors() {
        // No-op: UIManager is automatically updated when theme changes
        logger.debug("reloadColors called (no-op with JSON-based themes)");
    }

    /**
     * Gets all colors for the specified theme.
     * Note: This returns all Brokk.* colors from UIManager.
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
     * @return a map of all custom Brokk colors
     */
    public static Map<String, Color> getAllColors(boolean isDarkTheme) {
        // Build a map of all Brokk.* colors from UIManager
        var colors = new HashMap<String, Color>();
        var defaults = UIManager.getDefaults();

        defaults.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.startsWith("Brokk."))
                .forEach(key -> {
                    var value = defaults.get(key);
                    if (value instanceof Color c) {
                        // Remove "Brokk." prefix for the map key
                        String shortKey = key.substring(6);
                        colors.put(shortKey, c);
                    }
                });

        return Map.copyOf(colors);
    }

    /**
     * Adds or updates a color in UIManager.
     * Note: Changes will be lost when theme is reloaded.
     *
     * @param key the color key (without "Brokk." prefix)
     * @param darkColor the color for dark theme (ignored, same color used for all themes)
     * @param lightColor the color for light theme (ignored, same color used for all themes)
     */
    public static synchronized void setColor(String key, Color darkColor, Color lightColor) {
        // With JSON-based themes, we just update UIManager with one color
        // (the theme-specific value should be in the JSON, but this allows runtime overrides)
        String uiKey = "Brokk." + key;
        UIManager.put(uiKey, darkColor); // Use dark color as default
        logger.debug("Updated color '{}' in UIManager", uiKey);
    }

    // Compatibility helpers for migrating from difftool.utils.Colors

    /**
     * Gets the diff added color for the specified theme.
     * Compatibility method for transitioning from Colors.getAdded().
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
     * @return the added color
     */
    public static Color getDiffAdded(boolean isDarkTheme) {
        return getColor(isDarkTheme, DIFF_ADDED);
    }

    /**
     * Gets the diff changed color for the specified theme.
     * Compatibility method for transitioning from Colors.getChanged().
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
     * @return the changed color
     */
    public static Color getDiffChanged(boolean isDarkTheme) {
        return getColor(isDarkTheme, DIFF_CHANGED);
    }

    /**
     * Gets the diff deleted color for the specified theme.
     * Compatibility method for transitioning from Colors.getDeleted().
     *
     * @param isDarkTheme ignored - theme colors are automatically loaded by FlatLaf
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
        return getColor(false, SEARCH_HIGHLIGHT); // Theme-independent
    }

    /**
     * Gets the current search highlight color.
     * Compatibility method for transitioning from Colors.CURRENT_SEARCH.
     *
     * @return the current search highlight color
     */
    public static Color getCurrentSearchHighlight() {
        return getColor(false, SEARCH_CURRENT); // Theme-independent
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
