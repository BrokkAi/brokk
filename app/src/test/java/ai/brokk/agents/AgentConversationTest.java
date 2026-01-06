package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestConsoleIO;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AgentConversationTest {

  private TestConsoleIO io;
  private AgentConversation conversation;

  @BeforeEach
  void setUp() {
    io = new TestConsoleIO();
    conversation = new AgentConversation(io);
  }

  @Test
  void testAppendAddsToBothLists() {
    ChatMessage message = UserMessage.from("hello");

    conversation.append(message);

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(message, conversation.getInternalMessages().getFirst());
    assertEquals(message, conversation.getUiMessages().getFirst());
  }

  @Test
  void testAppendInternalAddsToInternalOnly() {
    ChatMessage message = UserMessage.from("internal");

    conversation.appendInternal(message);

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(0, conversation.getUiMessages().size());
    assertEquals(message, conversation.getInternalMessages().getFirst());
  }

  @Test
  void testAppendUiMessageAddsToUiOnly() {
    ChatMessage message = UserMessage.from("ui");

    conversation.appendUi(message, false);

    assertEquals(0, conversation.getInternalMessages().size());
    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(message, conversation.getUiMessages().getFirst());
  }

  @Test
  void testAppendUiMessageEchoTrue() {
    ChatMessage message = UserMessage.from("echo-me");

    conversation.appendUi(message, true);

    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(0, conversation.getInternalMessages().size());
    assertEquals(message, conversation.getUiMessages().getFirst());

    assertEquals(1, io.getLlmRawMessages().size());
  }

  @Test
  void testAppendUiMessageEchoFalse() {
    ChatMessage message = UserMessage.from("do-not-echo");

    conversation.appendUi(message, false);

    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(0, conversation.getInternalMessages().size());
    assertEquals(0, io.getLlmRawMessages().size());
  }

  @Test
  void testAppendUiTextAddsToUiOnly() {
    conversation.appendUi("test", ChatMessageType.USER, false);

    assertEquals(0, conversation.getInternalMessages().size());
    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(CustomMessage.from(Map.of("text", "test")), conversation.getUiMessages().getFirst());
  }

  @Test
  void testAppendUiTextEchoTrue() {
    conversation.appendUi("test", ChatMessageType.USER, true);

    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(0, conversation.getInternalMessages().size());

    assertEquals(1, io.getLlmRawMessages().size());
  }

  @Test
  void testAppendUiTextEchoFalse() {
    conversation.appendUi("test", ChatMessageType.USER, false);

    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(0, conversation.getInternalMessages().size());
    assertEquals(0, io.getLlmRawMessages().size());
  }

  @Test
  void testClearUiMessagesOnlyClearsUi() {
    ChatMessage internal = UserMessage.from("internal");
    ChatMessage ui = UserMessage.from("ui");

    conversation.appendInternal(internal);
    conversation.appendUi(ui, false);
    conversation.clearUiMessages();

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(internal, conversation.getInternalMessages().getFirst());
    assertEquals(0, conversation.getUiMessages().size());
  }

  @Test
  void testGettersReturnUnmodifiableLists() {
    conversation.appendInternal(UserMessage.from("internal"));
    conversation.appendUi(UserMessage.from("ui"), false);

    assertThrows(
        UnsupportedOperationException.class,
        () -> conversation.getInternalMessages().add(UserMessage.from("x")));

    assertThrows(
        UnsupportedOperationException.class,
        () -> conversation.getUiMessages().add(UserMessage.from("x")));
  }
}
