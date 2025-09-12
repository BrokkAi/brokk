package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.desktop.QuitEvent;
import java.awt.desktop.QuitHandler;
import java.awt.desktop.QuitResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Installs an application-wide QuitHandler that coordinates a graceful shutdown. On quit, it scans top-level Frames for
 * BrokkDiffPanel instances and asks each to confirmClose(). If any panel vetoes (user cancelled), the quit is
 * cancelled.
 */
public final class AppQuitHandler {
    private static final Logger logger = LogManager.getLogger(AppQuitHandler.class);

    private AppQuitHandler() {}

    /** Install the QuitHandler. Safe to call multiple times (idempotent attempts to set handler). */
    public static void install() {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            // setQuitHandler is available on Java 9+ (java.awt.desktop)
            desktop.setQuitHandler(new QuitHandler() {
                @Override
                public void handleQuitRequestWith(QuitEvent e, QuitResponse response) {
                    try {
                        // Check if we're already on the EDT - quit events often are
                        final AtomicBoolean allowQuit = new AtomicBoolean(true);

                        if (SwingUtilities.isEventDispatchThread()) {
                            // Already on EDT, execute directly
                            handleQuitOnEdt(allowQuit);
                        } else {
                            // Not on EDT, use invokeAndWait to synchronously execute on EDT
                            SwingUtilities.invokeAndWait(() -> handleQuitOnEdt(allowQuit));
                        }

                        if (allowQuit.get()) {
                            response.performQuit();
                        } else {
                            response.cancelQuit();
                        }

                    } catch (HeadlessException he) {
                        logger.warn(
                                "Headless environment - cannot perform interactive quit handling; proceeding with quit");
                        response.performQuit();
                    } catch (Exception ex) {
                        logger.error("Error during QuitHandler processing; cancelling quit", ex);
                        try {
                            response.cancelQuit();
                        } catch (Exception e2) {
                            logger.warn("Failed to cancel quit response: {}", e2.getMessage(), e2);
                        }
                    }
                }
            });
        } catch (UnsupportedOperationException e) {
            logger.warn("QuitHandler not supported on this platform/runtime: {}", e.getMessage());
        } catch (Exception e) {
            // HeadlessException and other errors end up here; handle headless specially for clarity.
            if (e instanceof HeadlessException) {
                logger.warn("Headless environment - skipping QuitHandler install");
            } else {
                logger.error("Failed to install QuitHandler", e);
            }
        }
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

    /** Handle quit request on the EDT. Updates allowQuit flag based on user interaction. */
    private static void handleQuitOnEdt(AtomicBoolean allowQuit) {
        try {
            // Scan all top-level frames for BrokkDiffPanel components
            Frame[] frames = Frame.getFrames();
            for (Frame f : frames) {
                if (f == null || !f.isDisplayable()) continue;
                var brokkPanel = findBrokkDiffPanel(f);
                if (brokkPanel != null) {
                    if (!brokkPanel.confirmClose(f)) {
                        allowQuit.set(false);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Unexpected error during quit handling on EDT", ex);
            allowQuit.set(false);
        }
    }
}
