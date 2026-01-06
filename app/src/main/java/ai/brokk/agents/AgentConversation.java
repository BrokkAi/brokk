package ai.brokk.agents;

import ai.brokk.IConsoleIO;
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

  public void append(ChatMessage message) {
    internalMessages.add(message);
    uiMessages.add(message);
  }

  public void appendInternal(ChatMessage message) {
    internalMessages.add(message);
  }

  public void appendUi(ChatMessage message, boolean echo) {
    uiMessages.add(message);
    if (echo) {
      io.llmOutput(message.toString(), ChatMessageType.CUSTOM, true, false);
    }
  }

  public void appendUi(String text, ChatMessageType type, boolean echo) {
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

  public void clearUiMessages() {
    uiMessages.clear();
  }
}
