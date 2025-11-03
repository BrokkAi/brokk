package ai.brokk;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reports uncaught exceptions to the Brokk server for monitoring and debugging purposes. This class handles
 * asynchronous reporting with deduplication to avoid flooding the server.
 */
public class ExceptionReporter {
    private static final Logger logger = LogManager.getLogger(ExceptionReporter.class);

    private final Supplier<IExceptionReportingService> serviceSupplier;

    // Deduplication: track when we last reported each exception signature
    private final ConcurrentHashMap<String, Long> reportedExceptions = new ConcurrentHashMap<>();

    // Only report the same exception once per hour
    private static final long DEDUPLICATION_WINDOW_MS = TimeUnit.HOURS.toMillis(1);

    // Maximum stacktrace length to send (prevent extremely large payloads)
    private static final int MAX_STACKTRACE_LENGTH = 10000;

    public ExceptionReporter(Supplier<IExceptionReportingService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    public ExceptionReporter(IExceptionReportingService service) {
        this(() -> service);
    }

    /**
     * Reports an exception to the Brokk server asynchronously. This method never throws exceptions - failures are
     * logged but do not propagate.
     *
     * @param throwable The exception to report (must not be null)
     */
    public void reportException(Throwable throwable) {
        // Generate a signature for this exception for deduplication
        String signature = generateExceptionSignature(throwable);

        // Check if we've recently reported this exception
        Long lastReportedTime = reportedExceptions.get(signature);
        long currentTime = System.currentTimeMillis();

        if (lastReportedTime != null && (currentTime - lastReportedTime) < DEDUPLICATION_WINDOW_MS) {
            logger.debug(
                    "Skipping duplicate exception report for {}: {} (last reported {} seconds ago)",
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage(),
                    (currentTime - lastReportedTime) / 1000);
            return;
        }

        // Mark this exception as reported
        reportedExceptions.put(signature, currentTime);

        // Clean up old entries from the deduplication map (keep it bounded)
        if (reportedExceptions.size() > 1000) {
            cleanupOldEntries();
        }

        // Format the stacktrace
        String stacktrace = formatStackTrace(throwable);

        // Report asynchronously to avoid blocking the current thread
        CompletableFuture.runAsync(() -> {
                    try {
                        String clientVersion = BuildInfo.version;
                        IExceptionReportingService service = serviceSupplier.get();
                        service.reportClientException(stacktrace, clientVersion);
                        logger.debug(
                                "Successfully reported exception: {} - {}",
                                throwable.getClass().getSimpleName(),
                                throwable.getMessage());
                    } catch (Exception e) {
                        // Log the failure but don't propagate - we don't want exception reporting
                        // to cause more exceptions
                        logger.warn(
                                "Failed to report exception to server: {} (original exception: {})",
                                e.getMessage(),
                                throwable.getClass().getSimpleName());
                    }
                })
                .exceptionally(ex -> {
                    logger.warn("Unexpected error during async exception reporting", ex);
                    return null;
                });
    }

    /**
     * Formats a throwable into a string stacktrace.
     *
     * @param throwable The throwable to format
     * @return Formatted stacktrace string
     */
    private String formatStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String fullStacktrace = sw.toString();

        // Truncate if too long to avoid extremely large payloads
        if (fullStacktrace.length() > MAX_STACKTRACE_LENGTH) {
            fullStacktrace = fullStacktrace.substring(0, MAX_STACKTRACE_LENGTH) + "\n... (truncated, total length: "
                    + fullStacktrace.length() + " chars)";
        }

        return fullStacktrace;
    }

    /**
     * Generates a signature for an exception to enable deduplication. The signature is based on the exception class and
     * the first few stack frames.
     *
     * @param throwable The throwable to generate a signature for
     * @return A signature string for deduplication
     */
    private String generateExceptionSignature(Throwable throwable) {
        StringBuilder signature = new StringBuilder();
        signature.append(throwable.getClass().getName());

        // Include the message if it's not too long (it might contain variable data)
        String message = throwable.getMessage();
        if (message != null && message.length() < 100) {
            signature.append(":").append(message);
        }

        // Include the first few stack frames to distinguish different locations
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        int framesToInclude = Math.min(3, stackTrace.length);
        for (int i = 0; i < framesToInclude; i++) {
            StackTraceElement frame = stackTrace[i];
            signature
                    .append("|")
                    .append(frame.getClassName())
                    .append(".")
                    .append(frame.getMethodName())
                    .append(":")
                    .append(frame.getLineNumber());
        }

        return signature.toString();
    }

    /**
     * Cleans up old entries from the deduplication map to keep it bounded. Removes entries older than the deduplication
     * window.
     */
    private void cleanupOldEntries() {
        long currentTime = System.currentTimeMillis();
        long cutoffTime = currentTime - DEDUPLICATION_WINDOW_MS;

        reportedExceptions.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);

        logger.debug("Cleaned up old exception deduplication entries, map size: {}", reportedExceptions.size());
    }

    /**
     * Convenience method to report an exception from the active project. This method handles all error cases gracefully
     * and never throws exceptions. Uses the cached ExceptionReporter from the active ContextManager.
     *
     * <p>Exception reporting can be disabled via the exceptionReportingEnabled property in brokk.properties.
     *
     * @param throwable The exception to report
     */
    public static void tryReportException(Throwable throwable) {
        // Check if exception reporting is enabled
        if (!MainProject.getExceptionReportingEnabled()) {
            logger.debug(
                    "Exception reporting is disabled, skipping report for: {}",
                    throwable.getClass().getName());
            return;
        }

        Chrome activeWindow = SwingUtil.runOnEdt(() -> Brokk.getActiveWindow(), null);
        if (activeWindow != null) {
            var cm = activeWindow.getContextManager();
            cm.reportException(throwable);
        }
    }
}
