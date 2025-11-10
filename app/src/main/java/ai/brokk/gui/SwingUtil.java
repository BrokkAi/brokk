package ai.brokk.gui;

import ai.brokk.exception.GlobalExceptionHandler;
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
            GlobalExceptionHandler.handle(e, s -> {});
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
    @Nullable
    public static Rectangle modelToView(JTextComponent comp, int pos) throws BadLocationException {
        var r2d = comp.modelToView2D(pos);
        return r2d == null
                ? null
                : new Rectangle((int) r2d.getX(), (int) r2d.getY(), (int) r2d.getWidth(), (int) r2d.getHeight());
    }

    private static Icon createSimpleFallbackIcon() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(Color.GRAY);
                g.fillRect(x, y, 16, 16);
                g.setColor(Color.DARK_GRAY);
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
    public static void applyPrimaryButtonStyle(AbstractButton b) {
        Color bg = UIManager.getColor("Button.default.background");
        if (bg == null) {
            bg = UIManager.getColor("Component.linkColor");
        }

        Color fg = UIManager.getColor("Button.default.foreground");
        if (fg == null) {
            fg = Color.BLACK;
        }

        b.setOpaque(true);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setBorderPainted(false);
        b.setFocusPainted(false);

        // Mark this button so we can re-apply the primary style after Look-and-Feel/theme changes.
        // This avoids leaving an explicitly-set background that becomes stale when UIManager changes.
        b.putClientProperty("brokk.primaryButton", true);
    }

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

        /**
         * Non-blocking trigger to ensure the underlying icon is resolved or that resolution work has been started.
         *
         * <p>This method intentionally avoids expensive synchronous rasterization on the EDT. It calls
         * {@link #delegate()} to provoke lightweight resolution and accesses image width where applicable to nudge any
         * lazy loading.
         */
        public void ensureResolved() {
            try {
                Icon resolved = delegate();
                if (resolved instanceof ImageIcon imageIcon) {
                    var img = imageIcon.getImage();
                    if (img != null) {
                        // Accessing width with a null ImageObserver may trigger lazy loading callbacks without
                        // blocking.
                        img.getWidth(null);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to ensure resolve for UI key '{}': {}", uiKey, e.getMessage());
            }
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
}
