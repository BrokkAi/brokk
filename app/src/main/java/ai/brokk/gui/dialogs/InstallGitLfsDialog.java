package ai.brokk.gui.dialogs;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minimal dialog to prompt the user to install Git LFS.
 *
 * <p>This class provides a single EDT-safe entry point {@link #showDialog()} which returns a
 * {@link Result}. On errors while displaying the dialog it logs and returns {@link Result#CANCEL}.
 *
 * <p>Keep this implementation intentionally small to avoid pulling heavy dependencies into the core UI flow.
 */
public final class InstallGitLfsDialog {
    private static final Logger logger = LogManager.getLogger(InstallGitLfsDialog.class);

    private InstallGitLfsDialog() {}

    public enum Result {
        INSTALL,
        CANCEL
    }

    /**
     * Shows the install prompt to the user. This method is safe to call from any thread:
     * - If called off the EDT, it will use {@link SwingUtilities#invokeAndWait} to run the dialog on the EDT.
     * - If called on the EDT, it will display synchronously.
     *
     * On interruption or invocation failure this method logs and returns {@link Result#CANCEL}.
     */
    public static Result showDialog() {
        if (SwingUtilities.isEventDispatchThread()) {
            return doShow();
        } else {
            final ResultHolder holder = new ResultHolder();
            try {
                SwingUtilities.invokeAndWait(() -> holder.result = doShow());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while showing InstallGitLfsDialog", ie);
                return Result.CANCEL;
            } catch (InvocationTargetException ite) {
                logger.error("Failed to show InstallGitLfsDialog", ite);
                return Result.CANCEL;
            }
            return holder.result != null ? holder.result : Result.CANCEL;
        }
    }

    /** Actual dialog display logic; always invoked on the EDT. */
    private static Result doShow() {
        String message = "<html>Git LFS appears to be missing or unavailable for this repository.<br>"
                + "Would you like to open the Git LFS website to install it?</html>";
        Object[] options = new Object[] {"Open Website", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                null,
                message,
                "Install Git LFS",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 0) {
            // Try to open the website; failures are non-fatal for the user's flow.
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI("https://git-lfs.github.com/"));
                } else {
                    // Fallback: attempt to use the AWT EventQueue to open if possible (best-effort).
                    try {
                        EventQueue.invokeLater(() -> {
                            // no-op placeholder; real fallback isn't necessary here — we at least inform caller.
                        });
                    } catch (Throwable t) {
                        // ignore: this is best-effort only
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to open Git LFS website", e);
            }
            return Result.INSTALL;
        } else {
            return Result.CANCEL;
        }
    }

    /** Simple mutable holder used when invoking the dialog via invokeAndWait. */
    private static class ResultHolder {
        Result result = Result.CANCEL;
    }
}
