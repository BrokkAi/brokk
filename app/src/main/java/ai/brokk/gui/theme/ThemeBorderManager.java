package ai.brokk.gui.theme;

import ai.brokk.Brokk;
import ai.brokk.MainProject;
import ai.brokk.gui.highcontrast.SwingExternalBorderDecorator;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Unified border manager that reads border properties from theme files
 * and applies them to windows when enabled by the theme configuration.
 *
 * Theme Configuration:
 * - ui.Brokk.windowBorder.enabled: boolean - whether borders should be applied
 * - ui.Brokk.windowBorder.color: string - hex color for the border
 * - ui.Brokk.windowBorder.width: integer - border width in pixels
 * - ui.Brokk.windowBorder.cornerRadius: integer - corner radius (0 for sharp corners)
 */
public final class ThemeBorderManager {

    private static final Logger logger = LogManager.getLogger(ThemeBorderManager.class);

    @Nullable
    private static volatile ThemeBorderManager instance;

    // Map of tracked windows to their decorators
    private final Map<Window, SwingExternalBorderDecorator> decorators = new ConcurrentHashMap<>();

    // Current border configuration
    private volatile BorderConfig currentConfig = BorderConfig.DISABLED;

    // Global AWT event listener for automatic window detection
    @Nullable
    private AWTEventListener windowEventListener;

    private ThemeBorderManager() {}

    public static synchronized ThemeBorderManager getInstance() {
        if (instance == null) {
            instance = new ThemeBorderManager();
        }
        return instance;
    }

    /**
     * Initialize the border manager with the current theme.
     * Safe to call early; will schedule any heavy UI work on the EDT.
     */
    public void init() {
        logger.info("ThemeBorderManager.init called");

        // Load border configuration from current theme
        updateBorderConfiguration();

        // Install global AWT event listener for automatic window detection
        installGlobalWindowListener();

        SwingUtilities.invokeLater(() -> {
            try {
                // Register Brokk's tracked project windows first (if any)
                try {
                    Brokk.getOpenProjectWindows().values().forEach(ch -> registerWindow(ch.getFrame()));
                } catch (Exception ex) {
                    logger.debug("Error scanning Brokk open project windows: {}", ex.getMessage());
                }

                // Also scan all current windows
                for (Window w : Window.getWindows()) {
                    try {
                        registerWindow(w);
                    } catch (Exception ex) {
                        logger.debug("Error registering window {}: {}", w, ex.getMessage());
                    }
                }
            } catch (Throwable t) {
                logger.warn("ThemeBorderManager.init failed: {}", t.getMessage(), t);
            }
        });
    }

    /**
     * Update border configuration from the current theme and apply changes.
     */
    public void onThemeChanged() {
        logger.info("ThemeBorderManager.onThemeChanged called");

        BorderConfig newConfig = loadBorderConfiguration();
        if (!newConfig.equals(currentConfig)) {
            logger.info("Border configuration changed: {} -> {}", currentConfig, newConfig);
            currentConfig = newConfig;

            SwingUtilities.invokeLater(() -> {
                try {
                    for (Map.Entry<Window, SwingExternalBorderDecorator> e : decorators.entrySet()) {
                        Window window = e.getKey();
                        SwingExternalBorderDecorator dec = e.getValue();

                        if (currentConfig.enabled) {
                            if (!dec.isInstalled()) {
                                logger.info("Installing decorator for window: {}", window.getName());
                                dec.install();
                            }
                            // Update configuration
                            dec.updateConfiguration(currentConfig);
                        } else {
                            if (dec.isInstalled()) {
                                logger.info("Uninstalling decorator for window: {}", window.getName());
                                dec.uninstall();
                            }
                        }
                    }
                } catch (Throwable t) {
                    logger.warn("ThemeBorderManager.onThemeChanged failed: {}", t.getMessage(), t);
                }
            });
        }
    }

