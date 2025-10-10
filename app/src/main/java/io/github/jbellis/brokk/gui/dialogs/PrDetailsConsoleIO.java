package io.github.jbellis.brokk.gui.dialogs;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IConsoleIO;
import java.util.List;
import javax.swing.*;

/**
 * Custom IConsoleIO implementation for streaming PR generation with tool calling. Shows thinking/reasoning tokens while
 * the LLM processes, then title/description are populated via tool execution after streaming completes.
 */
public class PrDetailsConsoleIO implements IConsoleIO {
    private final JTextField titleField;
    private final JTextArea descriptionArea;
    private final IConsoleIO errorReporter;

    @org.jetbrains.annotations.Nullable
    private Timer thinkingAnimationTimer;

    private int dotCount = 0;
    private boolean hasReceivedTokens = false;
    private boolean hasStartedContent = false;
    private boolean lastWasReasoning = true;

    public PrDetailsConsoleIO(JTextField titleField, JTextArea descriptionArea, IConsoleIO errorReporter) {
        this.titleField = titleField;
        this.descriptionArea = descriptionArea;
        this.errorReporter = errorReporter;

        SwingUtilities.invokeLater(() -> {
            titleField.setEnabled(false);
            descriptionArea.setEnabled(false);
            titleField.setText("Generating title");
            descriptionArea.setText("Generating description...\nThinking");
            titleField.setCaretPosition(0);
            descriptionArea.setCaretPosition(0);
            startThinkingAnimation();
        });
    }

    private void startThinkingAnimation() {
        thinkingAnimationTimer = new Timer(500, e -> {
            if (!hasReceivedTokens) {
                dotCount = (dotCount + 1) % 4; // Cycle 0, 1, 2, 3
                String dots = ".".repeat(dotCount);
                String spaces = " ".repeat(3 - dotCount); // Pad to keep width consistent
                titleField.setText("Generating title" + dots + spaces);
                descriptionArea.setText("Generating description...\nThinking" + dots + spaces);
            }
        });
        thinkingAnimationTimer.start();
    }

    private void stopThinkingAnimation() {
        if (thinkingAnimationTimer != null) {
            thinkingAnimationTimer.stop();
            thinkingAnimationTimer = null;
        }
    }

    @Override
    public void llmOutput(
            String token,
            dev.langchain4j.data.message.ChatMessageType type,
            boolean isNewMessage,
            boolean isReasoning) {

        if (!isReasoning && lastWasReasoning && !hasStartedContent) {
            // Transition from reasoning to content: clear the reasoning tokens
            SwingUtilities.invokeLater(() -> descriptionArea.setText(""));
            hasStartedContent = true;
        } else if (isReasoning && !lastWasReasoning) {
            // Illegal transition back to reasoning
            throw new IllegalStateException("PR generation stream switched from non-reasoning to reasoning");
        }

        if (!token.isEmpty()) {
            String finalToken = token;
            SwingUtilities.invokeLater(() -> {
                if (!hasReceivedTokens) {
                    hasReceivedTokens = true;
                    stopThinkingAnimation();
                    descriptionArea.setText("Generating description...\n\n");
                }
                descriptionArea.append(finalToken);
                descriptionArea.setCaretPosition(descriptionArea.getText().length());
            });
        }

        lastWasReasoning = isReasoning;
    }

    public void onComplete() {
        stopThinkingAnimation();
        SwingUtilities.invokeLater(() -> {
            titleField.setEnabled(true);
            descriptionArea.setEnabled(true);
            titleField.setCaretPosition(0);
            descriptionArea.setCaretPosition(0);
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
