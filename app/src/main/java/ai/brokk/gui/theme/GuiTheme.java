package ai.brokk.gui.theme;

import ai.brokk.Brokk;
import ai.brokk.MainProject;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.mop.ThemeColors;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.jetbrains.annotations.Nullable;

/** Manages UI theme settings and application across the application. */
public class GuiTheme {
    private static final Logger logger = LogManager.getLogger(GuiTheme.class);

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_HIGH_CONTRAST = "high-contrast";

    private final JFrame frame;

    @Nullable
    private final JScrollPane mainScrollPane;

    private final Chrome chrome;

    // Track registered popup menus that need theme updates
    private final List<JPopupMenu> popupMenus = new ArrayList<>();

    /**
     * Creates a new theme manager
     *
     * @param frame The main application frame
     * @param mainScrollPane The main scroll pane for LLM output (can be null)
     * @param chrome The Chrome instance for UI feedback
     */
    public GuiTheme(JFrame frame, @Nullable JScrollPane mainScrollPane, Chrome chrome) {
        this.frame = frame;
        this.mainScrollPane = mainScrollPane;
        this.chrome = chrome;
    }

    /**
     * Loads and applies a theme to the Look and Feel.
     * This static method can be called before any Chrome instances exist,
     * making it suitable for application startup initialization.
     *
     * @param themeName The theme name (dark/light/high-contrast)
     */
    public static void setupLookAndFeel(String themeName) {
        String effectiveTheme = themeName;
        if (effectiveTheme == null || effectiveTheme.isEmpty()) {
            logger.warn("Null or empty theme name, defaulting to dark");
            effectiveTheme = THEME_DARK;
        }

        String themeFile =
                switch (effectiveTheme) {
                    case THEME_LIGHT -> "/themes/BrokkLight.theme.json";
                    case THEME_DARK -> "/themes/BrokkDark.theme.json";
                    case THEME_HIGH_CONTRAST -> "/themes/HighContrast.theme.json";
                    default -> {
                        logger.warn("Unknown theme '{}', defaulting to dark", effectiveTheme);
                        yield "/themes/BrokkDark.theme.json";
                    }
                };

        try (var stream = GuiTheme.class.getResourceAsStream(themeFile)) {
            if (stream == null) {
                logger.error("Theme file '{}' not found, falling back to Darcula", themeFile);
                FlatDarculaLaf.setup();
            } else {
                IntelliJTheme.setup(stream);
            }
        } catch (IOException e) {
            logger.error("Failed to load theme from '{}': {}", themeFile, e.getMessage());
            FlatDarculaLaf.setup();
        }
    }

    /**
     * Applies the current theme to the application
     *
     * @param isDark true for dark theme, false for light theme
     */
    public void applyTheme(boolean isDark) {
        // Delegate to the new method with current word wrap setting
        boolean wordWrap = MainProject.getCodeBlockWrapMode();
        applyTheme(isDark, wordWrap);
    }

    public void applyTheme(boolean isDark, boolean wordWrap) {
        String themeName = getThemeName(isDark);
        applyTheme(themeName, wordWrap);
    }

