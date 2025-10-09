package io.github.jbellis.brokk.cli;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.gui.dialogs.BlitzForgeProgressHeadless;
import javax.swing.*;

/**
 * A lightweight, head-less {@link IConsoleIO} implementation that writes LLM output to {@code System.out} and tool
 * errors to {@code System.err}. All other {@code IConsoleIO} methods inherit their default no-op behaviour, which is
 * sufficient for a command-line environment with no GUI.
 */
public final class HeadlessConsole extends MemoryConsole {
    @Override
    public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
        super.llmOutput(token, type, explicitNewMessage, isReasoning);
        if (isNewMessage(type, explicitNewMessage)) {
            System.out.printf("# %s%n%n", type);
        }
        System.out.print(token);
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
