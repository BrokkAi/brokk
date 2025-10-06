package io.github.jbellis.brokk.gui.dialogs;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IConsoleIO;
import java.util.List;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Custom IConsoleIO implementation for streaming PR title and description generation. Manages state transitions:
 * REASONING → TITLE → DESCRIPTION
 */
public class PrDetailsConsoleIO implements IConsoleIO {
    private static final Logger logger = LogManager.getLogger(PrDetailsConsoleIO.class);

    private enum StreamingPhase {
        REASONING,
        TITLE,
        DESCRIPTION,
        COMPLETE // After </description> tag - ignore any additional content
    }

    private final JTextField titleField;
    private final JTextArea descriptionArea;
    private final IConsoleIO errorReporter;
    private StreamingPhase currentPhase = StreamingPhase.REASONING;
    private final StringBuilder titleBuffer = new StringBuilder();
    private final StringBuilder descriptionBuffer = new StringBuilder();
    private boolean isInsideTag = false;
    private String currentTag = "";

    @org.jetbrains.annotations.Nullable
    private Timer thinkingAnimationTimer;

    private int dotCount = 0;
    private boolean hasReceivedTokens = false;

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

        if (!isReasoning && currentPhase == StreamingPhase.REASONING) {
            logger.debug("Transitioning from REASONING to content phase");
            currentPhase = StreamingPhase.TITLE;
            stopThinkingAnimation();
            SwingUtilities.invokeLater(() -> {
                titleField.setText("Generating title...");
                descriptionArea.setText("");
            });
        } else if (isReasoning && currentPhase != StreamingPhase.REASONING) {
            throw new IllegalStateException("Invalid transition back to reasoning phase");
        }

        if (isReasoning) {
            if (!token.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    if (!hasReceivedTokens) {
                        hasReceivedTokens = true;
                        stopThinkingAnimation();
                        descriptionArea.setText("Generating description...\nThinking...\n\n");
                    }
                    descriptionArea.append(token);
                    descriptionArea.setCaretPosition(descriptionArea.getText().length());
                });
            }
            return;
        }

        parseAndRoute(token);
    }

    private void parseAndRoute(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);

            if (c == '<') {
                isInsideTag = true;
                currentTag = "";
            } else if (c == '>') {
                isInsideTag = false;
                handleTag(currentTag);
                currentTag = "";
            } else if (isInsideTag) {
                currentTag += c;
            } else {
                appendToCurrentPhase(String.valueOf(c));
            }
        }
    }

    private void handleTag(String tag) {
        if (tag.equals("title")) {
            currentPhase = StreamingPhase.TITLE;
            logger.debug("Switched to TITLE phase");
            SwingUtilities.invokeLater(() -> titleField.setText(""));
        } else if (tag.equals("/title")) {
            currentPhase = StreamingPhase.DESCRIPTION;
            logger.debug("Switched to DESCRIPTION phase");
        } else if (tag.equals("description")) {
            currentPhase = StreamingPhase.DESCRIPTION;
            logger.debug("Confirmed DESCRIPTION phase");
            SwingUtilities.invokeLater(() -> descriptionArea.setText(""));
        } else if (tag.equals("/description")) {
            currentPhase = StreamingPhase.COMPLETE;
            logger.debug("End of DESCRIPTION phase - ignoring any further content");
        }
    }

    private void appendToCurrentPhase(String content) {
        switch (currentPhase) {
            case TITLE -> {
                if (titleBuffer.isEmpty() && content.trim().isEmpty()) {
                    return;
                }
                titleBuffer.append(content);
                String currentTitle = titleBuffer.toString();
                SwingUtilities.invokeLater(() -> {
                    titleField.setText(currentTitle);
                    titleField.setCaretPosition(currentTitle.length());
                });
            }
            case DESCRIPTION -> {
                if (descriptionBuffer.isEmpty() && content.trim().isEmpty()) {
                    return;
                }
                descriptionBuffer.append(content);
                String currentDesc = descriptionBuffer.toString();
                SwingUtilities.invokeLater(() -> {
                    descriptionArea.setText(currentDesc);
                    descriptionArea.setCaretPosition(currentDesc.length());
                });
            }
            case REASONING -> {}
            case COMPLETE -> {
                logger.debug("Ignoring content after </description>: {}", content);
            }
        }
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

    public String getFinalTitle() {
        return titleBuffer.toString().trim();
    }

    public String getFinalDescription() {
        return descriptionBuffer.toString().trim();
    }

    /**
     * Returns true if streaming successfully received and displayed content tokens. Used to determine if fallback
     * setText() is needed in case streaming failed silently.
     */
    public boolean hasReceivedContent() {
        return hasReceivedTokens;
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
