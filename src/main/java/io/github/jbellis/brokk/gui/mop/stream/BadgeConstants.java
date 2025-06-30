package io.github.jbellis.brokk.gui.mop.stream;

/**
 * Shared constants for badge-related functionality across the codebase.
 * Used by SymbolBadgeCustomizer, IncrementalBlockRenderer, TextNodeMarkerCustomizer,
 * HtmlUtil, MarkdownComponentData, and related classes.
 */
public final class BadgeConstants {
    
    // HTML attributes
    public static final String ATTR_DATA_SYMBOL_ID = "data-symbol-id";
    public static final String ATTR_DATA_FILE_ID = "data-file-id";
    public static final String ATTR_DATA_BADGE_INFO = "data-badge-info";
    public static final String ATTR_TITLE = "title";
    public static final String ATTR_STYLE = "style";
    
    // CSS classes
    public static final String CLASS_BADGE = "badge";
    public static final String CLASS_BADGE_SYMBOL = "badge-symbol";
    public static final String CLASS_BADGE_FILE = "badge-file";
    public static final String CLASS_CLICKABLE_BADGE = "clickable-badge";
    public static final String CLASS_CLICKABLE_FILE_BADGE = "clickable-file-badge";
    
    // CSS selectors
    public static final String SELECTOR_BADGE_SYMBOL = "> .badge-symbol";

    // Style constants
    public static final String STYLE_CLICKABLE = "text-decoration: underline;";
    // Template strings
    public static final String TITLE_FORMAT = "file:%s:id:%d";
    public static final String BADGE_TITLE_FORMAT = "%s %s (%s)";
    public static final String BADGE_CLASS_PREFIX = "badge-";
    
    // Badge text constants
    public static final String BADGE_TEXT_CLASS = "C";
    public static final String BADGE_TEXT_FUNCTION = "F";
    public static final String BADGE_TEXT_FIELD = "V";
    public static final String BADGE_TEXT_MODULE = "M";
    
    private BadgeConstants() {
        // Utility class - prevent instantiation
    }
}