    /**
     * Load border configuration from the current theme.
     */
    private BorderConfig loadBorderConfiguration() {
        try {
            String themeName = MainProject.getTheme();
            logger.debug("Loading border configuration for theme: {}", themeName);

            // Check if borders are enabled for this theme
            boolean enabled = getThemeBoolean("Brokk.windowBorder.enabled", false);
            if (!enabled) {
                logger.debug("Window borders disabled for theme: {}", themeName);
                return BorderConfig.DISABLED;
            }

            // Load border properties
            String colorHex = getThemeString("Brokk.windowBorder.color", "#E6E6E6");
            int width = getThemeInt("Brokk.windowBorder.width", 1);
            int cornerRadius = getThemeInt("Brokk.windowBorder.cornerRadius", 0);

            Color color = parseColor(colorHex);

            BorderConfig config = new BorderConfig(enabled, color, width, cornerRadius);
            logger.debug("Loaded border configuration for theme '{}': {}", themeName, config);
            return config;

        } catch (Exception e) {
            logger.warn("Failed to load border configuration: {}", e.getMessage(), e);
            return BorderConfig.DISABLED;
        }
    }

    /**
     * Update the current border configuration without applying changes.
     */
    private void updateBorderConfiguration() {
        currentConfig = loadBorderConfiguration();
    }

    /**
     * Get a boolean property from the current theme.
     */
    private boolean getThemeBoolean(String key, boolean defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                return Boolean.parseBoolean(s);
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme boolean '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Get a string property from the current theme.
     */
    private String getThemeString(String key, String defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value != null) {
                return value.toString();
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme string '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Get an integer property from the current theme.
     */
    private int getThemeInt(String key, int defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s) {
                return Integer.parseInt(s);
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme int '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Parse a hex color string to a Color object.
     * Returns a default light gray color if parsing fails.
     */
    private Color parseColor(String colorHex) {
        try {
            String hex = colorHex.trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }

            if (hex.length() == 6) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return new Color(r, g, b);
            } else if (hex.length() == 8) {
                int a = Integer.parseInt(hex.substring(0, 2), 16);
                int r = Integer.parseInt(hex.substring(2, 4), 16);
                int g = Integer.parseInt(hex.substring(4, 6), 16);
                int b = Integer.parseInt(hex.substring(6, 8), 16);
                return new Color(r, g, b, a);
            }

            logger.debug("Invalid color format '{}', using default", colorHex);
            return new Color(192, 192, 192); // Light gray fallback
        } catch (Exception e) {
            logger.debug("Failed to parse color '{}': {}, using default", colorHex, e.getMessage());
            return new Color(192, 192, 192); // Light gray fallback
        }
    }

    /**
     * Install global AWT event listener for automatic window detection.
     */
    private void installGlobalWindowListener() {
        try {
            windowEventListener = event -> {
                if (event instanceof WindowEvent windowEvent) {
                    Window window = windowEvent.getWindow();
                    if (window instanceof JFrame || window instanceof JDialog) {
                        switch (windowEvent.getID()) {
                            case WindowEvent.WINDOW_OPENED -> {
                                // Register window when it becomes visible
                                SwingUtilities.invokeLater(() -> registerWindow(window));
                            }
                            case WindowEvent.WINDOW_CLOSED -> {
                                // Unregister when closed
                                SwingUtilities.invokeLater(() -> unregisterWindow(window));
                            }
                        }
                    }
                }
            };

            Toolkit.getDefaultToolkit().addAWTEventListener(windowEventListener, AWTEvent.WINDOW_EVENT_MASK);
            logger.info("Installed global AWT event listener for automatic window detection");
        } catch (SecurityException e) {
            logger.warn("Cannot install global AWT event listener due to security restrictions: {}", e.getMessage());
            logger.warn("Manual registration will be required for all windows");
        } catch (Exception e) {
            logger.error("Failed to install global AWT event listener: {}", e.getMessage(), e);
        }
    }

    /**
     * Dispose: remove all overlays, clear listeners, and uninstall global event listener.
     */
    public void dispose() {
        // Remove global AWT event listener
        if (windowEventListener != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(windowEventListener);
                logger.info("Removed global AWT event listener");
            } catch (Exception e) {
                logger.debug("Error removing global AWT event listener: {}", e.getMessage());
            }
        }

        SwingUtilities.invokeLater(() -> {
            decorators.keySet().forEach(this::unregisterWindow);
            decorators.clear();
            currentConfig = BorderConfig.DISABLED;
        });
    }

