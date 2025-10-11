package io.github.jbellis.brokk.exception;

import io.github.jbellis.brokk.MainProject;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.InvocationTargetException;
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
        try {
            MainProject.setOomFlag();
        } catch (Exception e) {
            logger.error("Could not persist evidence of OutOfMemoryError in application properties.", e);
            // Can't do much more here.
        } finally {
            // This may or may not be shown to the user
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    "The application has run out of memory and will now shut down.",
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
        try {
            var jdeployVersion = System.getProperty("jdeploy.app.version");
            var isJdeploy = jdeployVersion != null;
            final String message;
            if (isJdeploy) {
                logger.info(
                        "Detected JDeploy environment (jdeploy.app.version={}). Showing recovery message.",
                        jdeployVersion);
                message = "<html>"
                        + "The application ran out of memory during the last session.<br>"
                        + "Any active projects have been cleared to prevent this from immediately reoccurring.<br><br>"
                        + "To adjust memory allocation:<br>"
                        + "- Open Settings &gt; Global &gt; General<br>"
                        + "- Increase the memory allocation<br><br>"
                        + "A restart is required for changes to take effect."
                        + "</html>";
            } else {
                logger.info("JDeploy environment not detected. Showing Gradle run memory guidance.");
                message =
                        """
                    <html>
                    The application ran out of memory during the last session.<br><br>
                    When running from source (Gradle), prefer setting <code>JAVA_TOOL_OPTIONS</code> to raise the JVM heap (e.g., <code>-Xmx</code>).<br>
                    Example:<br>
                    <code>JAVA_TOOL_OPTIONS=&quot;-Xmx8G&quot; ./gradlew run</code><br><br>
                    Note: <code>-Dorg.gradle.jvmargs</code> and <code>GRADLE_OPTS</code> configure Gradle's JVM only and do not affect the application's JVM.
                    </html>
                    """;
            }
            SwingUtilities.invokeAndWait(() ->
                    JOptionPane.showMessageDialog(null, message, "Memory Error Recovery", JOptionPane.WARNING_MESSAGE));
        } catch (InterruptedException | InvocationTargetException e) {
            logger.warn("Failed to synchronously show memory recovery message dialog.", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper to recursively check for OOM in the cause chain.
     *
     * @param e the given throwable
     * @return true if a {@link OutOfMemoryError} is part of the throwable cause chain.
     */
    public static boolean isOomError(Throwable e) {
        if (e instanceof OutOfMemoryError) return true;
        else if (e.getCause() == null) return false;
        else return isOomError(e.getCause());
    }
}
