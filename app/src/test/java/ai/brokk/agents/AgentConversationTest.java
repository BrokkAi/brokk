package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestConsoleIO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
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
  void testAppendWithEchoTrue() {
    ChatMessage message = AiMessage.from("echo-both");

    conversation.append(message, true);

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(message, conversation.getInternalMessages().getFirst());
    assertEquals(message, conversation.getUiMessages().getFirst());
    assertEquals(1, io.getLlmRawMessages().size());
  }

  @Test
  void testAppendWithEchoFalse() {
    ChatMessage message = UserMessage.from("no-echo-both");

    conversation.append(message, false);

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(1, conversation.getUiMessages().size());
    assertEquals(message, conversation.getInternalMessages().getFirst());
    assertEquals(message, conversation.getUiMessages().getFirst());
    assertEquals(0, io.getLlmRawMessages().size());
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
    ChatMessage message = AiMessage.from("echo-me");

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
  void testConsumeUiMessagesReturnsAndClearsUi() {
    ChatMessage internal = UserMessage.from("internal");
    ChatMessage ui = UserMessage.from("ui");

    conversation.appendInternal(internal);
    conversation.appendUi(ui, false);

    var consumed = conversation.consumeUiMessages();

    assertEquals(1, consumed.size());
    assertEquals(ui, consumed.getFirst());
    assertTrue(conversation.getUiMessages().isEmpty());

    assertEquals(1, conversation.getInternalMessages().size());
    assertEquals(internal, conversation.getInternalMessages().getFirst());
  }

  @Test
  void consumeUiMessages_preservesInternalMessages() {
    ChatMessage internal1 = UserMessage.from("internal-1");
    ChatMessage internal2 = UserMessage.from("internal-2");
    ChatMessage ui1 = UserMessage.from("ui-1");

    conversation.appendInternal(internal1);
    conversation.appendUi(ui1, false);
    conversation.appendInternal(internal2);

    var consumed = conversation.consumeUiMessages();

    assertEquals(1, consumed.size());
    assertEquals(ui1, consumed.getFirst());
    assertTrue(conversation.getUiMessages().isEmpty());

    assertEquals(2, conversation.getInternalMessages().size());
    assertEquals(internal1, conversation.getInternalMessages().get(0));
    assertEquals(internal2, conversation.getInternalMessages().get(1));
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

  @Test
  void append_aiMessageWithReasoningAndText_splitsForUi_internalUnsplit() {
    var msg = new AiMessage("final answer", "some reasoning");

    conversation.append(msg, false);

    assertEquals(1, conversation.getInternalMessages().size());
    assertSame(msg, conversation.getInternalMessages().getFirst());

    var ui = conversation.getUiMessages();
    assertEquals(2, ui.size());
    assertEquals(AiMessage.from("some reasoning"), ui.get(0));
    assertEquals(new AiMessage("final answer", null, null), ui.get(1));
  }

  @Test
  void append_aiMessageWithReasoningAndToolCalls_splitsForUi_internalUnsplit() {
    var req = ToolExecutionRequest.builder().id("call-1").name("searchSymbols").arguments("{}").build();
    var msg = new AiMessage("", "tool reasoning", List.of(req));

    conversation.append(msg, false);

    assertEquals(1, conversation.getInternalMessages().size());
    assertSame(msg, conversation.getInternalMessages().getFirst());

    var ui = conversation.getUiMessages();
    assertEquals(2, ui.size());
    assertEquals(AiMessage.from("tool reasoning"), ui.get(0));

    assertEquals(new AiMessage("", null, List.of(req)), ui.get(1));
    assertNull(((AiMessage) ui.get(1)).reasoningContent());
    assertTrue(((AiMessage) ui.get(1)).hasToolExecutionRequests());
  }

  @Test
  void append_aiMessageWithOnlyReasoning_notSplit() {
    var msg = new AiMessage("", "just reasoning");

    conversation.append(msg, false);

    assertEquals(1, conversation.getInternalMessages().size());
    assertSame(msg, conversation.getInternalMessages().getFirst());

    var ui = conversation.getUiMessages();
    assertEquals(1, ui.size());
    assertSame(msg, ui.getFirst());
  }

  @Test
  void append_aiMessageWithOnlyText_notSplit() {
    var msg = new AiMessage("just text");

    conversation.append(msg, false);

    assertEquals(1, conversation.getInternalMessages().size());
    assertSame(msg, conversation.getInternalMessages().getFirst());

    var ui = conversation.getUiMessages();
    assertEquals(1, ui.size());
    assertSame(msg, ui.getFirst());
  }

  @Test
  void appendUi_aiMessageWithReasoningAndText_splitsForUi() {
    var msg = new AiMessage("visible", "hidden reasoning");

    conversation.appendUi(msg, false);

    assertTrue(conversation.getInternalMessages().isEmpty());

    var ui = conversation.getUiMessages();
    assertEquals(2, ui.size());
    assertEquals(AiMessage.from("hidden reasoning"), ui.get(0));
    assertEquals(new AiMessage("visible", null, null), ui.get(1));
  }
}
