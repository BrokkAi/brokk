package ai.brokk.executor.io;

import ai.brokk.TaskEntry;
import ai.brokk.agents.BlitzForge;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobStore;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Headless implementation of IConsoleIO that maps console I/O events to durable JobEvents.
 * <p>Inherits MemoryConsole so headless runs retain an in-memory transcript that mirrors GUI/CLI behavior alongside the durable token event log.
 *
 * <p>All methods are non-blocking: events are enqueued into a single-threaded executor
 * that serializes them to the JobStore's event log. This ensures:
 * <ul>
 *   <li>Sequential consistency: events are written in the order enqueued</li>
 *   <li>Non-blocking caller: LLM streaming and task execution threads are not blocked</li>
 *   <li>Durable: events persist to disk via JobStore</li>
 * </ul>
 */
public class HeadlessHttpConsole extends MemoryConsole {
    private static final Logger logger = LogManager.getLogger(HeadlessHttpConsole.class);

    private final JobStore jobStore;
    private final String jobId;
    private final ExecutorService eventWriter;
    private volatile long lastSeq = -1;

    /**
     * Create a new HeadlessHttpConsole.
     *
     * @param jobStore The JobStore for persisting events
     * @param jobId The job ID to append events to
     */
    public HeadlessHttpConsole(JobStore jobStore, String jobId) {
        this.jobStore = jobStore;
        this.jobId = jobId;

        // Single-threaded executor to serialize event writes
        this.eventWriter = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "HeadlessConsole-EventWriter");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                logger.error("Uncaught exception in event writer thread", throwable);
            });
            return t;
        });
    }

    /**
     * Enqueue an event for asynchronous writing.
     * This method returns immediately; the event is persisted on the event writer thread.
     *
     * @param type The event type
     * @param data The event data (arbitrary JSON-serializable object)
     */
    private void enqueueEvent(String type, @Nullable Object data) {
        eventWriter.execute(() -> {
            try {
                lastSeq = jobStore.appendEvent(jobId, JobEvent.of(type, data));
                logger.debug("Appended event type={} seq={}", type, lastSeq);
            } catch (IOException e) {
                logger.error("Failed to append event of type {}", type, e);
            }
        });
    }

    /**
     * Map LLM token output to LLM_TOKEN event.
     */
    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        super.llmOutput(token, type, isNewMessage, isReasoning);
        var data = Map.of(
                "token", token,
                "messageType", type.name(),
                "isNewMessage", isNewMessage,
                "isReasoning", isReasoning);
        enqueueEvent("LLM_TOKEN", data);
    }

    @Override
    public void actionComplete() {
        // MemoryConsole increments the transcript as tokens stream and fences new messages via isNewMessage,
        // so no additional finalization is required here.
    }

    /**
     * Map notifications to NOTIFICATION event.
     */
    @Override
    public void showNotification(NotificationRole role, String message) {
        var data = Map.of("level", role.name(), "message", message);
        enqueueEvent("NOTIFICATION", data);
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return unused -> HeadlessHttpConsole.this;
    }

    /**
     * Map system notifications to NOTIFICATION event.
     */
    @Override
    public void systemNotify(String message, String title, int messageType) {
        var data = Map.of(
                "level", mapMessageTypeToLevel(messageType),
                "message", message,
                "title", title);
        enqueueEvent("NOTIFICATION", data);
    }

    /**
     * Headless consoles cannot block for confirmation prompts. We emit a CONFIRM_REQUEST event
     * containing {message,title,optionType,messageType,defaultDecision}, return the default
     * decision immediately, and allow API clients to infer headless choices from the event log.
     */
    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        var decision = defaultDecisionFor(optionType);
        emitConfirmRequestEvent(message, title, optionType, messageType, decision);
        return decision;
    }

    @Override
    public int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        return showConfirmDialog(message, title, optionType, messageType);
    }

    /**
     * Map tool errors to ERROR event.
     */
    @Override
    public void toolError(String msg, String title) {
        var data = Map.of(
                "message", msg,
                "title", title);
        enqueueEvent("ERROR", data);
    }

    /**
     * Map context baseline preparation to CONTEXT_BASELINE event.
     * Includes a snippet count for UI guidance.
     */
    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        resetTranscript(); // Mimic GUI clear-before-stream so the next response starts from this baseline.
        var data = Map.of(
                "count", history.size(),
                "snippet", formatHistorySnippet(history));
        enqueueEvent("CONTEXT_BASELINE", data);
    }

    /**
     * Map LLM and history output to CONTEXT_BASELINE event.
     */
    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        resetTranscript(); // Reset transcript before staging baseline + pending entry, mirroring GUI behavior.
        var data = Map.of("count", history.size() + 1, "snippet", formatHistorySnippet(history));
        enqueueEvent("CONTEXT_BASELINE", data);
    }

    /**
     * Map background output to STATE_HINT event.
     */
    @Override
    public void backgroundOutput(String taskDescription) {
        var data = Map.of("name", "backgroundTask", "value", taskDescription);
        enqueueEvent("STATE_HINT", data);
    }

    /**
     * Map background output with details to STATE_HINT event.
     */
    @Override
    public void backgroundOutput(String summary, String details) {
        var data = Map.of(
                "name", "backgroundTask",
                "value", summary,
                "details", details);
        enqueueEvent("STATE_HINT", data);
    }

    /**
     * Map task progress state to STATE_HINT event.
     */
    @Override
    public void setTaskInProgress(boolean progress) {
        var data = Map.of("name", "taskInProgress", "value", progress);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void showOutputSpinner(String message) {
        var data = Map.of("name", "outputSpinner", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void hideOutputSpinner() {
        var data = Map.of("name", "outputSpinner", "value", false);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void showSessionSwitchSpinner() {
        var data = Map.of("name", "sessionSwitchSpinner", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void hideSessionSwitchSpinner() {
        var data = Map.of("name", "sessionSwitchSpinner", "value", false);
        enqueueEvent("STATE_HINT", data);
    }

    /**
     * Map action button state to STATE_HINT events.
     */
    @Override
    public void disableActionButtons() {
        var data = Map.of("name", "actionButtonsEnabled", "value", false);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void enableActionButtons() {
        var data = Map.of("name", "actionButtonsEnabled", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void updateWorkspace() {
        var data = Map.of("name", "workspaceUpdated", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void updateGitRepo() {
        var data = Map.of("name", "gitRepoUpdated", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void updateContextHistoryTable() {
        var data = Map.of("name", "contextHistoryUpdated", "value", true);
        enqueueEvent("STATE_HINT", data);
    }

    @Override
    public void updateContextHistoryTable(Context context) {
        var data = Map.of("name", "contextHistoryUpdated", "value", true, "count", 1);
        enqueueEvent("STATE_HINT", data);
    }

    /**
     * Gracefully shut down the event writer and await termination.
     *
     * @param timeoutSeconds Maximum seconds to wait for pending events
     */
    public void shutdown(int timeoutSeconds) {
        eventWriter.shutdown();
        try {
            if (!eventWriter.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("Event writer did not terminate within {}s; forcing shutdown", timeoutSeconds);
                var pending = eventWriter.shutdownNow();
                if (!pending.isEmpty()) {
                    logger.warn("Discarded {} pending events", pending.size());
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for event writer termination");
            eventWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the last sequence number of appended events.
     * Useful for resuming polls after reconnection.
     *
     * @return The last sequence number, or -1 if no events have been appended
     */
    public long getLastSeq() {
        return lastSeq;
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /**
     * Emits a {@code CONFIRM_REQUEST} event describing a headless confirmation dialog and returns immediately.
     * The payload contains non-null fields:
     * <ul>
     *     <li>{@code message} - dialog body text (empty string if absent)</li>
     *     <li>{@code title} - dialog title (empty string if absent)</li>
     *     <li>{@code optionType} - Swing {@link javax.swing.JOptionPane} option constant</li>
     *     <li>{@code messageType} - Swing {@link javax.swing.JOptionPane} message constant</li>
     *     <li>{@code defaultDecision} - the automatically selected decision per headless policy</li>
     * </ul>
     * Callers receive the {@code defaultDecision} synchronously, while observers can inspect the durable event.
     */
    private void emitConfirmRequestEvent(
            String message, String title, int optionType, int messageType, int defaultDecision) {
        var data = Map.of(
                "message", message,
                "title", title,
                "optionType", optionType,
                "messageType", messageType,
                "defaultDecision", defaultDecision);
        enqueueEvent("CONFIRM_REQUEST", data);
    }

    /**
     * Chooses the default decision returned to callers when running headless.
     * <ul>
     *     <li>{@link javax.swing.JOptionPane#YES_NO_OPTION} and {@link javax.swing.JOptionPane#YES_NO_CANCEL_OPTION}
     *     yield {@link javax.swing.JOptionPane#YES_OPTION}</li>
     *     <li>{@link javax.swing.JOptionPane#OK_CANCEL_OPTION} yields {@link javax.swing.JOptionPane#OK_OPTION}</li>
     *     <li>All other option types yield {@link javax.swing.JOptionPane#OK_OPTION}</li>
     * </ul>
     */
    private static int defaultDecisionFor(int optionType) {
        return switch (optionType) {
            case javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.YES_NO_CANCEL_OPTION ->
                javax.swing.JOptionPane.YES_OPTION;
            case javax.swing.JOptionPane.OK_CANCEL_OPTION -> javax.swing.JOptionPane.OK_OPTION;
            default -> javax.swing.JOptionPane.OK_OPTION;
        };
    }

    /**
     * Map JOptionPane message types to notification levels.
     */
    private static String mapMessageTypeToLevel(int messageType) {
        return switch (messageType) {
            case javax.swing.JOptionPane.ERROR_MESSAGE -> "ERROR";
            case javax.swing.JOptionPane.WARNING_MESSAGE -> "WARNING";
            case javax.swing.JOptionPane.INFORMATION_MESSAGE -> "INFO";
            case javax.swing.JOptionPane.QUESTION_MESSAGE -> "INFO";
            case javax.swing.JOptionPane.PLAIN_MESSAGE -> "INFO";
            default -> "INFO";
        };
    }

    /**
     * Format a snippet of the task history for diagnostics.
     * Returns a concise string representation.
     */
    private static String formatHistorySnippet(List<TaskEntry> history) {
        if (history.isEmpty()) {
            return "empty";
        }
        return "tasks=%d".formatted(history.size());
    }
}
