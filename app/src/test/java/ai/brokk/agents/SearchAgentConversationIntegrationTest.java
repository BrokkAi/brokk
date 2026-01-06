package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestConsoleIO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying that SearchAgent correctly routes messages through AgentConversation.
 * These tests verify the message routing contract without requiring full SearchAgent instantiation.
 */
final class SearchAgentConversationIntegrationTest {

    @Test
    void appendUiText_echoTrue_addsCustomMessageToUiAndEchoesToIo() {
        conversation.appendUi("status text", ChatMessageType.AI, true);

        assertEquals(1, conversation.getUiMessages().size());
        assertTrue(conversation.getUiMessages().getFirst() instanceof CustomMessage);
        assertEquals(
                CustomMessage.from(Map.of("text", "status text")),
                conversation.getUiMessages().getFirst());

        assertEquals(1, io.getLlmRawMessages().size());
    }

    private TestConsoleIO io;
    private AgentConversation conversation;

    @BeforeEach
    void setUp() {
        io = new TestConsoleIO();
        conversation = new AgentConversation(io);
    }

    @Test
    void appendInternal_forUserMessagePrompt_addsOnlyToInternalMessages() {
        var prompt = new UserMessage("What tools do you want to use next?");

        conversation.appendInternal(prompt);

        assertEquals(1, conversation.getInternalMessages().size());
        assertEquals(0, conversation.getUiMessages().size());
        assertEquals(prompt, conversation.getInternalMessages().getFirst());
    }

    @Test
    void append_forAiResponse_addsToBothLists() {
        var aiResponse = new AiMessage("I will search for relevant files.");

        conversation.append(aiResponse);

        assertEquals(1, conversation.getInternalMessages().size());
        assertEquals(1, conversation.getUiMessages().size());
        assertEquals(aiResponse, conversation.getInternalMessages().getFirst());
        assertEquals(aiResponse, conversation.getUiMessages().getFirst());
    }

    @Test
    void append_forToolExecutionResult_addsToBothLists() {
        var toolResult = new ToolExecutionResultMessage("id-1", "searchSymbols", "Found 3 matches");

        conversation.append(toolResult);

        assertEquals(1, conversation.getInternalMessages().size());
        assertEquals(1, conversation.getUiMessages().size());
        assertEquals(toolResult, conversation.getInternalMessages().getFirst());
        assertEquals(toolResult, conversation.getUiMessages().getFirst());
    }

    @Test
    void messageRouting_preservesOrderAcrossMultipleOperations() {
        var prompt = new UserMessage("What tools do you want to use next?");
        var aiResponse = new AiMessage("I will call searchSymbols.");
        var toolResult = new ToolExecutionResultMessage("id-1", "searchSymbols", "Found matches");

        conversation.appendInternal(prompt);
        conversation.append(aiResponse);
        conversation.append(toolResult);

        var internal = conversation.getInternalMessages();
        assertEquals(3, internal.size());
        assertEquals(prompt, internal.get(0));
        assertEquals(aiResponse, internal.get(1));
        assertEquals(toolResult, internal.get(2));

        var ui = conversation.getUiMessages();
        assertEquals(2, ui.size());
        assertEquals(aiResponse, ui.get(0));
        assertEquals(toolResult, ui.get(1));
    }

    @Test
    void getInternalMessages_usableForLlmPromptConstruction() {
        var prompt = new UserMessage("What tools do you want to use next?");
        var aiResponse = new AiMessage("Searching...");

        conversation.appendInternal(prompt);
        conversation.append(aiResponse);

        var messages = conversation.getInternalMessages();

        assertTrue(messages.stream().anyMatch(m -> m.type() == ChatMessageType.USER));
        assertTrue(messages.stream().anyMatch(m -> m.type() == ChatMessageType.AI));
    }
}
