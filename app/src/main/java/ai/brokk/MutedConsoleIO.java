package ai.brokk;

import ai.brokk.agents.BlitzForge;
import ai.brokk.context.Context;
import ai.brokk.gui.InstructionsPanel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A wrapper around IConsoleIO that mutes streaming output (llmOutput) to the MOP while delegating
 * all notifications, errors, and other UI operations to the wrapped IO.
 *
 * This is useful for running agents in parallel where only one should produce visible
 * streaming output, but all should still show notifications (costs, errors, etc.).
 */
public class MutedConsoleIO implements IConsoleIO {
    private final IConsoleIO delegate;

    public MutedConsoleIO(IConsoleIO delegate) {
        this.delegate = delegate;
    }

    @Override
    public void actionComplete() {
        delegate.actionComplete();
    }

    @Override
    public void toolError(String msg, String title) {
        delegate.toolError(msg, title);
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        return delegate.showConfirmDialog(message, title, optionType, messageType);
    }

    @Override
    public int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        return delegate.showConfirmDialog(parent, message, title, optionType, messageType);
    }

    @Override
    public void backgroundOutput(String taskDescription) {
        delegate.backgroundOutput(taskDescription);
    }

    @Override
    public void backgroundOutput(String summary, String details) {
        delegate.backgroundOutput(summary, details);
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        // No-op for muted background agents
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        // No-op for muted background agents
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        // Mute streaming output
    }

    @Override
    public void llmOutput(String token, ChatMessageType type) {
        // Mute streaming output
    }

    @Override
    public void systemNotify(String message, String title, int messageType) {
        delegate.systemNotify(message, title, messageType);
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        delegate.showNotification(role, message);
    }

    @Override
    public void showOutputSpinner(String message) {
        // No-op for muted background agents
    }

    @Override
    public void hideOutputSpinner() {
        // No-op for muted background agents
    }

    @Override
    public void showSessionSwitchSpinner() {
        delegate.showSessionSwitchSpinner();
    }

    @Override
    public void hideSessionSwitchSpinner() {
        delegate.hideSessionSwitchSpinner();
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        // Return empty to avoid transcript contamination for background agents
        return List.of();
    }

    @Override
    public void setTaskInProgress(boolean progress) {
        delegate.setTaskInProgress(progress);
    }

    @Override
    public void postSummarize() {
        delegate.postSummarize();
    }

    @Override
    public void disableHistoryPanel() {
        delegate.disableHistoryPanel();
    }

    @Override
    public void enableHistoryPanel() {
        delegate.enableHistoryPanel();
    }

    @Override
    public void updateCommitPanel() {
        delegate.updateCommitPanel();
    }

    @Override
    public void updateGitRepo() {
        delegate.updateGitRepo();
    }

    @Override
    public void updateContextHistoryTable(Context context) {
        delegate.updateContextHistoryTable(context);
    }

    @Override
    public InstructionsPanel getInstructionsPanel() {
        return delegate.getInstructionsPanel();
    }

    @Override
    public void updateContextHistoryTable() {
        delegate.updateContextHistoryTable();
    }

    @Override
    public void updateWorkspace() {
        delegate.updateWorkspace();
    }

    @Override
    public void disableActionButtons() {
        delegate.disableActionButtons();
    }

    @Override
    public void enableActionButtons() {
        delegate.enableActionButtons();
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return delegate.getBlitzForgeListener(cancelCallback);
    }
}
