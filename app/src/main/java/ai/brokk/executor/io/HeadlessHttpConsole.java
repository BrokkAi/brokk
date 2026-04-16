package ai.brokk.executor.io;

import ai.brokk.BrokkAuthValidation;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.agents.BlitzForge;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.tools.ApprovalResult;
import ai.brokk.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
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
    private final @Nullable Consumer<Double> costListener;

    /**
     * Create a new HeadlessHttpConsole.
     *
     * @param jobStore The JobStore for persisting events
     * @param jobId The job ID to append events to
     */
    public HeadlessHttpConsole(JobStore jobStore, String jobId) {
        this(jobStore, jobId, null);
    }

    public HeadlessHttpConsole(JobStore jobStore, String jobId, @Nullable Consumer<Double> costListener) {
        this.jobStore = jobStore;
        this.jobId = jobId;
        this.costListener = costListener;
    }

    /**
     * Append an event synchronously. This blocks the caller until the event is
     * durably appended to the JobStore and the assigned sequence is available.
     *
     * @param type The event type
     * @param data The event data (arbitrary JSON-serializable object)
     */
    private void appendEvent(String type, @Nullable Object data) {
        try {
            long seq = jobStore.appendEvent(jobId, JobEvent.of(type, data));
            logger.trace("Appended event type={} seq={}", type, seq);
        } catch (IOException e) {
            logger.error("Failed to append event of type {}", type, e);
        }
    }

    /**
     * Map LLM token output to LLM_TOKEN event.
     */
    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        var data = Map.of(
                "token", token,
                "messageType", type.name(),
                "isNewMessage", meta.isNewMessage(),
                "isReasoning", meta.isReasoning(),
                "isTerminal", meta.isTerminal());
        appendEvent("LLM_TOKEN", data);
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
        appendEvent("NOTIFICATION", data);
    }

    @Override
    public void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        if (role == NotificationRole.COST && cost != null && costListener != null) {
            try {
                costListener.accept(cost);
            } catch (Exception e) {
                logger.warn("Cost listener threw exception", e);
            }
        }

        if (role == NotificationRole.COST && cost != null) {
            // Enriched payload for cost notifications: include structured numeric cost.
            var data = Map.of(
                    "level", role.name(),
                    "message", message,
                    "cost", cost);
            appendEvent("NOTIFICATION", data);
            return;
        }

        // For non-cost notifications or when cost is not provided, preserve legacy behavior.
        showNotification(role, message);
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
        appendEvent("NOTIFICATION", data);
    }

    @Override
    public void brokkAuthValidationUpdated(BrokkAuthValidation validation) {
        var data = new HashMap<String, Object>();
        data.put("state", validation.state().name());
        data.put("valid", validation.valid());
        data.put("subscribed", validation.subscribed());
        data.put("hasBalance", validation.hasBalance());
        data.put("balanceDisplay", validation.balanceDisplay());
        data.put("message", validation.message());
        if (validation.hasBalance()) {
            data.put("balance", validation.balance());
        }
        appendEvent("BROKK_AUTH_VALIDATION", data);
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
        appendEvent("ERROR", data);
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
        appendEvent("CONTEXT_BASELINE", data);
    }

    /**
     * Map LLM and history output to CONTEXT_BASELINE event.
     */
    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        resetTranscript(); // Reset transcript before staging baseline + pending entry, mirroring GUI behavior.
        var data = Map.of("count", history.size() + 1, "snippet", formatHistorySnippet(history));
        appendEvent("CONTEXT_BASELINE", data);
    }

    /**
     * Map background output to STATE_HINT event.
     */
    @Override
    public void backgroundOutput(String taskDescription) {
        var data = Map.of("name", "backgroundTask", "value", taskDescription);
        appendEvent("STATE_HINT", data);
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
        appendEvent("STATE_HINT", data);
    }

    /**
     * Map task progress state to STATE_HINT event.
     */
    @Override
    public void setTaskInProgress(boolean progress) {
        var data = Map.of("name", "taskInProgress", "value", progress);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void showOutputSpinner(String message) {
        var data = Map.of("name", "outputSpinner", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void hideOutputSpinner() {
        var data = Map.of("name", "outputSpinner", "value", false);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void showSessionSwitchSpinner() {
        var data = Map.of("name", "sessionSwitchSpinner", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void hideSessionSwitchSpinner() {
        var data = Map.of("name", "sessionSwitchSpinner", "value", false);
        appendEvent("STATE_HINT", data);
    }

    /**
     * Map action button state to STATE_HINT events.
     */
    @Override
    public void disableActionButtons() {
        var data = Map.of("name", "actionButtonsEnabled", "value", false);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void enableActionButtons() {
        var data = Map.of("name", "actionButtonsEnabled", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void updateWorkspace() {
        var data = Map.of("name", "workspaceUpdated", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void updateGitRepo() {
        var data = Map.of("name", "gitRepoUpdated", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void updateContextHistoryTable() {
        var data = Map.of("name", "contextHistoryUpdated", "value", true);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public void updateContextHistoryTable(Context context) {
        var data = Map.of("name", "contextHistoryUpdated", "value", true, "count", 1);
        appendEvent("STATE_HINT", data);
    }

    @Override
    public boolean supportsCommandResult() {
        return true;
    }

    @Override
    public void commandOutput(String line) {
        // no-op: TUI receives full output via commandResult
    }

    @Override
    public void commandStart(String stage, String command) {
        var data = new HashMap<String, Object>();
        data.put("stage", stage);
        data.put("command", command);
        appendEvent("COMMAND_START", data);
    }

    @Override
    public void commandResult(
            String stage, String command, boolean success, String output, @Nullable String exception) {
        var data = new HashMap<String, Object>();
        data.put("stage", stage);
        data.put("command", command);
        data.put("success", success);
        data.put("output", output);
        if (exception != null && !exception.isBlank()) {
            data.put("exception", exception);
        }
        appendEvent("COMMAND_RESULT", data);
    }

    @Override
    public ApprovalResult beforeToolCall(ToolExecutionRequest request, boolean destructive) {
        var data = new HashMap<String, Object>();
        putIfNonNull(data, "id", request.id());
        putIfNonNull(data, "name", request.name());
        putIfNonNull(data, "arguments", request.arguments());
        data.put("destructive", destructive);
        appendEvent("TOOL_CALL", data);
        return ApprovalResult.APPROVED;
    }

    @Override
    public void afterToolOutput(ToolExecutionResult result) {
        var data = new HashMap<String, Object>();
        putIfNonNull(data, "id", result.toolId());
        putIfNonNull(data, "name", result.toolName());
        putIfNonNull(data, "status", result.status().name());
        putIfNonNull(data, "resultText", result.resultText());
        appendEvent("TOOL_OUTPUT", data);
    }

    /**
     * Get the last sequence number of appended events by querying the JobStore.
     * This makes the JobStore the authoritative source of truth.
     *
     * @return The last sequence number, or -1 if no events have been appended
     */
    public long getLastSeq() {
        return jobStore.getLastSeq(jobId);
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
     *     <li>{@code optionType} - Swing {@link JOptionPane} option constant</li>
     *     <li>{@code messageType} - Swing {@link JOptionPane} message constant</li>
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
        appendEvent("CONFIRM_REQUEST", data);
    }

    /**
     * Chooses the default decision returned to callers when running headless.
     */
    private static int defaultDecisionFor(int optionType) {
        return switch (optionType) {
            case JOptionPane.YES_NO_OPTION, JOptionPane.YES_NO_CANCEL_OPTION -> JOptionPane.YES_OPTION;
            case JOptionPane.OK_CANCEL_OPTION -> JOptionPane.OK_OPTION;
            default -> JOptionPane.OK_OPTION;
        };
    }

    /**
     * Map JOptionPane message types to notification levels.
     */
    private static String mapMessageTypeToLevel(int messageType) {
        return switch (messageType) {
            case JOptionPane.ERROR_MESSAGE -> "ERROR";
            case JOptionPane.WARNING_MESSAGE -> "WARNING";
            case JOptionPane.INFORMATION_MESSAGE -> "INFO";
            case JOptionPane.QUESTION_MESSAGE -> "INFO";
            case JOptionPane.PLAIN_MESSAGE -> "INFO";
            default -> "INFO";
        };
    }

    private static void putIfNonNull(Map<String, Object> data, String key, @Nullable Object value) {
        if (value != null) {
            data.put(key, value);
        }
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
