package ai.brokk.agents;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AgentConversation {

    private final IConsoleIO io;
    private final List<ChatMessage> internalMessages = new ArrayList<>();
    private final List<ChatMessage> uiMessages = new ArrayList<>();

    public AgentConversation(IConsoleIO io) {
        this.io = io;
    }

    // for debugging purpose we can show the tool results in MOP
    private static boolean shouldEchoToolResults(ChatMessage message) {
        return Boolean.parseBoolean(System.getProperty("brokk.showtoolresult", "false"))
                && message instanceof ToolExecutionResultMessage;
    }

    // MOP is only able to display one message at a time, so we split AiMessages that contain both reasoning and tool
    // calls/text
    private static List<ChatMessage> splitAiMessageForUi(AiMessage message) {
        var reasoning = message.reasoningContent();
        boolean hasReasoning = reasoning != null && !reasoning.isBlank();
        boolean hasText = message.text() != null && !message.text().isBlank();

        if (hasReasoning && (hasText || message.hasToolExecutionRequests())) {
            var reasoningOnly = AiMessage.from("", reasoning);

            AiMessage withoutReasoning = new AiMessage(
                    message.text(), null, message.hasToolExecutionRequests() ? message.toolExecutionRequests() : null);

            return List.of(reasoningOnly, withoutReasoning);
        }

        return List.of(message);
    }

    // append to both internal and ui and do not echo to MOP
    public void append(ChatMessage message) {
        append(message, false);
    }

    // append to both internal and ui and echo to MOP if true
    public void append(ChatMessage message, boolean echo) {
        internalMessages.add(message);
        appendUi(message, echo || shouldEchoToolResults(message));
    }

    // append to internal only
    public void appendInternal(ChatMessage message) {
        internalMessages.add(message);
    }

    // append to ui only and echo to MOP if true
    public void appendUi(ChatMessage message, boolean echo) {
        List<ChatMessage> uiToAppend = message instanceof AiMessage ai ? splitAiMessageForUi(ai) : List.of(message);
        uiMessages.addAll(uiToAppend);

        if (echo) {
            io.llmOutput(Messages.getText(message), message.type(), LlmOutputMeta.newMessage());
        }
    }

    public void appendUi(String text, boolean echo) {
        uiMessages.add(CustomMessage.from(Map.of("text", text)));
        if (echo) {
            io.llmOutput(text, ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());
        }
    }

    public List<ChatMessage> getInternalMessages() {
        return List.copyOf(internalMessages);
    }

    public List<ChatMessage> getUiMessages() {
        return List.copyOf(uiMessages);
    }

    public List<ChatMessage> consumeUiMessages() {
        var result = List.copyOf(uiMessages);
        uiMessages.clear();
        return result;
    }
}
