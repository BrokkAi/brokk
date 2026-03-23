package ai.brokk.acpserver;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.acpserver.agent.SyncPromptContext;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.concurrent.ExecutorsUtil;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * IConsoleIO adapter that streams output via ACP session updates.
 * <p>
 * This extends MemoryConsole to maintain transcript state while
 * forwarding output to the ACP client via the SyncPromptContext.
 * <p>
 * Similar to ProgressNotifyingConsole in the MCP server implementation.
 */
public class AcpProgressConsole extends MemoryConsole implements AutoCloseable {

    private static final int FLUSH_SIZE_THRESHOLD = 1024;
    private static final long FLUSH_DELAY_MS = 50;

    private final SyncPromptContext ctx;
    private final StringBuilder tokenBuffer = new StringBuilder();
    private final ScheduledExecutorService flushScheduler =
            ExecutorsUtil.newSingleThreadScheduledExecutor("AcpTokenFlush");
    private @Nullable ScheduledFuture<?> pendingFlush;

    /**
     * Creates an ACP progress console.
     *
     * @param ctx the ACP prompt context for sending updates
     * @param delegate the underlying console to echo notifications to
     */
    public AcpProgressConsole(SyncPromptContext ctx, IConsoleIO delegate) {
        this.ctx = ctx;
        setEchoTo(delegate);
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        synchronized (tokenBuffer) {
            tokenBuffer.append(token);
            if (tokenBuffer.length() >= FLUSH_SIZE_THRESHOLD) {
                flushTokenBuffer();
            } else if (pendingFlush == null) {
                pendingFlush = flushScheduler.schedule(this::flushTokenBuffer, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flushTokenBuffer() {
        synchronized (tokenBuffer) {
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
            if (tokenBuffer.length() > 0) {
                ctx.sendMessage(tokenBuffer.toString());
                tokenBuffer.setLength(0);
            }
        }
    }

    public void shutdown() {
        flushTokenBuffer();
        flushScheduler.shutdown();
    }

    @Override
    public void close() {
        shutdown();
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
        flushTokenBuffer();
        String content = taskEntry.toString();
        if (!content.isBlank()) {
            ctx.sendMessage(content);
        }
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        flushTokenBuffer();
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
