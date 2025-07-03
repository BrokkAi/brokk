package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.BadgeConstants;
import io.github.jbellis.brokk.gui.search.SearchConstants;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;

import javax.swing.text.html.StyleSheet;

/**
 * Utility class for generating CSS styles used across the application.
 * This ensures consistent styling between the main UI (MarkdownComponentData)
 * and debug output (HtmlUtil).
 */
public final class CssStyleGenerator {

    private CssStyleGenerator() {
        // Utility class - prevent instantiation
    }

    /**
     * Adds all standard CSS rules to a StyleSheet (for Swing components).
     *
     * @param styleSheet the StyleSheet to add rules to
     * @param isDarkTheme true for dark theme, false for light theme
     */
    public static void addStandardRules(StyleSheet styleSheet, boolean isDarkTheme) {
        var rules = generateCssRules(isDarkTheme, false);
        for (String rule : rules) {
            styleSheet.addRule(rule);
        }
    }

    /**
     * Generates CSS rules as a string (for HTML debug output).
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @return CSS rules as a string
     */
    public static String generateCssString(boolean isDarkTheme) {
        var rules = generateCssRules(isDarkTheme, true);
        return String.join("\n", rules) + "\n";
    }

    /**
     * Generates individual CSS rules for both StyleSheet and string output.
     *
     * @param isDarkTheme true for dark theme, false for light theme
     * @param includeDebugRules true to include debug-specific rules (for HTML output)
     * @return list of CSS rules
     */
    private static java.util.List<String> generateCssRules(boolean isDarkTheme, boolean includeDebugRules) {
        var rules = new java.util.ArrayList<String>();

        // Get theme-appropriate colors
        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");
        var bgColorHex = ColorUtil.toHex(bgColor);
        var textColor = ThemeColors.getColor(isDarkTheme, "chat_text");
        var textColorHex = ColorUtil.toHex(textColor);
        var linkColor = ThemeColors.getColorHex(isDarkTheme, "link_color_hex");
        var borderColor = ThemeColors.getColorHex(isDarkTheme, "border_color_hex");

        // Base typography - different margin/padding for debug vs swing
        String bodyMarginPadding = includeDebugRules ?
            "margin: 20px; padding: 8px;" :
            "margin: 0; padding-left: 8px; padding-right: 8px;";

        rules.add("body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5; " +
                 "background-color: " + bgColorHex + "; color: " + textColorHex + "; " + bodyMarginPadding + " }");

        // Headings
        rules.add("h1, h2, h3, h4, h5, h6 { margin-top: 18px; margin-bottom: 12px; " +
                 "font-weight: 600; line-height: 1.25; color: " + textColorHex + "; }");
        rules.add("h1 { font-size: 1.5em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        rules.add("h2 { font-size: 1.3em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        rules.add("h3 { font-size: 1.1em; }");
        rules.add("h4 { font-size: 1em; }");

        // Links
        rules.add("a { color: " + linkColor + "; text-decoration: none; }");
        rules.add("a:hover { text-decoration: underline; }");

        // Paragraphs and lists
        rules.add("p, ul, ol { margin-top: 0; margin-bottom: 12px; }");
        rules.add("ul, ol { padding-left: 2em; }");
        rules.add("li { margin: 0.25em 0; }");
        rules.add("li > p { margin-top: 12px; }");

        // Code styling
        rules.add("code { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace; " +
                 "padding: 0.2em 0.4em; margin: 0; font-size: 85%; border-radius: 3px; " +
                 "color: " + linkColor + "; }");

        // Table styling
        rules.add("table { border-collapse: collapse; margin: 15px 0; width: 100%; }");
        rules.add("table, th, td { border: 1px solid " + borderColor + "; }");
        rules.add("th { background-color: " + ThemeColors.getColorHex(isDarkTheme, "code_block_background") + "; " +
                 "padding: 8px; text-align: left; font-weight: 600; }");
        rules.add("td { padding: 8px; }");
        rules.add("tr:nth-child(even) { background-color: " + ThemeColors.getColorHex(isDarkTheme, "message_background") + "; }");
        rules.add("tr:hover { background-color: " + ThemeColors.getColorHex(isDarkTheme, "chat_background") + "; }");

        // Search highlighting classes - using same colors as diff tool
        var searchColorHex = ColorUtil.toHex(Colors.SEARCH);
        var currentSearchColorHex = ColorUtil.toHex(Colors.CURRENT_SEARCH);
        rules.add("." + SearchConstants.SEARCH_HIGHLIGHT_CLASS + " { background-color: " + searchColorHex + "; color: black; }");
        rules.add("." + SearchConstants.SEARCH_CURRENT_CLASS + " { background-color: " + currentSearchColorHex + "; color: black; }");

        // Badge styling
        rules.add(".badge-class { background-color: #6610f2; color: #dc3545; }");
        rules.add(".badge-function { background-color: #fd7e14; color: #dc3545; }");
        rules.add(".badge-field { background-color: #20c997; color: #dc3545; }");
        rules.add(".badge-module { background-color: #6f42c1; color: #dc3545; }");

        // File badge styling - lighter blue color for better visibility
        rules.add("." + BadgeConstants.CLASS_CLICKABLE_FILE_BADGE + " { color: #7ba7d4; }");
        
        // Symbol badge styling - green color with cursor pointer
        rules.add("." + BadgeConstants.CLASS_CLICKABLE_BADGE + ".badge-symbol { color: #28a745; cursor: pointer; }");

        // Debug-specific rules (only for HTML output)
        if (includeDebugRules) {
            rules.add("div[style*='border: 1px solid blue'] { margin: 10px; padding: 10px; }");
            rules.add("h3 { color: #0066cc; }");
        }

        return rules;
    }
}
