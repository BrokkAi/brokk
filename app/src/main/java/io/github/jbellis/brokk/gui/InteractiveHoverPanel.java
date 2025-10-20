package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.H_GLUE;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.components.TokenUsageBar;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class InteractiveHoverPanel extends JPanel {
    private final WorkspaceItemsChipPanel chips;
    private final TokenUsageBar tokenBar;
    private @Nullable GlobalHoverManager hoverManager;
    private volatile Collection<ContextFragment> currentHover = List.of();

    public InteractiveHoverPanel(
            JComponent chipContainer,
            JComponent tokenBarContainer,
            WorkspaceItemsChipPanel chips,
            TokenUsageBar tokenBar) {
        super(new BorderLayout(H_GLUE, 0));
        setOpaque(false);
        this.chips = chips;
        this.tokenBar = tokenBar;

        add(chipContainer, BorderLayout.CENTER);
        add(tokenBarContainer, BorderLayout.SOUTH);
    }

    public void install() {
        SwingUtilities.invokeLater(() -> {
            hoverManager = new GlobalHoverManager(this, isInside -> {
                if (!isInside) {
                    setHoverTarget(List.of());
                }
            });
            hoverManager.start();

            chips.setOnHover((frag, entered) -> {
                if (entered && frag != null) {
                    setHoverTarget(List.of(frag));
                }
                // Individual chip exit is ignored to prevent flicker; clearing is handled by the GlobalHoverManager.
            });
            tokenBar.setOnHoverFragments((frags, entered) -> {
                if (entered) {
                    setHoverTarget(List.copyOf(frags));
                }
                // Individual segment exit is ignored; clearing is handled by the GlobalHoverManager.
            });
        });
    }

    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            if (hoverManager != null) {
                hoverManager.stop();
                hoverManager = null;
            }
            chips.setOnHover(null);
            tokenBar.setOnHoverFragments(null);
            setHoverTarget(List.of());
        });
    }

    @Nullable
    public Collection<ContextFragment> getCurrentHover() {
        return currentHover;
    }

    private void setHoverTarget(Collection<ContextFragment> targets) {
        assert SwingUtilities.isEventDispatchThread();
        if (currentHover.size() == targets.size() && currentHover.containsAll(targets)) {
            return;
        }
        this.currentHover = List.copyOf(targets);
        chips.applyGlobalStyling(this.currentHover);
        tokenBar.applyGlobalStyling(this.currentHover);
    }

    /**
     * A global AWT event listener that reliably tracks whether the mouse is inside a designated component or any of its
     * children. This solves the classic Swing problem where mouseExited events can be missed, leading to stuck hover
     * states.
     */
    private static class GlobalHoverManager implements AWTEventListener, AutoCloseable {
        private final JComponent componentToTrack;
        private final Consumer<Boolean> onHoverStateChanged;
        private volatile boolean isMouseInside = false;

        /**
         * @param componentToTrack The component whose bounds (including children) should be monitored.
         * @param onHoverStateChanged A consumer that receives {@code true} when the mouse enters the component's area
         *     and {@code false} when it exits.
         */
        public GlobalHoverManager(JComponent componentToTrack, Consumer<Boolean> onHoverStateChanged) {
            this.componentToTrack = componentToTrack;
            this.onHoverStateChanged = onHoverStateChanged;
        }

        public void start() {
            // Ensure we don't add the listener multiple times
            stop();
            Toolkit.getDefaultToolkit()
                    .addAWTEventListener(this, AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        }

        public void stop() {
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        }

        @Override
        public void close() {
            stop();
        }

        @Override
        public void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent mouseEvent)) {
                return;
            }

            // We only care about events that tell us where the mouse is.
            // MOUSE_ENTERED/EXITED on children can be misleading. MOUSE_MOVED is the source of truth.
            int id = mouseEvent.getID();
            if (id != MouseEvent.MOUSE_MOVED && id != MouseEvent.MOUSE_DRAGGED && id != MouseEvent.MOUSE_ENTERED) {
                return;
            }

            // Find the deepest component at the mouse's screen location
            Component componentUnderMouse = findComponentAt(mouseEvent.getLocationOnScreen());

            boolean isNowInside = componentUnderMouse != null
                    && SwingUtilities.isDescendingFrom(componentUnderMouse, componentToTrack);

            if (isNowInside != isMouseInside) {
                isMouseInside = isNowInside;
                // Fire the callback on the EDT to ensure thread safety with Swing components
                SwingUtilities.invokeLater(() -> onHoverStateChanged.accept(isMouseInside));
            }
        }

        @Nullable
        private Component findComponentAt(Point screenLocation) {
            // To reliably find the component, we must check all visible windows of this application.
            for (var window : java.awt.Window.getWindows()) {
                if (window.isShowing() && window.getBounds().contains(screenLocation)) {
                    // Convert screen coordinates to window coordinates
                    Point windowLocation = new Point(screenLocation);
                    SwingUtilities.convertPointFromScreen(windowLocation, window);
                    // Find the deepest component at that point
                    return SwingUtilities.getDeepestComponentAt(window, windowLocation.x, windowLocation.y);
                }
            }
            return null;
        }
    }
}
