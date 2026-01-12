package ai.brokk.cli;

import ai.brokk.IConsoleIO;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public abstract class MemoryConsole implements IConsoleIO {
    List<ChatMessage> messages = new ArrayList<>();
    private @Nullable IConsoleIO echoTo;

    public void setEchoTo(@Nullable IConsoleIO echoTo) {
        this.echoTo = echoTo;
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
        if (isNewMessage(type, explicitNewMessage)) {
            messages.add(createMessage(type, token));
        } else {
            var lastMessage = messages.getLast();
            var newText = Messages.getText(lastMessage) + token;
            messages.set(messages.size() - 1, updateMessage(lastMessage, newText));
        }
        if (echoTo != null) {
            echoTo.llmOutput(token, type, explicitNewMessage, isReasoning);
        }
    }

    protected boolean isNewMessage(ChatMessageType type, boolean explicitNewMessage) {
        return explicitNewMessage || messages.isEmpty() || messages.getLast().type() != type;
    }

    private ChatMessage updateMessage(ChatMessage original, String newText) {
        return switch (original) {
            case SystemMessage sm -> SystemMessage.from(newText);
            case AiMessage am -> new AiMessage(newText, am.toolExecutionRequests());
            case UserMessage um -> UserMessage.from(newText);
            case CustomMessage cm -> {
                var attributes = new HashMap<>(cm.attributes());
                attributes.put("text", newText);
                yield new CustomMessage(attributes);
            }
            default -> throw new IllegalStateException("Unsupported message type for update: " + original.getClass());
        };
    }

    private ChatMessage createMessage(ChatMessageType type, String text) {
        return switch (type) {
            case SYSTEM -> SystemMessage.from(text);
            case USER -> UserMessage.from(text);
            case AI -> AiMessage.from(text);
            case CUSTOM -> Messages.customSystem(text);
            default -> throw new IllegalArgumentException("Unsupported message type for creation: " + type);
        };
    }

    protected final void resetTranscript() {
        messages.clear();
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        return List.copyOf(messages);
    }

    @Override
    public void toolError(String message, String title) {
        if (echoTo != null) {
            echoTo.toolError(message, title);
        }
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        if (echoTo != null) {
            echoTo.showNotification(role, message);
        }
    }
}
