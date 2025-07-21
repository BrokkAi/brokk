package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.MainProject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Manages UI theme settings and application across the application.
 */
public class GuiTheme {
    private static final Logger logger = LogManager.getLogger(GuiTheme.class);

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

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
     * Applies the current theme to the application
     *
     * @param isDark true for dark theme, false for light theme
     */
    public void applyTheme(boolean isDark) {
        String themeName = getThemeName(isDark);

        try {
            // Save preference first so we know the value is stored
            MainProject.setTheme(themeName);

            // Apply the theme to the Look and Feel
            if (isDark) {
                com.formdev.flatlaf.FlatDarkLaf.setup();
            } else {
                com.formdev.flatlaf.FlatLightLaf.setup();
            }

            // Register custom icons for this theme
            registerCustomIcons(isDark);

            // Apply theme to RSyntaxTextArea components
            applyThemeAsync(themeName);

            Brokk.getOpenProjectWindows()
                    .values()
                    .forEach(chrome -> chrome.getTheme().applyThemeToChromeComponents());
        } catch (Exception e) {
            chrome.toolError("Failed to switch theme: " + e.getMessage());
        }
    }

    private void applyThemeToChromeComponents() {
        // Update the UI
        SwingUtilities.updateComponentTreeUI(frame);

        // Update registered popup menus
        for (JPopupMenu menu : popupMenus) {
            SwingUtilities.updateComponentTreeUI(menu);
        }

        // Make sure scroll panes update properly
        if (mainScrollPane != null) {
            mainScrollPane.revalidate();
        }
    }

    private static String getThemeName(boolean isDark) {
        return isDark ? THEME_DARK : THEME_LIGHT;
    }

    /**
     * Applies the appropriate theme to all RSyntaxTextArea components
     * @param themeName "dark" or "light"
     */
    private void applyThemeAsync(String themeName) {
        loadRSyntaxTheme(THEME_DARK.equals(themeName)).ifPresent(theme ->
            // Apply to all RSyntaxTextArea components in open windows
            SwingUtilities.invokeLater(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JFrame win) {
                        applyThemeToFrame(win, theme);
                    }
                    if (window instanceof JDialog dialog) {
                        // Skip dialogs that are not displayable
                        if (dialog.isDisplayable()) {
                            // 1. ThemeAware dialogs can theme themselves
                            if (dialog instanceof ThemeAware aware) {
                                aware.applyTheme(this);
                            }
                            applyThemeToComponent(dialog.getContentPane(), theme);
                        }
                    }
                }
            })
        );
    }

    /**
     * Loads an RSyntaxTextArea theme.
     *
     * @param isDark true for dark theme, false for light theme
     * @return The loaded Theme object, or null if loading fails.
     */
    public static Optional<Theme> loadRSyntaxTheme(boolean isDark) {
        String themeChoice = getThemeName(isDark);
        String themeResource = "/org/fife/ui/rsyntaxtextarea/themes/%s".formatted(isDark ? "dark.xml" : "default.xml");
        var inputStream = GuiTheme.class.getResourceAsStream(themeResource);

        if (inputStream == null) {
            logger.error("RSyntaxTextArea theme resource not found: {}", themeResource);
            return Optional.empty();
        }

        try {
            return Optional.of(Theme.load(inputStream));
        } catch (IOException e) {
            logger.error("Could not load {} RSyntaxTextArea theme from {}: {}", themeChoice, themeResource, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Applies the syntax theme to every relevant component contained in the
     * supplied frame.
     */
    private void applyThemeToFrame(JFrame frame, Theme theme) {
        assert SwingUtilities.isEventDispatchThread() : "applyThemeToFrame must be called on EDT";
        applyThemeToComponent(frame.getContentPane(), theme);
    }

    /**
     * Recursive depth-first traversal of the Swing component hierarchy that honours the
     * {@link io.github.jbellis.brokk.gui.ThemeAware} contract.
     */
    private void applyThemeToComponent(@Nullable Component component, Theme theme) {
        assert SwingUtilities.isEventDispatchThread() : "applyThemeToComponent must be called on EDT";
        if (component == null) {
            return;
        }

        switch (component) {
            // 1. Give ThemeAware components first crack at theming themselves
            case ThemeAware aware -> aware.applyTheme(this);
            // 2. Plain RSyntaxTextArea
            case RSyntaxTextArea area -> theme.apply(area);
            // 3. Handle the common case of RSyntaxTextArea wrapped in a JScrollPane
            case JScrollPane scrollPane -> {
                var viewport = scrollPane.getViewport();
                if (viewport != null) {
                    @Nullable Component view = viewport.getView();
                    applyThemeToComponent(view, theme);
                }
            }
            default -> { }
        }

        // 4. Recurse into child components (if any)
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyThemeToComponent(child, theme);
            }
        }
    }

    /**
     * Checks if dark theme is currently active
     * @return true if dark theme is active
     */
    public boolean isDarkTheme() {
        return THEME_DARK.equalsIgnoreCase(MainProject.getTheme());
    }

    /**
     * Registers a popup menu to receive theme updates
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
     * Applies the current theme to a specific RSyntaxTextArea
     * @param textArea The text area to apply theme to
     */
    public void applyCurrentThemeToComponent(RSyntaxTextArea textArea) {
        loadRSyntaxTheme(isDarkTheme()).ifPresent(theme ->
            SwingUtilities.invokeLater(() -> theme.apply(textArea))
        );
    }

    /**
     * Registers custom icons for the application based on the current theme
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
                var dirPath = java.nio.file.Paths.get(directoryUrl.toURI());
                try (var stream = java.nio.file.Files.list(dirPath)) {
                    stream.filter(path -> {
                        var fileNamePath = path.getFileName();
                        if (fileNamePath == null) {
                            return false;
                        }
                        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".png") || name.endsWith(".gif");
                    }).forEach(path -> {
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

                    try (var jar = new java.util.jar.JarFile(jarFile)) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (entryName.startsWith(entryPath) && !entry.isDirectory()) {
                                var filename = entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                                if (filename.endsWith(".png") || filename.endsWith(".gif")) {
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
     * @param key The UIManager key for the icon
     * @param resourcePath The resource path to the icon file
     */
    private static void registerIcon(String key, String resourcePath) {
        URL url = GuiTheme.class.getResource(resourcePath);
        if (url != null) {
            UIManager.put(key, new ImageIcon(url));
        } else {
            logger.warn("Icon resource {} not found for key {}", resourcePath, key);
        }
    }
}