    public void applyTheme(String themeName, boolean wordWrap) {
        try {
            // Save preference first so we know the value is stored
            MainProject.setTheme(themeName);

            // Use the static utility method to load theme JSON and apply to UIManager
            setupLookAndFeel(themeName);

            // Reload ThemeColors to pick up new UIManager values
            ThemeColors.reloadColors();

            // Register custom icons for this theme
            boolean isDark = !THEME_LIGHT.equals(themeName);
            registerCustomIcons(isDark);

            // Apply theme to RSyntaxTextArea components
            applyThemeAsync(themeName, wordWrap);

            Brokk.getOpenProjectWindows().values().forEach(chrome -> chrome.getTheme()
                    .applyThemeToChromeComponents());

            // Notify ThemeBorderManager about theme changes so it can install or remove overlays.
            SwingUtilities.invokeLater(() -> {
                try {
                    ThemeBorderManager.getInstance().onThemeChanged();
                    // Always refresh existing windows to ensure borders are properly added or removed
                    ThemeBorderManager.getInstance().applyToExistingWindows();

                    // Update title bar styling for all frames using ThemeTitleBarManager
                    ThemeTitleBarManager.updateAllTitleBars();
                } catch (Throwable t) {
                    logger.warn("Failed to notify HighContrastBorderManager: {}", t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            chrome.toolError("Failed to switch theme: " + e.getMessage());
        }
    }

    public void applyThemeToChromeComponents() {
        // Update the UI
        SwingUtilities.updateComponentTreeUI(frame);

        // Update title bar styling for macOS frames
        SwingUtilities.invokeLater(() -> {
            Chrome.updateTitleBarStyling(frame);
        });

        // Update registered popup menus
        for (JPopupMenu menu : popupMenus) {
            SwingUtilities.updateComponentTreeUI(menu);
        }

        // Make sure scroll panes update properly
        if (mainScrollPane != null) {
            mainScrollPane.revalidate();
        }

        // Re-apply primary button styling for buttons that were explicitly styled earlier.
        // We do this after updateComponentTreeUI so the components re-adopt UIManager colors.
        SwingUtilities.invokeLater(() -> {
            Consumer<Component> recurse = new Consumer<Component>() {
                @Override
                public void accept(Component c) {
                    if (c instanceof AbstractButton b) {
                        Object prop = b.getClientProperty("brokk.primaryButton");
                        if (Boolean.TRUE.equals(prop)) {
                            SwingUtil.applyPrimaryButtonStyle(b);
                        }
                    }
                    if (c instanceof Container container) {
                        for (Component child : container.getComponents()) {
                            accept(child);
                        }
                    }
                }
            };

            // Apply to the main frame content
            recurse.accept(frame.getContentPane());

            // Apply to any displayable dialogs (so buttons in dialogs also re-style)
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isDisplayable()) {
                    recurse.accept(d.getContentPane());
                }
            }

            // Apply to tracked popup menus as well
            for (JPopupMenu menu : popupMenus) {
                recurse.accept(menu);
            }
        });
    }

    private static String getThemeName(boolean isDark) {
        return isDark ? THEME_DARK : THEME_LIGHT;
    }

    /**
     * Applies the appropriate theme to all RSyntaxTextArea components
     *
     * @param themeName "dark", "light", or "high-contrast"
     * @param wordWrap whether word wrap mode is enabled
     */
    private void applyThemeAsync(String themeName, boolean wordWrap) {
        loadRSyntaxTheme(themeName)
                .ifPresent(theme ->
                        // Apply to all RSyntaxTextArea components in open windows
                        SwingUtilities.invokeLater(() -> {
                            for (Window window : Window.getWindows()) {
                                if (window instanceof JFrame win) {
                                    applyThemeToFrame(win, theme, wordWrap);
                                }
                                if (window instanceof JDialog dialog) {
                                    // Skip dialogs that are not displayable
                                    if (dialog.isDisplayable()) {
                                        // 1. ThemeAware dialogs can theme themselves
                                        if (dialog instanceof ThemeAware aware) {
                                            aware.applyTheme(this, wordWrap);
                                        }
                                        applyThemeToComponent(dialog.getContentPane(), theme, wordWrap);
                                    }
                                }
                            }
                        }));
    }

    /**
     * Loads an RSyntaxTextArea theme.
     *
     * @param themeName The theme name (dark, light, or high-contrast)
     * @return The loaded Theme object, or null if loading fails.
     */
    public static Optional<Theme> loadRSyntaxTheme(String themeName) {
        String themeResource =
                switch (themeName) {
                    case THEME_LIGHT -> "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
                    case THEME_DARK -> "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
                    case THEME_HIGH_CONTRAST -> "/org/fife/ui/rsyntaxtextarea/themes/high-contrast.xml";
                    default -> {
                        logger.warn("Unknown theme '{}' for RSyntaxTextArea, defaulting to dark", themeName);
                        yield "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
                    }
                };

        var inputStream = GuiTheme.class.getResourceAsStream(themeResource);

        if (inputStream == null) {
            logger.error("RSyntaxTextArea theme resource not found: {}", themeResource);
            return Optional.empty();
        }

        try {
            return Optional.of(Theme.load(inputStream));
        } catch (IOException e) {
            logger.error(
                    "Could not load {} RSyntaxTextArea theme from {}: {}", themeName, themeResource, e.getMessage());
            return Optional.empty();
        }
    }

    /** Applies the syntax theme to every relevant component contained in the supplied frame. */
    private void applyThemeToFrame(JFrame frame, Theme theme, boolean wordWrap) {
        assert SwingUtilities.isEventDispatchThread() : "applyThemeToFrame must be called on EDT";
        applyThemeToComponent(frame.getContentPane(), theme, wordWrap);
    }

    /**
     * Recursive depth-first traversal of the Swing component hierarchy that honours the
     * {@link ThemeAware} contract.
     */
    private void applyThemeToComponent(@Nullable Component component, Theme theme, boolean wordWrap) {
        assert SwingUtilities.isEventDispatchThread() : "applyThemeToComponent must be called on EDT";
        if (component == null) {
            return;
        }

        switch (component) {
            // 1. Give ThemeAware components first crack at theming themselves
            case ThemeAware aware -> aware.applyTheme(this, wordWrap);
            // 2. Plain RSyntaxTextArea
            case RSyntaxTextArea area -> theme.apply(area);
            // 3. Handle the common case of RSyntaxTextArea wrapped in a JScrollPane
            case JScrollPane scrollPane -> {
                var viewport = scrollPane.getViewport();
                if (viewport != null) {
                    @Nullable Component view = viewport.getView();
                    applyThemeToComponent(view, theme, wordWrap);
                }
            }
            default -> {}
        }

        // 4. Recurse into child components (if any)
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyThemeToComponent(child, theme, wordWrap);
            }
        }
    }

