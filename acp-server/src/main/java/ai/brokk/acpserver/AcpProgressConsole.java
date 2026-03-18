package ai.brokk.acpserver;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.acpserver.agent.SyncPromptContext;
import ai.brokk.cli.MemoryConsole;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * IConsoleIO adapter that streams output via ACP session updates.
 * <p>
 * This extends MemoryConsole to maintain transcript state while
 * forwarding output to the ACP client via the SyncPromptContext.
 * <p>
 * Similar to ProgressNotifyingConsole in the MCP server implementation.
 */
@NullMarked
public class AcpProgressConsole extends MemoryConsole {

    private final SyncPromptContext ctx;
    private final IConsoleIO delegate;

    /**
     * Creates an ACP progress console.
     *
     * @param ctx the ACP prompt context for sending updates
     * @param delegate the underlying console to echo notifications to
     */
    public AcpProgressConsole(SyncPromptContext ctx, IConsoleIO delegate) {
        this.ctx = ctx;
        this.delegate = delegate;
        setEchoTo(delegate);
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        // Stream LLM tokens to the client as message chunks
        ctx.sendMessage(token);
    }

    @Override
    public void toolError(String msg, String title) {
        super.toolError(msg, title);
        ctx.sendThought(title + ": " + msg);
    }

    @Override
    public void backgroundOutput(String taskDescription) {
        ctx.sendThought(taskDescription);
    }

    @Override
    public void backgroundOutput(String summary, String details) {
        ctx.sendThought(summary + ": " + details);
    }

    @Override
    public void systemNotify(String message, String title, int messageType) {
        ctx.sendThought(title + ": " + message);
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        super.showNotification(role, message);
        ctx.sendThought(message);
    }

    @Override
    public void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        super.showNotification(role, message, cost);
        ctx.sendThought(message);
    }

    @Override
    public void showTransientMessage(String message) {
        ctx.sendThought(message);
    }

    @Override
    public void showOutputSpinner(String message) {
        ctx.sendThought(message);
    }

    @Override
    public void hideOutputSpinner() {
        // No-op for ACP
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        // Send the task entry content as a message
        String content = taskEntry.toString();
        if (!content.isBlank()) {
            ctx.sendMessage(content);
        }
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        // Reset transcript for new stream
        resetTranscript();
    }

    @Override
    public void setTaskInProgress(boolean progress) {
        // No-op for ACP - managed externally
    }

    @Override
    public void disableActionButtons() {
        // No-op - no GUI
    }

    @Override
    public void enableActionButtons() {
        // No-op - no GUI
    }

    @Override
    public void updateWorkspace() {
        // No-op - no GUI
    }

    @Override
    public void updateGitRepo() {
        // No-op - no GUI
    }

    @Override
    public void updateContextHistoryTable() {
        // No-op - no GUI
    }
}
