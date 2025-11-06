package ai.brokk.cli;

import ai.brokk.IConsoleIO;
import ai.brokk.agents.BlitzForge;
import ai.brokk.gui.dialogs.BlitzForgeProgressHeadless;
import dev.langchain4j.data.message.ChatMessageType;
import javax.swing.*;

/**
 * A silent {@link IConsoleIO} implementation that suppresses LLM streaming output for performance.
 * This is useful for batch operations like PR reviews where streaming output is not needed.
 * Tool errors and notifications are still written to stderr/stdout.
 */
public final class SilentConsole extends MemoryConsole {
    @Override
    public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
        super.llmOutput(token, type, explicitNewMessage, isReasoning);
    }

    @Override
    public void toolError(String msg, String title) {
        System.err.println("[" + title + "] " + msg);
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        String prefix = "[%s]".formatted(role.toString());
        if (role == IConsoleIO.NotificationRole.ERROR) {
            System.err.println(prefix + message);
        } else {
            System.out.println(prefix + message);
        }
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        return JOptionPane.NO_OPTION;
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return new BlitzForgeProgressHeadless(this);
    }
}
