package ai.brokk.exception;

import ai.brokk.ExceptionReporter;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.project.MainProject;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GlobalExceptionHandler implements UncaughtExceptionHandler {
    private static final Logger logger = LogManager.getLogger(GlobalExceptionHandler.class);
    private static final ExecutorService executor = ExecutorsUtil.newFixedThreadExecutor("GlobalExceptionHandler", 1);

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        handle(thread, throwable, st -> {});
    }

    public static void handle(Throwable th, Consumer<String> notifier) {
        handle(Thread.currentThread(), th, notifier);
    }

    public static void handle(Throwable th) {
        handle(Thread.currentThread(), th, st -> {});
    }

    /**
     * 1. Log exception
     * 2. Upload it
     * 3. Call notifier
     * 4. Shut down if OOM
     *
     * Note: InterruptedException and CancellationException are globally suppressed here.
     * These are expected, non-exceptional conditions (cancellations). Callers that need
     * special handling (like UserActionManager) may handle them explicitly before calling this.
     */
    public static void handle(Thread thread, Throwable th, Consumer<String> notifier) {
        // Globally suppress IE and CE—these are expected, not exceptional
        if (isCausedBy(th, InterruptedException.class) || isCausedBy(th, CancellationException.class)) {
            logger.debug("Suppressing cancellation/interrupt on thread %s".formatted(thread.getName()), th);
            return;
        }

        // Suppress known JavaFX race condition (JDK-8334593): EmbeddedScene receives
        // pixel scale factor updates after scene disposal
        if (isJavaFxSceneStateNpe(th)) {
            logger.trace("Suppressing JavaFX SceneState NPE on thread {}", thread.getName());
            return;
        }

        logger.error("Uncaught exception on thread %s".formatted(thread), th);

        // Prevent recursive handling: abort if this method is already on the call stack.
        var currentStack = Thread.currentThread().getStackTrace();
        // 0 -> getStacktrace()
        // 1 -> handle()
        // [optional handle overload]
        // 2 or 3 -> start of caller
        for (int i = 3; i < currentStack.length; i++) {
            var element = currentStack[i];
            if (element.getClassName().equals(GlobalExceptionHandler.class.getName())
                    && element.getMethodName().equals("handle")) {
                logger.warn("Recursive exception handling detected; aborting further handling.");
                return;
            }
        }

        ExceptionReporter.tryReportException(th);

        // ensure that a notifier that does blocking things doesn't cause issues
        executor.submit(() -> {
            notifier.accept("Internal error %s%s"
                    .formatted(th.getClass().getName(), th.getMessage() == null ? "" : ": " + th.getMessage()));
        });

        if (isOomError(th)) {
            shutdownWithRecovery();
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

    private static boolean isOomError(Throwable th) {
        return isCausedBy(th, OutOfMemoryError.class);
    }

    /**
     * @return true if the given Class is part of the throwable cause chain.
     */
    public static boolean isCausedBy(Throwable th, Class<? extends Throwable> cls) {
        if (cls.isInstance(th)) return true;
        else if (th.getCause() == null) return false;
        else return isCausedBy(th.getCause(), cls);
    }

    /**
     * Detects JDK-8334593: EmbeddedScene receives pixel scale factor updates after scene disposal.
     */
    private static boolean isJavaFxSceneStateNpe(Throwable th) {
        if (!(th instanceof NullPointerException)) {
            return false;
        }
        var message = th.getMessage();
        if (message == null || !message.contains("sceneState")) {
            return false;
        }
        for (var element : th.getStackTrace()) {
            if ("com.sun.javafx.tk.quantum.GlassScene".equals(element.getClassName())
                    && "updateSceneState".equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
