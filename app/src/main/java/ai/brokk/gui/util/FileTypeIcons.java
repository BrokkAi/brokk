package ai.brokk.gui.util;

import ai.brokk.gui.SwingUtil;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.GrayFilter;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class that provides file-type specific icons for the project tree.
 * Maps file extensions to corresponding Bootstrap Streamline SVG icons loaded via the theme system.
 */
public final class FileTypeIcons {

    private FileTypeIcons() {}

    // Base icon size for tree display (16px - standard tree icon size)
    // Scaled by UIScale for HiDPI support
    private static final int BASE_ICON_SIZE = 16;

    // Cache for icons by UIManager key to avoid repeated lookups/allocations per render
    // Uses a simple map since keys are interned strings and icons should persist
    private static final Map<String, Icon> iconCache = new WeakHashMap<>();

    // Cache for greyed icons to avoid creating new disabled images on every render
    private static final Map<Icon, Icon> greyedIconCache = new WeakHashMap<>();

    // UIManager keys for folder and default file icons
    private static final String FOLDER_KEY = "Brokk.folder2_streamline_bootstrap";
    private static final String FOLDER_OPEN_KEY = "Brokk.folder2_open_streamline_bootstrap";
    private static final String DEFAULT_FILE_KEY = "Brokk.file_earmark_streamline_bootstrap";

    // Extension to icon name mapping (for extensions that don't match the standard pattern)
    // Most extensions can be auto-converted (e.g., "java" -> "Filetype-Java--Streamline-Bootstrap")
    private static final Map<String, String> EXTENSION_TO_ICON_NAME = Map.ofEntries(
            // Special cases where extension doesn't match icon name
            Map.entry("sc", "Scala"), // scala files use "sc" extension
            Map.entry("kt", "Java"), // Kotlin uses Java icon
            Map.entry("kts", "Java"),
            Map.entry("groovy", "Java"),
            Map.entry("clj", "Java"),
            Map.entry("mjs", "Js"),
            Map.entry("cjs", "Js"),
            Map.entry("mts", "Ts"),
            Map.entry("cts", "Ts"),
            Map.entry("pyw", "Py"),
            Map.entry("pyi", "Py"),
            Map.entry("htm", "Html"),
            Map.entry("xhtml", "Html"),
            Map.entry("scss", "Scss"),
            Map.entry("sass", "Sass"),
            Map.entry("less", "Css"),
            Map.entry("jsonc", "Json"),
            Map.entry("json5", "Json"),
            Map.entry("xsl", "Xml"),
            Map.entry("xslt", "Xml"),
            Map.entry("xsd", "Xml"),
            Map.entry("pom", "Xml"),
            Map.entry("yaml", "Yml"),
            Map.entry("markdown", "Md"),
            Map.entry("text", "Txt"),
            Map.entry("log", "Txt"),
            Map.entry("cc", "Cpp"),
            Map.entry("cxx", "Cpp"),
            Map.entry("hpp", "Cpp"),
            Map.entry("hxx", "Cpp"),
            Map.entry("bash", "Sh"),
            Map.entry("zsh", "Sh"),
            Map.entry("fish", "Sh"),
            Map.entry("bat", "Sh"),
            Map.entry("cmd", "Sh"),
            Map.entry("ps1", "Sh"));

    /**
     * Gets the appropriate icon for a file based on its extension.
     *
     * @param filename the filename (with or without path)
     * @return the icon for the file type, or File-Earmark--Streamline-Bootstrap if no specific icon exists
     */
    public static Icon getIconForFile(String filename) {
        String extension = getExtension(filename);
        if (extension != null) {
            String iconKey = getIconKeyForExtension(extension);
            if (iconKey != null) {
                Icon icon = getIconFromUIManager(iconKey);
                if (icon != null) {
                    return resizeIcon(icon);
                }
            }
        }
        // No specific icon found - use File-Earmark--Streamline-Bootstrap as default
        return getDefaultFileIcon();
    }

    /**
     * Gets the folder icon for closed or expanded state.
     *
     * @param expanded true for open folder icon, false for closed folder icon
     * @return the folder icon
     */
    public static Icon getFolderIcon(boolean expanded) {
        String key = expanded ? FOLDER_OPEN_KEY : FOLDER_KEY;
        Icon icon = getIconFromUIManager(key);
        if (icon != null) {
            return resizeIcon(icon);
        }
        // Fallback to default tree icons
        Icon fallback = expanded ? UIManager.getIcon("Tree.openIcon") : UIManager.getIcon("Tree.closedIcon");
        if (fallback != null) {
            return resizeIcon(fallback);
        }
        // Final fallback - use default file icon if tree icons aren't available
        return getDefaultFileIcon();
    }

