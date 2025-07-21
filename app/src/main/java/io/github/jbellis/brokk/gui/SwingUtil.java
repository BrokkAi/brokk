package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public class SwingUtil {
    private static final Logger logger = LogManager.getLogger(SwingUtil.class);

    /**
     * Executes a Callable on the EDT and handles exceptions properly.  Use this instead of Swingutilities.invokeAndWait.
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

    /** Executes a Runnable on the EDT and handles exceptions properly.  Use this instead of Swingutilities.invokeAndWait. */
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
     * Safely loads an icon from the UIManager theme with fallback support.
     * Tries the primary icon key first, then falls back to a reliable default icon.
     *
     * @param iconKey The UIManager key for the desired icon (e.g., "FileView.directoryIcon")
     */
    public static @Nullable Icon uiIcon(String iconKey) {
        // Try primary icon first
        var icon = loadUIIcon(iconKey);
        if (icon != null) {
            return icon;
        }

        // Try common fallback icons in order of preference
        var fallbackKeys = new String[]{
            "OptionPane.informationIcon",   // Usually available and neutral
            "FileView.fileIcon",           // Generic file icon
            "Tree.leafIcon",               // Small document icon
            "OptionPane.questionIcon",     // Question mark icon
            "FileView.directoryIcon"       // Folder icon
        };

        for (var fallbackKey : fallbackKeys) {
            icon = loadUIIcon(fallbackKey);
            if (icon != null) {
                logger.debug("Using fallback icon '{}' for requested key '{}'", fallbackKey, iconKey);
                return icon;
            }
        }

        // If all else fails, create a simple colored rectangle as last resort
        logger.warn("No UI icons available, creating simple fallback for key '{}'", iconKey);
        return createSimpleFallbackIcon();
    }

    /**
     * Replacement for the deprecated {@code JTextComponent.modelToView(int)}.
     */
    @org.jetbrains.annotations.Nullable
    public static Rectangle modelToView(JTextComponent comp, int pos) throws BadLocationException {
        var r2d = comp.modelToView2D(pos);
        return r2d == null
               ? null
               : new Rectangle((int) r2d.getX(),
                               (int) r2d.getY(),
                               (int) r2d.getWidth(),
                               (int) r2d.getHeight());
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
            public int getIconWidth() { return 16; }

            @Override
            public int getIconHeight() { return 16; }
        };
    }
}
