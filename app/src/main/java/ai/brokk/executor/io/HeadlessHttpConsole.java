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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Headless implementation of IConsoleIO that maps console I/O events to durable JobEvents.
 * <p>Inherits MemoryConsole so headless runs retain an in-memory transcript that mirrors GUI/CLI behavior alongside the durable token event log.
 *
 * <p>Writes are performed synchronously to the JobStore so the persisted sequence is always authoritative.
 */
public class HeadlessHttpConsole extends MemoryConsole {
    private static final Logger logger = LogManager.getLogger(HeadlessHttpConsole.class);

    private final JobStore jobStore;
    private final String jobId;

    /**
     * Create a new HeadlessHttpConsole.
     *
     * @param jobStore The JobStore for persisting events
     * @param jobId The job ID to append events to
     */
    public HeadlessHttpConsole(JobStore jobStore, String jobId) {
        this.jobStore = jobStore;
        this.jobId = jobId;
    }

    /**
     * Append an event synchronously. This blocks the caller until the event is
     * durably appended to the JobStore and the assigned sequence is available.
     *
     * @param type The event type
     * @param data The event data (arbitrary JSON-serializable object)
     */
    private void enqueueEvent(String type, @Nullable Object data) {
        try {
            long seq = jobStore.appendEvent(jobId, JobEvent.of(type, data));
            logger.debug("Appended event type={} seq={}", type, seq);
        } catch (IOException e) {
            logger.error("Failed to append event of type {}", type, e);
        }
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
     * Graceful shutdown is a no-op because writes are synchronous.
     *
     * @param timeoutSeconds Ignored in this implementation
     */
    public void shutdown(int timeoutSeconds) {
        // No-op: events are written synchronously, so nothing to await.
    }

    /**
     * Get the last sequence number of appended events by querying the JobStore.
     * This makes the JobStore the authoritative source of truth.
     *
     * @return The last sequence number, or -1 if no events have been appended or on error
     */
    public long getLastSeq() {
        try {
            var events = jobStore.readEvents(jobId, -1, 0);
            if (events.isEmpty()) {
                return -1;
            }
            return events.get(events.size() - 1).seq();
        } catch (IOException e) {
            logger.warn("Failed to read lastSeq from JobStore for job {}: returning -1", jobId, e);
            return -1;
        }
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
