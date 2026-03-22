package ai.brokk;

import ai.brokk.agents.BlitzForge;
import ai.brokk.context.Context;
import ai.brokk.gui.InstructionsPanel;
import ai.brokk.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public interface IConsoleIO {
    default void actionComplete() {}

    void toolError(String msg, String title);

    default void toolError(String msg) {
        toolError(msg, "Error");
    }

    default int showConfirmDialog(String message, String title, int optionType, int messageType) {
        throw new UnsupportedOperationException();
    }

    default int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        throw new UnsupportedOperationException();
    }

    default void backgroundOutput(String taskDescription) {
        // pass
    }

    default void backgroundOutput(String summary, String details) {
        // pass
    }

    default void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        llmOutput(taskEntry.toString(), ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
    }

    /**
     * Stages a new history to be displayed before the next LLM stream begins.
     * <p>
     * This is a deferred action. When the first token of the next new message arrives,
     * the output panel will atomically:
     * <ol>
     *     <li>Clear the main output area.</li>
     *     <li>Display the provided {@code history}.</li>
     *     <li>Render the new token.</li>
     * </ol>
     * This mechanism ensures the conversation view is correctly synchronized before a new
     * AI response streams in, preventing UI flicker.
     * <p>
     * The default implementation is a no-op to preserve source compatibility.
     *
     * @param history The task history to display as the new baseline for the next stream.
     */
    default void prepareOutputForNextStream(List<TaskEntry> history) {
        // no-op by default; GUI consoles may override to prepare the Output panel
    }

    void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta);

    /**
     * default implementation just forwards to systemOutput but the Chrome GUI implementation wraps JOptionPane;
     * messageType should correspond to JOP (ERROR_MESSAGE, WARNING_MESSAGE, etc)
     */
    default void systemNotify(String message, String title, int messageType) {
        showNotification(NotificationRole.INFO, message);
    }

    /**
     * Generic, non-blocking notifications for output panels or headless use. Default implementation forwards to
     * systemOutput.
     */
    default void showNotification(NotificationRole role, String message) {
        llmOutput("\n" + message, ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());
    }

    /**
     * Extended notification API that can optionally carry a structured numeric cost value.
     * <p>
     * Default implementation ignores the cost and delegates to the legacy two-argument
     * method to preserve source and binary compatibility for existing implementors.
     */
    default void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        showNotification(role, message);
    }

    default void showOutputSpinner(String message) {}

    default void hideOutputSpinner() {}

    default void showSessionSwitchSpinner() {}

    default void hideSessionSwitchSpinner() {}

    /**
     * Shows a transient message overlay in the output panel.
     * Transient messages are temporary notifications that can be dismissed or replaced.
     * Default implementation is a no-op; GUI consoles should override.
     *
     * @param message The message to display
     */
    default void showTransientMessage(String message) {
        // no-op by default
    }

    /**
     * Hides any currently displayed transient message.
     * Default implementation is a no-op; GUI consoles should override.
     */
    default void hideTransientMessage() {
        // no-op by default
    }

    default List<ChatMessage> getLlmRawMessages() {
        throw new UnsupportedOperationException();
    }

    default void setTaskInProgress(boolean progress) {
        // pass
    }

    /**
     * Signals that a shell command is about to start executing.
     * Default implementation outputs the stage and command via llmOutput.
     * Headless consoles override to emit a COMMAND_START event instead.
     */
    default void commandStart(String stage, String command) {
        llmOutput("\nRunning " + stage + " command:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
        llmOutput(
                command + "\n\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.newMessage().withTerminal(true));
    }

    /**
     * Streams a single line of command output during execution.
     * Default implementation forwards to llmOutput for Swing compatibility.
     * Headless consoles override as no-op since output is delivered via commandResult.
     */
    default void commandOutput(String line) {
        llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal());
    }

    /**
     * Returns true if this console consumes the buffered output delivered via commandResult.
     * When false, ProjectBuildRunner skips buffering command output to avoid memory waste.
     */
    default boolean supportsCommandResult() {
        return false;
    }

    /**
     * Signals that a shell command has finished executing.
     * Default implementation is a no-op (Swing already saw output via commandOutput).
     * Headless consoles override to emit a COMMAND_RESULT event.
     */
    default void commandResult(
            String stage, String command, boolean success, String output, @Nullable String exception) {
        // no-op: Swing consoles already streamed output line-by-line via commandOutput
    }

    //
    // ----- gui hooks -----
    //

    default void postSummarize() {
        // pass
    }

    default void disableHistoryPanel() {
        // pass
    }

    default void enableHistoryPanel() {
        // pass
    }

    default void updateGitRepo() {
        // pass
    }

    default void updateContextHistoryTable(Context context) {
        // pass
    }

    /**
     * Notifies the console that a tool is about to be called.
     * Default implementation is a no-op.
     */
    default void beforeToolCall(ToolExecutionRequest request) {
        // no-op
    }

    /**
     * Notifies the console that a tool call has completed.
     * Default implementation is a no-op.
     */
    default void afterToolOutput(ToolExecutionResult result) {
        // no-op
    }

    default InstructionsPanel getInstructionsPanel() {
        throw new UnsupportedOperationException();
    }

    default void updateContextHistoryTable() {
        // pass
    }

    default void updateWorkspace() {
        // pass
    }

    default void disableActionButtons() {
        // pass
    }

    default void enableActionButtons() {
        // pass
    }

    enum NotificationRole {
        ERROR,
        COST,
        INFO
    }

    /**
     * Returns a BlitzForge.Listener implementation suitable for the current console type (GUI or headless).
     *
     * @param cancelCallback A callback to invoke if the user requests cancellation (e.g., presses Cancel).
     * @return A BlitzForge.Listener instance.
     */
    default BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        throw new UnsupportedOperationException("getBlitzForgeListener not implemented for this console type");
    }
}