    /**
     * Register a window to be decorated when borders are enabled.
     * Safe to call multiple times; idempotent for the same Window.
     */
    public void registerWindow(Window w) {
        logger.debug(
                "ThemeBorderManager.registerWindow: window={}, type={}, enabled={}",
                w.getName(),
                w.getClass().getSimpleName(),
                currentConfig.enabled);

        // Only handle frames and dialogs (policy in design)
        if (!(w instanceof JFrame) && !(w instanceof JDialog)) {
            logger.debug("Skipping window registration: not a JFrame or JDialog");
            return;
        }

        // Exclude utility windows and non-opaque windows
        try {
            if (w.getType() == Window.Type.UTILITY) {
                logger.debug("Skipping window registration: UTILITY window type");
                return;
            }
            if (!w.isDisplayable() && !w.isFocusableWindow()) {
                logger.debug("Window not displayable and not focusable, but allowing registration");
                // still allow registration (we'll install later when displayable) but we still create decorator
            }
            // Exclude translucent windows
            Color bg = w.getBackground();
            if (bg != null && bg.getAlpha() < 255) {
                logger.debug("Skipping window registration: translucent window (alpha={})", bg.getAlpha());
                return;
            }
        } catch (Exception ex) {
            logger.debug("Error evaluating window properties: {}", ex.getMessage());
        }

        // Create and store decorator if not present
        decorators.computeIfAbsent(w, window -> {
            logger.debug("Creating new decorator for window: {}", window.getName());
            var dec = new SwingExternalBorderDecorator(window, currentConfig);
            if (currentConfig.enabled) {
                dec.install();
            }
            return dec;
        });
    }

    /**
     * Unregister window and remove decorator immediately.
     */
    public void unregisterWindow(Window w) {
        SwingUtilities.invokeLater(() -> {
            SwingExternalBorderDecorator dec = decorators.remove(w);
            if (dec != null) {
                try {
                    dec.uninstall();
                } catch (Exception ex) {
                    logger.debug("Error uninstalling decorator for {}: {}", w, ex.getMessage(), ex);
                }
            }
        });
    }

    /**
     * Helper: apply to any existing windows (useful to call after late initialization).
     */
    public void applyToExistingWindows() {
        SwingUtilities.invokeLater(() -> {
            for (Window w : Window.getWindows()) {
                try {
                    registerWindow(w);
                } catch (Exception ex) {
                    logger.debug("Error registering window during applyToExistingWindows: {}", ex.getMessage());
                }
            }
        });
    }

    /** For tests / debug: checks if a window is currently registered. */
    public boolean isWindowRegistered(Window w) {
        return decorators.containsKey(w);
    }

    /** For tests / debug: returns whether borders are currently enabled. */
    public boolean isEnabled() {
        return currentConfig.enabled;
    }

    /** For tests / debug: returns the current border configuration. */
    public BorderConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * Configuration class for border properties.
     */
    public static final class BorderConfig {
        public static final BorderConfig DISABLED = new BorderConfig(false, null, 0, 0);

        public final boolean enabled;

        @Nullable
        public final Color color;

        public final int width;
        public final int cornerRadius;

        public BorderConfig(boolean enabled, @Nullable Color color, int width, int cornerRadius) {
            this.enabled = enabled;
            this.color = color;
            this.width = width;
            this.cornerRadius = cornerRadius;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (getClass() != obj.getClass()) return false;
            BorderConfig that = (BorderConfig) obj;
            return enabled == that.enabled
                    && width == that.width
                    && cornerRadius == that.cornerRadius
                    && Objects.equals(color, that.color);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, color, width, cornerRadius);
        }

        @Override
        public String toString() {
            return "BorderConfig{" + "enabled="
                    + enabled + ", color="
                    + color + ", width="
                    + width + ", cornerRadius="
                    + cornerRadius + '}';
        }
    }
}
