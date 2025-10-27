package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.gui.InstructionsPanel;
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
        llmOutput(taskEntry.toString(), ChatMessageType.CUSTOM);
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

    enum MessageSubType {
        Run,
        Ask,
        Code,
        Architect,
        Search,
        BuildError,
        CommandOutput
    }

    void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning);

    default void llmOutput(String token, ChatMessageType type) {
        llmOutput(token, type, false, false);
    }

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
        llmOutput("\n" + message, ChatMessageType.CUSTOM, true, false);
    }

    default void showOutputSpinner(String message) {}

    default void hideOutputSpinner() {}

    default void showSessionSwitchSpinner() {}

    default void hideSessionSwitchSpinner() {}

    default List<ChatMessage> getLlmRawMessages() {
        throw new UnsupportedOperationException();
    }

    default void setTaskInProgress(boolean progress) {
        // pass
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

    default void updateCommitPanel() {
        // pass
    }

    default void updateGitRepo() {
        // pass
    }

    default void updateContextHistoryTable(Context context) {
        // pass
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
        CONFIRM,
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
