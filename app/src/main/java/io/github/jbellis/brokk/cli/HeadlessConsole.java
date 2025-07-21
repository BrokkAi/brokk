package io.github.jbellis.brokk.cli;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A lightweight, head-less {@link IConsoleIO} implementation that writes LLM
 * output to {@code System.out} and tool errors to {@code System.err}.  All
 * other {@code IConsoleIO} methods inherit their default no-op behaviour,
 * which is sufficient for a command-line environment with no GUI.
 */
public final class HeadlessConsole implements IConsoleIO {
    List<ChatMessage> messages =  new ArrayList<>();

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
        if (messages.isEmpty() || messages.getLast().type() != type) {
            System.out.printf("# %s%n%n", type);
            messages.add(createMessage(type, token));
        } else {
            var lastMessage = messages.getLast();
            var newText = Messages.getText(lastMessage) + token;
            messages.set(messages.size() - 1, updateMessage(lastMessage, newText));
        }
        System.out.print(token);
    }

    private ChatMessage updateMessage(ChatMessage original, String newText) {
        return switch (original) {
            case SystemMessage sm -> SystemMessage.from(newText);
            case AiMessage am -> new AiMessage(newText, am.toolExecutionRequests());
            case UserMessage um -> UserMessage.from(newText);
            case CustomMessage cm -> {
                var attributes = new java.util.HashMap<>(cm.attributes());
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
            case CUSTOM -> new CustomMessage(java.util.Map.of("text", text));
            default -> throw new IllegalArgumentException("Unsupported message type for creation: " + type);
        };
    }

    @Override
    public void toolError(String msg, String title) {
        // Prefix the message with the title to make it clear in the console
        // which error type we encountered.
        System.err.println("[" + title + "] " + msg);
    }

    @Override
    public void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        messages.clear();
        messages.addAll(newOutput.messages());
    }

    @Override
    public String getLlmOutputText() {
        return getLlmRawMessages().stream()
                .map(Messages::getText)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        return List.copyOf(messages);
    }
}