    /**
     * Get the current theme identifier (normalized to lowercase).
     *
     * @return "light", "dark", or "high-contrast"
     */
    public String getCurrentTheme() {
        return MainProject.getTheme().toLowerCase(Locale.ROOT);
    }

    /**
     * Get the human-readable display name for the current theme.
     *
     * @return "Brokk Light", "Brokk Dark", or "High Contrast"
     */
    public String getCurrentThemeName() {
        return switch (getCurrentTheme()) {
            case THEME_LIGHT -> "Brokk Light";
            case THEME_DARK -> "Brokk Dark";
            case THEME_HIGH_CONTRAST -> "High Contrast";
            default -> "Unknown Theme";
        };
    }

    /**
     * Check if the current theme is specifically the high-contrast theme.
     *
     * @return true if high-contrast theme is active
     */
    public boolean isHighContrastTheme() {
        return THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
    }

    /**
     * Checks if dark color scheme is currently active. This includes both the dark theme and high-contrast theme, as
     * both use dark backgrounds. Use this when you need to select dark vs light color palettes.
     *
     * @return true if dark theme or high contrast theme is active
     */
    public boolean isDarkTheme() {
        String theme = MainProject.getTheme();
        return THEME_DARK.equalsIgnoreCase(theme) || THEME_HIGH_CONTRAST.equalsIgnoreCase(theme);
    }

    /**
     * Registers a popup menu to receive theme updates
     *
     * @param menu The popup menu to register
     */
    public void registerPopupMenu(JPopupMenu menu) {
        if (!popupMenus.contains(menu)) {
            popupMenus.add(menu);

            // Apply current theme immediately if already initialized
            SwingUtilities.invokeLater(() -> SwingUtilities.updateComponentTreeUI(menu));
        }
    }

    /**
     * Applies the current RSyntaxTextArea theme to the supplied component.
     *
     * @param textArea The text area to apply theme to
     */
    public void applyCurrentThemeToComponent(RSyntaxTextArea textArea) {
        String themeName = MainProject.getTheme();
        loadRSyntaxTheme(themeName).ifPresent(theme -> SwingUtilities.invokeLater(() -> theme.apply(textArea)));
    }

    /**
     * Applies the current RSyntaxTextArea theme to the supplied component while preserving
     * any explicitly set font size from FontSizeAware components.
     * Must be called on the EDT.
     *
     * @param textArea The text area to apply theme to
     */
    public void applyThemePreservingFont(RSyntaxTextArea textArea) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Save current font if component has explicit font size
        float currentFontSize = -1;
        if (textArea instanceof FontSizeAware fsAware && fsAware.hasExplicitFontSize()) {
            currentFontSize = fsAware.getExplicitFontSize();
        }

