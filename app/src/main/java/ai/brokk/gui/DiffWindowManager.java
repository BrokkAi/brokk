package ai.brokk.gui;

import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.util.List;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class to manage diff windows and avoid creating duplicate panels for the same content. Provides methods to
 * find existing panels and raise their windows to the front.
 */
public final class DiffWindowManager {

    private DiffWindowManager() {}

    /**
     * Find an existing BrokkDiffPanel that matches the given content.
     *
     * @param leftSources The left sources to match
     * @param rightSources The right sources to match
     * @return The matching panel if found, null otherwise
     */
    @Nullable
    public static BrokkDiffPanel findExistingPanel(List<BufferSource> leftSources, List<BufferSource> rightSources) {

        Frame[] frames = Frame.getFrames();

        for (Frame frame : frames) {
            if (frame == null || !frame.isDisplayable()) {
                continue;
            }

            var brokkPanel = findBrokkDiffPanel(frame);
            if (brokkPanel != null) {
                if (brokkPanel.matchesContent(leftSources, rightSources)) {
                    return brokkPanel;
                }
            }
        }

        return null;
    }

    /**
     * Try to raise an existing window showing the same content instead of creating a new one.
     *
     * @param leftSources The left sources to match
     * @param rightSources The right sources to match
     * @return true if existing window was found and raised, false if new panel should be created
     */
    public static boolean tryRaiseExistingWindow(List<BufferSource> leftSources, List<BufferSource> rightSources) {
        var existingPanel = findExistingPanel(leftSources, rightSources);
        if (existingPanel != null) {
            var window = SwingUtilities.getWindowAncestor(existingPanel);
            if (window != null) {
                raiseWindow(window);
                return true;
            }
        }
        return false;
    }

    /** Raise the given window to the front and give it focus. */
    public static void raiseWindow(Window window) {
        SwingUtilities.invokeLater(() -> {
            // Bring window to front
            window.toFront();

            // Request focus
            window.requestFocus();

            // On some platforms, also need to set visible again
            if (!window.isVisible()) {
                window.setVisible(true);
            }

            // Make sure it's not minimized (iconified)
            if (window instanceof Frame frame && frame.getState() == Frame.ICONIFIED) {
                frame.setState(Frame.NORMAL);
            }
        });
    }

    /** Recursively find the first BrokkDiffPanel in the component hierarchy. */
    @Nullable
    private static BrokkDiffPanel findBrokkDiffPanel(Container root) {
        for (var comp : root.getComponents()) {
            if (comp == null) continue;
            if (comp instanceof BrokkDiffPanel panel) {
                return panel;
            }
            if (comp instanceof Container container) {
                var found = findBrokkDiffPanel(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
