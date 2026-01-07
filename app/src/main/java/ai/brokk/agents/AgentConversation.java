package ai.brokk.agents;

import ai.brokk.IConsoleIO;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
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

  private static boolean shouldEchoToolCalls() {
    return Boolean.parseBoolean(System.getProperty("brokk.showtoolresult", "false"));
  }

  private static List<ChatMessage> splitAiMessageForUi(AiMessage message) {
    var reasoning = message.reasoningContent();
    boolean hasReasoning = reasoning != null && !reasoning.isBlank();
    boolean hasText = message.text() != null && !message.text().isBlank();

    if (hasReasoning && (hasText || message.hasToolExecutionRequests())) {
      var reasoningOnly = AiMessage.from(reasoning);

      AiMessage withoutReasoning =
          new AiMessage(message.text(), null, message.hasToolExecutionRequests() ? message.toolExecutionRequests() : null);

      return List.of(reasoningOnly, withoutReasoning);
    }

    return List.of(message);
  }

  public void append(ChatMessage message) {
    internalMessages.add(message);
    uiMessages.add(message);
  }

  public void append(ChatMessage message, boolean echo) {
    internalMessages.add(message);

    List<ChatMessage> uiToAppend =
        message instanceof AiMessage ai ? splitAiMessageForUi(ai) : List.of(message);
    uiMessages.addAll(uiToAppend);

    if (!echo) {
      return;
    }

    if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
      if (!shouldEchoToolCalls()) {
        return;
      }
    }

    io.llmOutput(Messages.getText(message), message.type(), true, false);
  }

  public void appendInternal(ChatMessage message) {
    internalMessages.add(message);
  }

  public void appendUi(ChatMessage message, boolean echo) {
    List<ChatMessage> uiToAppend =
        message instanceof AiMessage ai ? splitAiMessageForUi(ai) : List.of(message);
    uiMessages.addAll(uiToAppend);

    if (!echo) {
      return;
    }

    if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
      if (!shouldEchoToolCalls()) {
        return;
      }
    }

    io.llmOutput(Messages.getText(message), message.type(), true, false);
  }

  public void appendUi(String text, boolean echo) {
    uiMessages.add(CustomMessage.from(Map.of("text", text)));
    if (echo) {
      io.llmOutput(text, ChatMessageType.CUSTOM, true, false);
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
