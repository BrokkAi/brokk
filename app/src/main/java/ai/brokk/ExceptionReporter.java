package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.InternalServerException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Reports uncaught exceptions to the Brokk server for monitoring and debugging purposes. This class handles
 * asynchronous reporting with deduplication to avoid flooding the server.
 *
 * <p><b>Design Note: LLM Provider Error Flow (Issue #2578)</b>
 * <ul>
 *   <li><b>Source:</b> Provider errors (e.g., Anthropic 500) are caught by {@code JdkHttpClient} and passed to
 *   {@code StreamingChatResponseHandler.onError} in {@code Llm.java}.</li>
 *   <li><b>Mapping:</b> {@code dev.langchain4j.internal.ExceptionMapper} converts HTTP 5xx responses into
 *   {@code dev.langchain4j.exception.InternalServerException}.</li>
 *   <li><b>Reporting Path:</b> These exceptions are currently reported as client exceptions because:
 *     <ol>
 *       <li>They may be surfaced via {@code ContextManager.reportException} if caught in background tasks.</li>
 *       <li>The {@code ExceptionReporter} does not distinguish between operational provider noise (like transient
 *       500s or 503s) and actual client-side logic bugs.</li>
 *     </ol>
 *   </li>
 *   <li><b>Current Behavior:</b> Any {@code Throwable} passed to {@code reportException} is sent to the backend,
 *   including {@code InternalServerException}, {@code HttpException}, and {@code RetriableException}. This leads
 *   to "pollution" of client error logs with provider-side issues.</li>
 * </ul>
 */
public class ExceptionReporter {
    private static final Logger logger = LogManager.getLogger(ExceptionReporter.class);

    private final Supplier<ReportingService> serviceSupplier;

    // Deduplication: track when we last reported each exception signature
    private final ConcurrentHashMap<String, Long> reportedExceptions = new ConcurrentHashMap<>();

    // Only report the same exception once per hour
    private static final long DEDUPLICATION_WINDOW_MS = TimeUnit.HOURS.toMillis(1);

    // Maximum stacktrace length to send (prevent extremely large payloads)
    private static final int MAX_STACKTRACE_LENGTH = 10000;

    public ExceptionReporter(Supplier<ReportingService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    public ExceptionReporter(ReportingService service) {
        this(() -> service);
    }

    /**
     * Reports an exception to the Brokk server asynchronously. This method never throws exceptions - failures are
     * logged but do not propagate.
     *
     * @param throwable The exception to report (must not be null)
     */
    @Blocking
    public void reportException(Throwable throwable) {
        reportException(throwable, Map.of());
    }

    /**
     * Reports an exception to the Brokk server asynchronously with optional context fields. This method never throws
     * exceptions - failures are logged but do not propagate.
     *
     * @param throwable The exception to report (must not be null)
     * @param optionalFields Optional context fields to include with the report
     */
    @Blocking
    public void reportException(Throwable throwable, Map<String, String> optionalFields) {
        // Always write to local log file first for debugging, even if we don't report to backend
        writeLocalErrorReport(throwable, optionalFields);

        if (!shouldReport(throwable)) {
            logger.debug(
                    "Suppressing client exception report for provider internal server error: {}", throwable.toString());
            return;
        }

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

        try {
            String clientVersion = BuildInfo.version;
            ReportingService service = serviceSupplier.get();
            service.reportClientException(stacktrace, clientVersion, optionalFields);
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
    }

    private boolean shouldReport(Throwable throwable) {
        // Return false for provider-side internal server errors (InternalServerException), true otherwise.
        // We treat InternalServerException (produced by dev.langchain4j.internal.ExceptionMapper for HTTP 5xx)
        // as expected provider-side operational noise: we still notify the user and log locally, but we do not
        // want to pollute client exception telemetry with them.
        return !(throwable instanceof InternalServerException);
    }

    /**
     * Formats a throwable into a string stacktrace.
     *
     * @param throwable The throwable to format
     * @return Formatted stacktrace string
     */
    public static String formatStackTrace(Throwable throwable) {
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
     * Writes the exception to .brokk/last-error.log in the active project directory.
     */
    private void writeLocalErrorReport(Throwable throwable, Map<String, String> optionalFields) {
        Chrome activeWindow = SwingUtil.runOnEdt(Brokk::getActiveWindow, null);
        if (activeWindow == null) {
            return;
        }

        var project = activeWindow.getContextManager().getProject();
        String stacktrace = formatStackTrace(throwable);

        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp: ").append(Instant.now()).append("\n");
        sb.append("Exception: ").append(throwable.getClass().getName()).append("\n");
        sb.append("Message:   ").append(throwable.getMessage()).append("\n");

        if (!optionalFields.isEmpty()) {
            sb.append("Context:\n");
            optionalFields.forEach(
                    (k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }

        sb.append("\nStacktrace:\n").append(stacktrace).append("\n");

        try {
            ProjectFile errorLog = new ProjectFile(project.getRoot(), AbstractProject.BROKK_DIR + "/last-error.log");
            errorLog.write(sb.toString());
        } catch (Exception e) {
            logger.warn("Failed to write local error report: {}", e.getMessage());
        }
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

        SwingUtilities.invokeLater(() -> {
            Chrome activeWindow = Brokk.getActiveWindow();
            if (activeWindow == null) {
                logger.warn("Unable to report exceptions in headless mode");
                return;
            }

            var cm = activeWindow.getContextManager();
            cm.reportException(throwable);
        });
    }

    /**
     * Interface for services that can report client exceptions.
     * This interface allows for testing without requiring full Service initialization.
     */
    public interface ReportingService {
        /**
         * Reports a client exception to the server.
         *
         * @param stacktrace The formatted stack trace of the exception
         * @param clientVersion The version of the client application
         * @return JsonNode response from the server
         * @throws IOException if the HTTP request fails
         */
        default JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException {
            return reportClientException(stacktrace, clientVersion, Map.of());
        }

        /**
         * Reports a client exception to the server with optional context fields.
         *
         * @param stacktrace The formatted stack trace of the exception
         * @param clientVersion The version of the client application
         * @param optionalFields Optional context fields to include in the report
         * @return JsonNode response from the server
         * @throws IOException if the HTTP request fails
         */
        JsonNode reportClientException(String stacktrace, String clientVersion, Map<String, String> optionalFields)
                throws IOException;
    }
}
