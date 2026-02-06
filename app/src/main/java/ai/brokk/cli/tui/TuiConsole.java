package ai.brokk.cli.tui;

import static java.util.Objects.requireNonNull;

import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.cli.HeadlessConsole;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class TuiConsole extends HeadlessConsole implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(TuiConsole.class);

    private final TuiApp app;
    private final TuiBuffer buffer;

    public TuiConsole(TuiApp app, TuiBuffer buffer) {
        this.app = requireNonNull(app);
        this.buffer = requireNonNull(buffer);
    }

    @Override
    protected void printLlmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        // Suppress stdout/stderr output; the TUI owns rendering.
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        app.postUi(() -> {
            if (isNewMessage(type, meta.isNewMessage())) {
                buffer.append("\n# " + type + "\n\n");
            }
            buffer.append(token);
        });
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        // Preserve base behavior (sets transcript), but render in the TUI.
        app.postUi(() -> {
            buffer.clear();
            history.forEach(h -> buffer.append(h + "\n"));
            buffer.append(taskEntry.toString());
        });
        super.setLlmAndHistoryOutput(history, taskEntry);
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        app.postUi(() -> {
            buffer.clear();
            history.forEach(h -> buffer.append(h + "\n"));
        });
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        app.pushTransientMessage("[" + role + "] " + message);
    }

    @Override
    public void toolError(String msg, String title) {
        app.pushTransientMessage("[" + title + "] " + msg);
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        // Keep it simple: treat all prompts as yes/no.
        try {
            return app.promptYesNo(title, message);
        } catch (RuntimeException e) {
            logger.warn("Confirm dialog failed", e);
            return javax.swing.JOptionPane.NO_OPTION;
        }
    }

    @Override
    public void showOutputSpinner(String message) {
        app.showSpinner(message);
    }

    @Override
    public void hideOutputSpinner() {
        app.hideSpinner();
    }

    @Override
    public void setTaskInProgress(boolean progress) {
        app.setStatus(progress ? "Task in progress" : "Idle");
    }

    @Override
    public void actionComplete() {
        app.setStatus("Done");
    }

    @Override
    public void close() {
        // Nothing to close here; caller owns TuiApp.
    }

    public static TuiConsole createDefault() throws java.io.IOException {
        var buffer = new TuiBuffer(10_000);
        var app = new TuiApp(buffer);
        return new TuiConsole(app, buffer);
    }

    public TuiApp getApp() {
        return app;
    }
}
