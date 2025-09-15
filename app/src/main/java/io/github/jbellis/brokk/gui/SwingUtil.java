package io.github.jbellis.brokk.gui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SwingUtil {
    private static final Logger logger = LogManager.getLogger(SwingUtil.class);
    private static final String PRIMARY_BUTTON_KEY = "brokk.primaryButton";

    /**
     * Executes a Callable on the EDT and handles exceptions properly. Use this instead of Swingutilities.invokeAndWait.
     *
     * @param task The task to execute
     * @param <T> The return type
     * @param defaultValue Value to return if execution fails
     * @return Result from task or defaultValue if execution fails
     */
    public static <T> @Nullable T runOnEdt(Callable<@Nullable T> task, @Nullable T defaultValue) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return task.call();
            }

            final CompletableFuture<T> future = new CompletableFuture<>();

            SwingUtilities.invokeAndWait(() -> {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Thread interrupted", e);
            return defaultValue;
        } catch (Exception e) {
            logger.warn("Execution error", e);
            return defaultValue;
        }
    }

    /**
     * Executes a Runnable on the EDT and handles exceptions properly. Use this instead of Swingutilities.invokeAndWait.
     */
    public static boolean runOnEdt(Runnable task) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Thread interrupted", e);
            return false;
        } catch (InvocationTargetException e) {
            logger.error("Execution error", e.getCause());
            return false;
        }
    }

    private static @Nullable Icon loadUIIcon(@Nullable String iconKey) {
        if (iconKey == null || iconKey.trim().isEmpty()) {
            return null;
        }

        try {
            var value = UIManager.get(iconKey);
            if (value instanceof Icon icon) {
                // Verify the icon is actually usable by checking its dimensions
                if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
                    return icon;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load UI icon for key '{}': {}", iconKey, e.getMessage());
        }

        return null;
    }

    /**
     * Safely loads an icon from the UIManager theme with fallback support. Tries the primary icon key first, then falls
     * back to a reliable default icon.
     *
     * @param iconKey The UIManager key for the desired icon (e.g., "FileView.directoryIcon")
     */
    public static Icon uiIcon(String iconKey) {
        // Always return a theme-aware proxy so icons refresh automatically
        if (loadUIIcon(iconKey) != null) {
            return new ThemedIcon(iconKey);
        }

        // Try common fallback icons in order of preference
        var fallbackKeys = new String[] {
            "OptionPane.informationIcon", // Usually available and neutral
            "FileView.fileIcon", // Generic file icon
            "Tree.leafIcon", // Small document icon
            "OptionPane.questionIcon", // Question mark icon
            "FileView.directoryIcon" // Folder icon
        };

        for (var fallbackKey : fallbackKeys) {
            if (loadUIIcon(fallbackKey) != null) {
                logger.debug("Using fallback icon '{}' for requested key '{}'", fallbackKey, iconKey);
                return new ThemedIcon(fallbackKey);
            }
        }

        // If all else fails, create a simple colored rectangle as last resort
        logger.warn("No UI icons available, creating simple fallback for key '{}'", iconKey);
        return createSimpleFallbackIcon();
    }

    /** Replacement for the deprecated {@code JTextComponent.modelToView(int)}. */
    @org.jetbrains.annotations.Nullable
    public static Rectangle modelToView(JTextComponent comp, int pos) throws BadLocationException {
        var r2d = comp.modelToView2D(pos);
        return r2d == null
                ? null
                : new Rectangle((int) r2d.getX(), (int) r2d.getY(), (int) r2d.getWidth(), (int) r2d.getHeight());
    }

    private static Icon createSimpleFallbackIcon() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                g.setColor(java.awt.Color.GRAY);
                g.fillRect(x, y, 16, 16);
                g.setColor(java.awt.Color.DARK_GRAY);
                g.drawRect(x, y, 15, 15);
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /**
     * Icon wrapper that always fetches the current value from UIManager. By delegating on every call we ensure the
     * image really changes after a theme switch without recreating every component that uses it.
     */
    public static class ThemedIcon implements Icon {

        private final String uiKey;
        private final int displaySize;

        public ThemedIcon(String uiKey) {
            this(uiKey, -1);
        }

        public ThemedIcon(String uiKey, int displaySize) {
            this.uiKey = uiKey;
            this.displaySize = displaySize;
        }

        public String uiKey() {
            return this.uiKey;
        }

        public ThemedIcon withSize(int displaySize) {
            return new ThemedIcon(this.uiKey, displaySize);
        }

        /** Retrieve the up-to-date delegate icon (or a simple fallback). */
        public Icon delegate() {
            Object value = UIManager.get(uiKey);
            if (displaySize > 0) {
                if (value instanceof FlatSVGIcon svgIcon) {
                    return svgIcon.derive(displaySize, displaySize);
                } else if (value instanceof ImageIcon imageIcon) {
                    var image = imageIcon.getImage();
                    var resized = new BufferedImage(displaySize, displaySize, BufferedImage.TYPE_INT_ARGB);
                    var g = resized.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.drawImage(image, 0, 0, displaySize, displaySize, null);
                    g.dispose();
                    return new ImageIcon(resized);
                }
            } else if (value instanceof Icon icon && icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
                return icon;
            }
            return createSimpleFallbackIcon();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            delegate().paintIcon(c, g, x, y);
        }

        @Override
        public int getIconWidth() {
            return delegate().getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return delegate().getIconHeight();
        }
    }

    /**
     * Return a disabled variant for the provided icon if available. This mirrors the behavior previously contained in
     * MaterialButton.createDisabledVersion: handle ThemedIcon wrappers and FlatSVGIcon disabled variants.
     *
     * <p>Note: callers should not pass null; callers in this project guard against null before calling.
     */
    public static javax.swing.Icon disabledIconFor(javax.swing.Icon icon) {
        // Handle the project's ThemedIcon wrapper
        if (icon instanceof ThemedIcon themedIcon) {
            javax.swing.Icon delegate = themedIcon.delegate();
            if (delegate instanceof com.formdev.flatlaf.extras.FlatSVGIcon svgIcon) {
                return svgIcon.getDisabledIcon();
            }
        }

        // Direct FlatSVGIcon case
        if (icon instanceof com.formdev.flatlaf.extras.FlatSVGIcon svgIcon) {
            return svgIcon.getDisabledIcon();
        }

        // Fallback: return original icon (guaranteed non-null by contract)
        return icon;
    }

    /**
     * Apply the MaterialButton visual defaults to a plain JButton so it looks and behaves like the old MaterialButton.
     * This method is safe to call from any thread; it will schedule on the EDT if required.
     *
     * <p>The parameter is nullable for convenience so callers can pass a possibly-null reference without needing to
     * guard.
     */
    public static void applyMaterialStyle(@org.jetbrains.annotations.Nullable javax.swing.JButton b) {
        if (b == null) return;

        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> applyMaterialStyle(b));
            return;
        }

        // Avoid installing twice on the same component
        if (Boolean.TRUE.equals(b.getClientProperty("materialStyleInstalled"))) return;
        b.putClientProperty("materialStyleInstalled", true);

        // Cursor & appearance
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.setBorderPainted(false);
        b.setFocusable(true);
        b.putClientProperty("JButton.buttonType", "borderless"); // Keep FlatLaf integration
        b.setContentAreaFilled(true);
        b.setRolloverEnabled(true);

        // Foreground color (link-like)
        // If the button is marked as a Brokk "primary" button, don't override its foreground here.
        java.awt.Color linkColor = javax.swing.UIManager.getColor("Label.linkForeground");
        if (linkColor == null) linkColor = javax.swing.UIManager.getColor("Label.foreground");
        if (linkColor == null) linkColor = java.awt.Color.BLUE;
        if (!Boolean.TRUE.equals(b.getClientProperty(PRIMARY_BUTTON_KEY))) {
            b.setForeground(linkColor);
        }

        // Disabled icon handling for SVG/themed icons
        javax.swing.Icon ic = b.getIcon();
        if (ic != null) {
            javax.swing.Icon disabled = disabledIconFor(ic);
            // disabledIconFor returns a non-null Icon per contract
            b.setDisabledIcon(disabled);
        }

        // Update cursor and disabled-icon when enabled state changes
        b.addPropertyChangeListener("enabled", evt -> {
            Object nv = evt.getNewValue();
            boolean enabled = nv instanceof Boolean en && en;
            b.setCursor(
                    enabled
                            ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                            : java.awt.Cursor.getDefaultCursor());
            javax.swing.Icon cur = b.getIcon();
            if (cur != null) {
                javax.swing.Icon dis = disabledIconFor(cur);
                b.setDisabledIcon(dis);
            }
        });
    }

    /**
     * Apply the primary button visual style: bright blue background and white text.
     *
     * <p>This method only changes the background and foreground colors and schedules the update on the EDT.
     */
    public static void applyPrimaryButtonStyle(@org.jetbrains.annotations.Nullable javax.swing.JButton b) {
        if (b == null) return;

        // Ensure material-style walker will not override the foreground for this button.
        // Marking the component also avoids losing other material/default behavior.
        runOnEdt(() -> {
            b.putClientProperty(PRIMARY_BUTTON_KEY, true);
            // Bright blue (hex #007BFF) and white text. We intentionally do not alter any other properties.
            b.setBackground(new java.awt.Color(0x007BFF));
            b.setForeground(java.awt.Color.WHITE);
        });
    }
}
