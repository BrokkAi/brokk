package io.github.jbellis.brokk.gui.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Utility class for common scrolling operations in search components.
 */
public final class ScrollingUtils {
    private static final Logger logger = LogManager.getLogger(ScrollingUtils.class);

    private ScrollingUtils() {
        // Utility class
    }

    /**
     * Finds the parent JScrollPane of a component.
     */
    @Nullable
    public static JScrollPane findParentScrollPane(Component component) {
        Container parent = component.getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane) {
                return (JScrollPane) parent;
            }
            if (parent instanceof JViewport && parent.getParent() instanceof JScrollPane) {
                return (JScrollPane) parent.getParent();
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Finds the parent JViewport of a component.
     */
    @Nullable
    public static JViewport findParentViewport(Component component) {
        JScrollPane scrollPane = findParentScrollPane(component);
        return scrollPane != null ? scrollPane.getViewport() : null;
    }

    /**
     * Scrolls a component into view with a specified position ratio.
     */
    public static void scrollToComponent(JComponent component, double positionRatio) {
        SwingUtilities.invokeLater(() -> {
            JScrollPane scrollPane = findParentScrollPane(component);

            if (scrollPane == null) {
                // Fallback: Try to scroll in viewport directly
                if (component.getParent() instanceof JViewport) {
                    Rectangle bounds = component.getBounds();
                    JViewport viewport = (JViewport) component.getParent();
                    Rectangle viewRect = viewport.getViewRect();
                    int desiredY;
                    if (positionRatio == 0.0) {
                        desiredY = Math.max(0, bounds.y);
                    } else {
                        desiredY = Math.max(0, bounds.y - (int)(viewRect.height * positionRatio));
                    }
                    viewport.setViewPosition(new Point(viewRect.x, desiredY));
                } else {
                    component.scrollRectToVisible(new Rectangle(0, 0, component.getWidth(), component.getHeight()));
                }
                return;
            }

            Rectangle bounds = SwingUtilities.convertRectangle(
                component.getParent(),
                component.getBounds(),
                scrollPane.getViewport().getView()
            );
            JViewport viewport = scrollPane.getViewport();
            Rectangle viewRect = viewport.getViewRect();

            // Calculate desired position
            // If positionRatio is 0, scroll to put component at top
            // Otherwise, position it at the ratio from the top
            int desiredY;
            if (positionRatio == 0.0) {
                desiredY = Math.max(0, bounds.y);
            } else {
                desiredY = Math.max(0, bounds.y - (int)(viewRect.height * positionRatio));
            }
            Component view = viewport.getView();
            int maxY = Math.max(0, view.getHeight() - viewRect.height);
            desiredY = Math.min(desiredY, maxY);

            viewport.setViewPosition(new Point(viewRect.x, desiredY));
            logger.trace("Scrolled to component {} at y={}", component.getClass().getSimpleName(), desiredY);
        });
    }

    /**
     * Centers a rectangle in the viewport.
     */
    public static void centerRectInViewport(JViewport viewport, Rectangle targetRect, double positionRatio) {
        int viewportHeight = viewport.getHeight();
        int targetY = Math.max(0, (int)(targetRect.y - viewportHeight * positionRatio));

        Rectangle viewRect = viewport.getViewRect();
        viewRect.y = targetY;

        Component view = viewport.getView();
        if (view instanceof JComponent jComponent) {
            jComponent.scrollRectToVisible(viewRect);
        }
    }
}
