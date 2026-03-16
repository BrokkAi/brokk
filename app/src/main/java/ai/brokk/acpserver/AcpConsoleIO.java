package ai.brokk.acpserver;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BlitzForge;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.gui.dialogs.BlitzForgeProgressHeadless;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import org.jetbrains.annotations.Nullable;

public final class AcpConsoleIO extends MemoryConsole {
    public interface ChunkSink {
        void send(String text, boolean reasoning);
    }

    private final ChunkSink sink;
    private final Consumer<String> notificationSink;
    private final Consumer<String> errorSink;
    private boolean lastReasoning;

    public AcpConsoleIO(ChunkSink sink, Consumer<String> notificationSink, Consumer<String> errorSink) {
        this.sink = Objects.requireNonNull(sink);
        this.notificationSink = Objects.requireNonNull(notificationSink);
        this.errorSink = Objects.requireNonNull(errorSink);
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        sink.send(token, meta.isReasoning());
        lastReasoning = meta.isReasoning();
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        notificationSink.accept("[%s] %s".formatted(role.name(), message));
    }

    @Override
    public void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        if (cost == null) {
            showNotification(role, message);
            return;
        }
        showNotification(role, "%s ($%.4f)".formatted(message, cost));
    }

    @Override
    public void toolError(String msg, String title) {
        errorSink.accept("[%s] %s".formatted(title, msg));
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        notificationSink.accept("[CONFIRM:%s] %s".formatted(title, message));
        return JOptionPane.NO_OPTION;
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return new BlitzForgeProgressHeadless(this);
    }

    public boolean lastReasoning() {
        return lastReasoning;
    }
}