    /**
     * Gets the default file icon used when no specific type is matched.
     *
     * @return the default file icon (File-Earmark--Streamline-Bootstrap), resized
     */
    public static Icon getDefaultFileIcon() {
        Icon icon = getIconFromUIManager(DEFAULT_FILE_KEY);
        if (icon != null) {
            return resizeIcon(icon);
        }
        // Fallback to default tree leaf icon
        Icon fallback = UIManager.getIcon("Tree.leafIcon");
        if (fallback != null) {
            return resizeIcon(fallback);
        }
        // Final fallback - create a simple empty icon if nothing is available
        // This should never happen in practice, but ensures we always return a valid icon
        int size = getScaledIconSize();
        return new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
    }

    /**
     * Converts a file extension to the corresponding Bootstrap Streamline icon key.
     *
     * @param extension the file extension (lowercase)
     * @return the UIManager key for the icon, or null if no mapping exists
     */
    private static @Nullable String getIconKeyForExtension(String extension) {
        // Check special cases first
        String iconName = EXTENSION_TO_ICON_NAME.get(extension);
        if (iconName == null) {
            if (extension.isEmpty()) {
                return null;
            }
            iconName = extension;
        }
        return "Brokk.filetype_" + iconName.toLowerCase(Locale.ROOT) + "_streamline_bootstrap";
    }

    /**
     * Gets an icon from UIManager and verifies it's valid.
     * Results are cached to avoid repeated lookups per render.
     * Returns null if the icon key doesn't exist in UIManager to avoid fallback icons.
     */
    private static @Nullable Icon getIconFromUIManager(String key) {
        // Check cache first
        Icon cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Use UIManager.getIcon to properly resolve LazyValue/ActiveValue entries
        Icon resolved = UIManager.getIcon(key);
        if (resolved == null || resolved.getIconWidth() <= 0 || resolved.getIconHeight() <= 0) {
            // Icon doesn't exist or is invalid - return null to use default
            return null;
        }

        // Get it via SwingUtil to ensure theme awareness (color filtering)
        Icon icon = SwingUtil.uiIcon(key);
        // Verify the icon has valid dimensions
        if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
            iconCache.put(key, icon);
            return icon;
        }
        return null;
    }

    /**
     * Gets the scaled icon size for tree display, accounting for HiDPI.
     */
    private static int getScaledIconSize() {
        return UIScale.scale(BASE_ICON_SIZE);
    }

    /**
     * Resizes an icon to the desired size for tree display.
     * Handles ThemedIcon, FlatSVGIcon, and other icon types.
     */
    private static Icon resizeIcon(Icon icon) {
        int size = getScaledIconSize();
        // If it's a ThemedIcon, use withSize() to resize
        if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
            return themedIcon.withSize(size);
        }
        // Handle direct FlatSVGIcon (e.g., from fallbacks)
        if (icon instanceof FlatSVGIcon svgIcon) {
            return svgIcon.derive(size, size);
        }
        // For other icon types, return as-is (they may already be the right size)
        return icon;
    }

    /**
     * Creates a greyed-out (disabled) version of the given icon.
     * Used for excluded/gitignored items in the project tree.
     * Results are cached to avoid creating new disabled images on every render.
     *
     * @param icon the icon to grey out
     * @return a greyed-out version of the icon
     */
    public static Icon getGreyedIcon(Icon icon) {
        // Check cache first
        Icon cached = greyedIconCache.get(icon);
        if (cached != null) {
            return cached;
        }

        Icon greyed = createGreyedIcon(icon);
        greyedIconCache.put(icon, greyed);
        return greyed;
    }

    private static Icon createGreyedIcon(Icon icon) {
        // Handle ThemedIcon wrapper from SwingUtil
        if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
            Icon delegate = themedIcon.delegate();
            if (delegate instanceof FlatSVGIcon svgIcon) {
                return svgIcon.getDisabledIcon();
            }
        }

        // Handle direct FlatSVGIcon
        if (icon instanceof FlatSVGIcon svgIcon) {
            return svgIcon.getDisabledIcon();
        }

        // Fallback for non-FlatSVG icons - use GrayFilter
        if (icon instanceof ImageIcon imageIcon) {
            var grayImage = GrayFilter.createDisabledImage(imageIcon.getImage());
            return new ImageIcon(grayImage);
        }

        // If all else fails, return the original icon
        return icon;
    }

    private static @Nullable String getExtension(String filename) {
        if (filename.isEmpty()) {
            return null;
        }
        // Handle files like ".gitignore" - they have no extension
        int lastDot = filename.lastIndexOf('.');
        int lastSep = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastDot > lastSep && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
