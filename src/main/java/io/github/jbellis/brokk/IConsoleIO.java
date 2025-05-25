package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.gui.InstructionsPanel;
import io.github.jbellis.brokk.util.Messages;

import java.util.List;

public interface IConsoleIO {
    void actionOutput(String msg);

    default void actionComplete() {
    }

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    default int showConfirmDialog(String message, String title, int optionType, int messageType) {
        throw new UnsupportedOperationException();
    }

    default void backgroundOutput(String taskDescription) {
        // pass
    }

    default void backgroundOutput(String summary, String details) {
        // pass
    }

    default void showMessageDialog(String message, String title, int messageType) {
        throw new UnsupportedOperationException();
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

    void llmOutput(String token, ChatMessageType type);

    default void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        var firstMessage = newOutput.messages().getFirst();
        llmOutput(Messages.getText(firstMessage), firstMessage.type());
    }

    default void systemOutput(String message) {
        llmOutput("\n" + message, ChatMessageType.USER);
    }
    
    default void showOutputSpinner(String message) {}

    default void hideOutputSpinner() {}

    default String getLlmOutputText() {
        throw new UnsupportedOperationException();
    }
    
    default List<ChatMessage> getLlmRawMessages() {
        throw new UnsupportedOperationException();
    }

    default void blockLlmOutput(boolean blocked) {
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
        return null;
    }

    default void updateContextHistoryTable() {
        // pass
    }

    default void updateContextTable() {
        // pass
    }

    default void disableActionButtons() {
        // pass
    }

    default void enableActionButtons() {
        // pass
    }
}