        // Apply theme and restore font synchronously
        String themeName = MainProject.getTheme();
        final float fontSize = currentFontSize;
        loadRSyntaxTheme(themeName).ifPresent(theme -> {
            // Apply theme (which will override font)
            theme.apply(textArea);

            // Immediately restore font size if it was explicitly set
            if (fontSize > 0) {
                Font themeFont = textArea.getFont();
                if (themeFont != null) {
                    textArea.setFont(themeFont.deriveFont(fontSize));
                }
            }
        });
    }

    /**
     * Updates component tree UI while preserving explicit font sizes in FontSizeAware components.
     *
     * @param container The container to update
     */
    public void updateComponentTreeUIPreservingFonts(Container container) {
        // Collect font sizes from FontSizeAware components before update
        java.util.Map<Component, Float> fontMap = new java.util.HashMap<>();
        collectExplicitFonts(container, fontMap);

        // Update UI
        SwingUtilities.updateComponentTreeUI(container);

        // Restore explicit fonts
        SwingUtilities.invokeLater(() -> restoreExplicitFonts(fontMap));
    }

    /**
     * Recursively collects explicit font sizes from FontSizeAware components.
     */
    private void collectExplicitFonts(Container container, java.util.Map<Component, Float> fontMap) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof FontSizeAware fsAware && fsAware.hasExplicitFontSize()) {
                float fontSize = fsAware.getExplicitFontSize();
                fontMap.put(comp, fontSize);
            }
            if (comp instanceof Container) {
                collectExplicitFonts((Container) comp, fontMap);
            }
        }
    }

    /**
     * Restores explicit font sizes to components.
     */
    private void restoreExplicitFonts(java.util.Map<Component, Float> fontMap) {
        fontMap.forEach((comp, fontSize) -> {
            if (comp instanceof RSyntaxTextArea textArea) {
                Font currentFont = textArea.getFont();
                if (currentFont != null) {
                    Font preservedFont = currentFont.deriveFont(fontSize);
                    textArea.setFont(preservedFont);
                }
            }
        });
    }

    /**
     * Registers custom icons for the application based on the current theme
     *
     * @param isDark true for dark theme icons, false for light theme icons
     */
    private void registerCustomIcons(boolean isDark) {
        String iconBase = isDark ? "/icons/dark/" : "/icons/light/";

        try {
            // Try to discover icons from the resource directory
            var iconUrl = GuiTheme.class.getResource(iconBase);
            if (iconUrl != null) {
                var iconFiles = discoverIconFiles(iconUrl, iconBase);
                for (String iconFile : iconFiles) {
                    // Extract filename without extension for the key
                    String filename = iconFile.substring(iconFile.lastIndexOf('/') + 1);
                    int dotIndex = filename.lastIndexOf('.');
                    String keyName = (dotIndex == -1) ? filename : filename.substring(0, dotIndex);
                    String iconKey = "Brokk." + keyName;

                    registerIcon(iconKey, iconFile);
                }
                logger.debug("Registered {} custom icons for {} theme", iconFiles.size(), isDark ? "dark" : "light");
            } else {
                logger.warn("Icon directory not found: {}", iconBase);
            }
        } catch (Exception e) {
            logger.warn("Failed to discover icons from {}: {}", iconBase, e.getMessage());
        }
    }

    /**
     * Discovers PNG and GIF icon files from a resource directory
     *
     * @param directoryUrl The URL of the directory resource
     * @param iconBase The base path for constructing resource paths
     * @return List of resource paths to icon files
     */
    private List<String> discoverIconFiles(URL directoryUrl, String iconBase) {
        var iconFiles = new ArrayList<String>();

        try {
            String protocol = directoryUrl.getProtocol();
            if (protocol == null) {
                logger.warn("URL has no protocol: {}", directoryUrl);
                return iconFiles;
            }

            if ("file".equals(protocol)) {
                // Running from file system (development)
                var dirPath = Paths.get(directoryUrl.toURI());
                try (var stream = Files.list(dirPath)) {
                    stream.filter(path -> {
                                var fileNamePath = path.getFileName();
                                if (fileNamePath == null) {
                                    return false;
                                }
                                String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
                                return name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".svg");
                            })
                            .forEach(path -> {
                                String filename = path.getFileName().toString();
                                iconFiles.add(iconBase + filename);
                            });
                }
            } else if ("jar".equals(protocol)) {
                // Running from JAR file
                String jarPath = directoryUrl.getPath();
                if (jarPath == null) {
                    logger.warn("JAR URL has no path: {}", directoryUrl);
                    return iconFiles;
                }
                var exclamationIndex = jarPath.indexOf('!');
                if (exclamationIndex >= 0) {
                    var jarFile = jarPath.substring(5, exclamationIndex); // Remove "file:"
                    var entryPath = jarPath.substring(exclamationIndex + 2); // Remove "!/"

                    try (var jar = new JarFile(jarFile)) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (entryName.startsWith(entryPath) && !entry.isDirectory()) {
                                var filename = entryName
                                        .substring(entryName.lastIndexOf('/') + 1)
                                        .toLowerCase(Locale.ROOT);
                                if (filename.endsWith(".png")
                                        || filename.endsWith(".gif")
                                        || filename.endsWith(".svg")) {
                                    iconFiles.add("/" + entryName);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error scanning icon directory {}: {}", directoryUrl, e.getMessage());
        }

        return iconFiles;
    }

    /**
     * Registers a single icon in the UIManager
     *
     * @param key The UIManager key for the icon
     * @param resourcePath The resource path to the icon file
     */
    private static void registerIcon(String key, String resourcePath) {
        URL url = GuiTheme.class.getResource(resourcePath);
        if (url == null) {
            logger.warn("Icon resource {} not found for key {}", resourcePath, key);
            return;
        }

        Icon icon;
        String lower = resourcePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg")) {
            // FlatLaf can render SVG natively
            icon = new FlatSVGIcon(url);
        } else {
            icon = new ImageIcon(url);
        }
        UIManager.put(key, icon);
    }
}
