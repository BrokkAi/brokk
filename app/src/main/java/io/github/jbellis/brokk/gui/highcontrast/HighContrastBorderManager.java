package io.github.jbellis.brokk.gui.highcontrast;

import io.github.jbellis.brokk.Brokk;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Singleton manager responsible for tracking top-level windows and applying/removing high-contrast external borders.
 *
 * Usage:
 *   HighContrastBorderManager.getInstance().init();
 *   HighContrastBorderManager.getInstance().registerWindow(someFrame);
 *   HighContrastBorderManager.getInstance().onThemeChanged(true); // enable borders
 *   HighContrastBorderManager.getInstance().onThemeChanged(false); // disable borders
 */
public final class HighContrastBorderManager {

    private static final Logger logger = LogManager.getLogger(HighContrastBorderManager.class);

    @Nullable
    private static volatile HighContrastBorderManager instance;

    // Map of tracked windows to their decorators
    private final Map<Window, SwingExternalBorderDecorator> decorators = new ConcurrentHashMap<>();

    // Whether borders should be active
    private volatile boolean active = false;

    // Global AWT event listener for automatic window detection
    @Nullable
    private AWTEventListener windowEventListener;

    private HighContrastBorderManager() {}

    public static synchronized HighContrastBorderManager getInstance() {
        if (instance == null) {
            instance = new HighContrastBorderManager();
        }
        return instance;
    }

    /**
     * Safe to call early; will schedule any heavy UI work on the EDT. Installs global AWT event listener for automatic
     * window detection and scans existing windows.
     *
     * @param isHighContrast whether the current theme is high-contrast (borders should be active)
     */
    public void init(boolean isHighContrast) {
        logger.info("HighContrastBorderManager.init called with isHighContrast={}", isHighContrast);
        this.active = isHighContrast;

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
                logger.warn("HighContrastBorderManager.init failed: {}", t.getMessage(), t);
            }
        });
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
            active = false;
        });
    }

    /**
     * Register a window to be decorated when high-contrast is active.
     * Safe to call multiple times; idempotent for the same Window.
     */
    public void registerWindow(Window w) {
        logger.debug(
                "HighContrastBorderManager.registerWindow: window={}, type={}, active={}",
                w.getName(),
                w.getClass().getSimpleName(),
                active);

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
            var dec = new SwingExternalBorderDecorator(window);
            if (active) {
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
     * Called when theme changes. If enabled==true then overlays are installed on all registered windows.
     * If enabled==false overlays are removed.
     */
    public void onThemeChanged(boolean enabled) {
        logger.info(
                "HighContrastBorderManager.onThemeChanged: enabled={}, registered windows={}",
                enabled,
                decorators.size());
        this.active = enabled;
        SwingUtilities.invokeLater(() -> {
            try {
                for (Map.Entry<Window, SwingExternalBorderDecorator> e : decorators.entrySet()) {
                    Window window = e.getKey();
                    SwingExternalBorderDecorator dec = e.getValue();
                    logger.info(
                            "Processing window: {}, type={}, installed={}",
                            window.getName(),
                            window.getClass().getSimpleName(),
                            dec.isInstalled());

                    if (enabled) {
                        if (!dec.isInstalled()) {
                            logger.info("Installing decorator for window: {}", window.getName());
                            dec.install();
                        } else {
                            logger.info("Decorator already installed for window: {}", window.getName());
                        }
                        // Ensure bounds are current
                        logger.info("Updating bounds for window: {}", window.getName());
                        dec.updateBounds();
                    } else {
                        if (dec.isInstalled()) {
                            logger.info("Uninstalling decorator for window: {}", window.getName());
                            dec.uninstall();
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("HighContrastBorderManager.onThemeChanged failed: {}", t.getMessage(), t);
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

    /** For tests / debug: returns whether manager is currently active (theme on). */
    public boolean isActive() {
        return active;
    }
}
