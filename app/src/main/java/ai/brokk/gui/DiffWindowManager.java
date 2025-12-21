package ai.brokk.gui;

import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import java.awt.Component;
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

}
