package io.github.jbellis.brokk.gui.highcontrast;

import io.github.jbellis.brokk.gui.theme.ThemeBorderManager;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Compatibility wrapper for the new ThemeBorderManager.
 *
 * @deprecated Use {@link ThemeBorderManager} instead. This class is kept for backward compatibility.
 */
@Deprecated
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
     * @deprecated Use {@link ThemeBorderManager#init()} instead.
     */
    @Deprecated
    public void init(boolean isHighContrast) {
        logger.info("HighContrastBorderManager.init called - redirecting to ThemeBorderManager");
        this.active = isHighContrast;

        // Redirect to new system
        ThemeBorderManager.getInstance().init();
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
            var dec = new SwingExternalBorderDecorator(window, ThemeBorderManager.BorderConfig.DISABLED);
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
     * @deprecated Use {@link ThemeBorderManager#onThemeChanged()} instead.
     */
    @Deprecated
    public void onThemeChanged(boolean enabled) {
        logger.info("HighContrastBorderManager.onThemeChanged called - redirecting to ThemeBorderManager");
        this.active = enabled;

        // Redirect to new system
        ThemeBorderManager.getInstance().onThemeChanged();
    }

    /**
     * @deprecated Use {@link ThemeBorderManager#applyToExistingWindows()} instead.
     */
    @Deprecated
    public void applyToExistingWindows() {
        logger.info("HighContrastBorderManager.applyToExistingWindows called - redirecting to ThemeBorderManager");

        // Redirect to new system
        ThemeBorderManager.getInstance().applyToExistingWindows();
    }

    /** For tests / debug: checks if a window is currently registered. */
    public boolean isWindowRegistered(Window w) {
        return ThemeBorderManager.getInstance().isWindowRegistered(w);
    }

    /** For tests / debug: returns whether manager is currently active (theme on). */
    public boolean isActive() {
        return ThemeBorderManager.getInstance().isEnabled();
    }
}
