package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import io.github.jbellis.brokk.context.ContextFragment;

import java.util.ArrayList;
import java.util.List;

public class TestConsoleIO implements IConsoleIO {
    private final StringBuilder outputLog = new StringBuilder();
    private final StringBuilder errorLog = new StringBuilder();
    private final List<ChatMessage> llmRawMessages = new ArrayList<>();
    private final StringBuilder streamingAiMessage = new StringBuilder();


    @Override
    public void actionOutput(String text) {
        outputLog.append(text).append("\n");
    }

    @Override
    public void toolError(String msg, String title) {
        errorLog.append(msg).append("\n");
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
        if (type == ChatMessageType.AI) {
            if (isNewMessage && streamingAiMessage.length() > 0) {
                llmRawMessages.add(new AiMessage(streamingAiMessage.toString()));
                streamingAiMessage.setLength(0);
            }
            streamingAiMessage.append(token);
        } else if (type == ChatMessageType.CUSTOM) {
            finishStreamingAiMessage();
            // Use AiMessage for status updates in tests, as TaskEntry formatting knows how to handle it.
            llmRawMessages.add(new AiMessage(token));
        }
    }

    private void finishStreamingAiMessage() {
        if (streamingAiMessage.length() > 0) {
            llmRawMessages.add(new AiMessage(streamingAiMessage.toString()));
            streamingAiMessage.setLength(0);
        }
    }

    @Override
    public void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        finishStreamingAiMessage();
        if (newOutput != null && newOutput.messages() != null) {
            llmRawMessages.addAll(newOutput.messages());
        }
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
}
