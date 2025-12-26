package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.util.List;
import javax.swing.*;

/**
 * Simple IConsoleIO implementation for streaming commit message or description generation.
 * Streams tokens into a single JTextArea and shows a small "thinking" animation until the first token arrives.
 * Supports reasoning vs content token streams: clears interim reasoning tokens when transitioning to content.
 */
public class TextAreaConsoleIO implements IConsoleIO {
    private final JTextArea textArea;
    private final IConsoleIO errorReporter;

    private final Timer thinkingAnimationTimer;
    private int dotCount = 0;
    private boolean hasReceivedTokens = false;

    // Reasoning/content stream handling
    private boolean lastWasReasoning = true;
    private boolean hasStartedContent = false;

    /**
     * New constructor that allows callers to customize the initial placeholder text displayed while thinking.
     *
     * @param initialMessage initial placeholder shown while the LLM hasn't emitted the first token
     */
    public TextAreaConsoleIO(JTextArea textArea, IConsoleIO errorReporter, String initialMessage) {
        this.textArea = textArea;
        this.errorReporter = errorReporter;
        thinkingAnimationTimer = new Timer(500, e -> {
            if (!hasReceivedTokens) {
                dotCount = (dotCount + 1) % 4;
                String dots = ".".repeat(dotCount);
                String spaces = " ".repeat(3 - dotCount);
                SwingUtilities.invokeLater(() -> textArea.setText(initialMessage + dots + spaces));
            }
        });

        SwingUtilities.invokeLater(() -> {
            textArea.setEnabled(false);
            textArea.setText(initialMessage);
            textArea.setCaretPosition(0);
            thinkingAnimationTimer.start();
        });
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        // Handle transition from reasoning -> content by clearing any interim reasoning tokens first.
        if (!isReasoning && lastWasReasoning && !hasStartedContent) {
            SwingUtilities.invokeLater(() -> textArea.setText(""));
            hasStartedContent = true;
        } else if (isReasoning && !lastWasReasoning) {
            // Illegal transition back to reasoning once non-reasoning content has started.
            throw new IllegalStateException("Stream switched from non-reasoning to reasoning");
        }

        if (token.isEmpty()) {
            lastWasReasoning = isReasoning;
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (!hasReceivedTokens) {
                hasReceivedTokens = true;
                thinkingAnimationTimer.stop();
                textArea.setEnabled(true);
                // Restore default text color for TextArea
                Color defaultFg = UIManager.getColor("TextArea.foreground");
                if (defaultFg != null) {
                    textArea.setForeground(defaultFg);
                }
                // Ensure content area is fresh for streamed content
                textArea.setText("");
            }
            textArea.append(token);
            textArea.setCaretPosition(textArea.getText().length());
        });

        lastWasReasoning = isReasoning;
    }

    public void onComplete() {
        thinkingAnimationTimer.stop();
        SwingUtilities.invokeLater(() -> {
            textArea.setEnabled(true);
            textArea.setCaretPosition(textArea.getText().length());
        });
    }

    @Override
    public void toolError(String message, String title) {
        errorReporter.toolError(message, title);
    }

    @Override
    public void showNotification(IConsoleIO.NotificationRole role, String message) {
        errorReporter.showNotification(role, message);
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        return List.of();
    }
}
