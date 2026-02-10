package ai.brokk.gui.search;

import java.awt.*;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Utility class for common scrolling operations in search components. */
public final class ScrollingUtils {
    private static final Logger logger = LogManager.getLogger(ScrollingUtils.class);

    private ScrollingUtils() {
        // Utility class
    }

    /** Finds the parent JScrollPane of a component. */
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

    /** Finds the parent JViewport of a component. */
    @Nullable
    public static JViewport findParentViewport(Component component) {
        JScrollPane scrollPane = findParentScrollPane(component);
        return scrollPane != null ? scrollPane.getViewport() : null;
    }

    /**
     * Scrolls a component into view, moving the viewport only as much as needed to ensure
     * the component is fully visible. If it is already fully visible, no scrolling occurs.
     *
     * <p>The primary goal
     * is minimal scrolling:
     * <ul>
     *   <li>If the component is fully within the current view, do nothing.</li>
     *   <li>If it is above the view, scroll up just enough so its top aligns with the
     *       top of the viewport.</li>
     *   <li>If it is below the view, scroll down just enough so its bottom aligns with
     *       the bottom of the viewport.</li>
     * </ul>
     */
    public static void scrollToComponent(JComponent component) {
        SwingUtilities.invokeLater(() -> {
            JScrollPane scrollPane = findParentScrollPane(component);

            // Fallback: direct viewport or component-based scrolling when no JScrollPane found
            if (scrollPane == null) {
                if (component.getParent() instanceof JViewport viewport) {
                    Rectangle bounds = component.getBounds();
                    Rectangle viewRect = viewport.getViewRect();

                    int currentY = viewRect.y;
                    int compTop = bounds.y;
                    int compBottom = bounds.y + bounds.height;

                    int desiredY = currentY;

                    // Component completely above current view
                    if (compTop < viewRect.y) {
                        desiredY = compTop;
                    }
                    // Component completely below current view
                    else if (compBottom > viewRect.y + viewRect.height) {
                        desiredY = compBottom - viewRect.height;
                    }

                    desiredY = Math.max(0, desiredY);
                    viewport.setViewPosition(new Point(viewRect.x, desiredY));
                } else {
                    component.scrollRectToVisible(new Rectangle(0, 0, component.getWidth(), component.getHeight()));
                }
                return;
            }

            JViewport viewport = scrollPane.getViewport();
            Component view = viewport.getView();
            if (view == null) {
                return;
            }

            // Translate component bounds into the coordinate system of the viewport's view
            Rectangle compBoundsInView =
                    SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), view);
            Rectangle viewRect = viewport.getViewRect();

            int currentY = viewRect.y;
            int compTop = compBoundsInView.y;
            int compBottom = compBoundsInView.y + compBoundsInView.height;
            int viewTop = viewRect.y;
            int viewBottom = viewRect.y + viewRect.height;

            int desiredY = currentY;

            // If component is already fully visible, no scrolling is needed.
            if (compTop >= viewTop && compBottom <= viewBottom) {
                logger.trace(
                        "Component {} already fully visible; no scroll needed",
                        component.getClass().getSimpleName());
            } else if (compTop < viewTop) {
                // Component is above the current view: align its top with the top of the viewport.
                desiredY = compTop;
            } else if (compBottom > viewBottom) {
                // Component is below the current view: align its bottom with the bottom of the viewport.
                desiredY = compBottom - viewRect.height;
            }

            // Clamp to valid scroll range
            int maxY = Math.max(0, view.getHeight() - viewRect.height);
            desiredY = Math.max(0, Math.min(desiredY, maxY));

            if (desiredY != currentY) {
                viewport.setViewPosition(new Point(viewRect.x, desiredY));
                logger.trace(
                        "Scrolled to component {} from y={} to y={}",
                        component.getClass().getSimpleName(),
                        currentY,
                        desiredY);
            } else {
                logger.trace(
                        "No vertical scroll adjustment needed for component {} (y={})",
                        component.getClass().getSimpleName(),
                        currentY);
            }
        });
    }

    /** Centers a rectangle in the viewport. */
    public static void centerRectInViewport(JViewport viewport, Rectangle targetRect, double positionRatio) {
        int viewportHeight = viewport.getHeight();
        int targetY = Math.max(0, (int) (targetRect.y - viewportHeight * positionRatio));

        Rectangle viewRect = viewport.getViewRect();
        viewRect.y = targetY;

        Component view = viewport.getView();
        if (view instanceof JComponent jComponent) {
            jComponent.scrollRectToVisible(viewRect);
        }
    }
}
