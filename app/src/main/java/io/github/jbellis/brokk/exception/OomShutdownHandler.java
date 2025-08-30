package io.github.jbellis.brokk.exception;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.util.LowMemoryWatcherManager;
import java.lang.Thread.UncaughtExceptionHandler;
import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OomShutdownHandler implements UncaughtExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(OomShutdownHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable throwable) {
        // Check if the error is an OutOfMemoryError
        if (isOomError(throwable)) {
            logger.error("Uncaught OutOfMemoryError detected on thread: {}", t.getName());
            shutdownWithRecovery();
        } else {
            logger.error("An uncaught exception occurred on thread: {}", t.getName(), throwable);
        }
    }

    public static void shutdownWithRecovery() {
        logger.debug("Shutting down due to OOM");
        try {
            MainProject.setOomFlag();
        } catch (Exception e) {
            logger.error("Could not persist evidence of OutOfMemoryError in application properties.", e);
            // Can't do much more here.
        } finally {
            // This may or may not be shown to the user
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    "The application has run out of memory and will now shut down.\n"
                            + "On the next startup, settings will be adjusted to prevent this.",
                    "Critical Memory Error",
                    JOptionPane.ERROR_MESSAGE));

            // A short delay gives the message box a moment to appear.
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                logger.warn("Interrupted while delaying application shutdown.");
            }

            System.exit(1); // Exit with a non-zero status code
        }
    }

    public static void showRecoveryMessage() {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null,
                String.format(
                        """
                                The application ran out of memory during the last session.
                                Any active projects have been cleared to prevent this from immediately reoccurring.
                                To launch Brokk with more allocated memory, use:
                                    jbang run --java-options -Xmx%dM brokk@brokkai/brokk
                                """,
                        LowMemoryWatcherManager.suggestedHeapSizeMb()),
                "Memory Error Recovery",
                JOptionPane.WARNING_MESSAGE));
    }

    /**
     * Helper to recursively check for OOM in the cause chain.
     *
     * @param e the given throwable
     * @return true if a {@link OutOfMemoryError} is part of the throwable cause chain.
     */
    public static boolean isOomError(Throwable e) {
        if (e == null) return false;
        else if (e instanceof OutOfMemoryError) return true;
        else if (e.getCause() == null) return false;
        else return isOomError(e.getCause());
    }
}
