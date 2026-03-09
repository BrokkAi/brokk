package ai.brokk.testutil;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.context.ContextFragments;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.ArrayList;
import java.util.List;

public class TestConsoleIO implements IConsoleIO {
    public record CapturedOutput(String token, ChatMessageType type, LlmOutputMeta meta) {}

    private final List<CapturedOutput> capturedOutputs = new ArrayList<>();
    private final StringBuilder outputLog = new StringBuilder();
    private final StringBuilder errorLog = new StringBuilder();
    private final List<ChatMessage> llmRawMessages = new ArrayList<>();
    private final StringBuilder streamingAiMessage = new StringBuilder();
    private int errorCount = 0;

    public void actionOutput(String text) {
        outputLog.append(text).append("\n");
    }

    @Override
    public void toolError(String msg, String title) {
        errorCount++;
        errorLog.append(msg).append("\n");
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        if (role == IConsoleIO.NotificationRole.ERROR) {
            errorLog.append(message).append("\n");
        } else {
            outputLog.append(message).append("\n");
        }
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        capturedOutputs.add(new CapturedOutput(token, type, meta));
        if (type == ChatMessageType.AI) {
            if (meta.isNewMessage() && streamingAiMessage.length() > 0) {
                llmRawMessages.add(new AiMessage(streamingAiMessage.toString()));
                streamingAiMessage.setLength(0);
            }
            streamingAiMessage.append(token);
            outputLog.append(token);
        } else if (type == ChatMessageType.CUSTOM) {
            finishStreamingAiMessage();
            // Use AiMessage for status updates in tests, as TaskEntry formatting knows how to handle it.
            llmRawMessages.add(new AiMessage(token));
            outputLog.append(token);
        }
    }

    public List<CapturedOutput> getCapturedOutputs() {
        return List.copyOf(capturedOutputs);
    }

    private void finishStreamingAiMessage() {
        if (!streamingAiMessage.isEmpty()) {
            llmRawMessages.add(new AiMessage(streamingAiMessage.toString()));
            streamingAiMessage.setLength(0);
        }
    }

    public void setLlmOutput(ContextFragments.TaskFragment newOutput) {
        finishStreamingAiMessage();
        llmRawMessages.addAll(newOutput.messages());
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        finishStreamingAiMessage();
        return llmRawMessages;
    }

    public String getOutputLog() {
        return outputLog.toString();
    }

    public String getErrorLog() {
        return errorLog.toString();
    }

    public int getErrorCount() {
        return errorCount;
    }
}
