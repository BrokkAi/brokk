package io.github.jbellis.brokk.gui.wand;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IConsoleIO;
import java.util.List;
import javax.swing.*;

public class WandConsoleIO implements IConsoleIO {
    private final JTextArea instructionsArea;
    private final IConsoleIO errorReporter;
    private boolean hasStartedContent = false;
    private boolean lastWasReasoning = true;

    public WandConsoleIO(JTextArea instructionsArea, IConsoleIO errorReporter) {
        this.instructionsArea = instructionsArea;
        this.errorReporter = errorReporter;
        SwingUtilities.invokeLater(() -> {
            instructionsArea.setEnabled(false);
            instructionsArea.setText("Improving your prompt...\n\n");
            instructionsArea.setCaretPosition(instructionsArea.getText().length());
        });
    }

    @Override
    public void llmOutput(
            String token,
            dev.langchain4j.data.message.ChatMessageType type,
            boolean isNewMessage,
            boolean isReasoning) {
        if (!isReasoning && lastWasReasoning && !hasStartedContent) {
            // Transition from reasoning to content: clear the area first
            SwingUtilities.invokeLater(() -> instructionsArea.setText(""));
            hasStartedContent = true;
        } else if (isReasoning && !lastWasReasoning) {
            // Illegal transition back to reasoning
            throw new IllegalStateException("Wand stream switched from non-reasoning to reasoning");
        }

        if (!token.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                instructionsArea.append(token);
                instructionsArea.setCaretPosition(instructionsArea.getText().length());
            });
        }
        lastWasReasoning = isReasoning;
    }

    @Override
    public void toolError(String message, String title) {
        errorReporter.toolError(message, title);
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        return List.of();
    }
}
