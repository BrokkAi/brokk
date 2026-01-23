package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodePromptsToolRedactionTest {

    @Test
    void shouldRedactFalse_preservesToolRequestAndResultMessages() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var userMessage = new UserMessage("Find Java files");

        var messages = List.<ChatMessage>of(userMessage, aiWithTools, toolResult);

        var result = CodePrompts.redactHistoryMessages(messages, false);

        assertEquals(3, result.size());
        assertSame(userMessage, result.get(0));
        assertInstanceOf(AiMessage.class, result.get(1));
        var resultAi = (AiMessage) result.get(1);
        assertTrue(resultAi.hasToolExecutionRequests());
        assertEquals(1, resultAi.toolExecutionRequests().size());
        assertInstanceOf(ToolExecutionResultMessage.class, result.get(2));
    }

    @Test
    void shouldRedactTrue_dropsToolExecutionResultMessage() {
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var userMessage = new UserMessage("Find Java files");

        var messages = List.<ChatMessage>of(userMessage, toolResult);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        assertSame(userMessage, result.get(0));
    }

    @Test
    void shouldRedactTrue_rewritesToolRequestAiMessageIntoPassiveText() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("addFilesToWorkspace")
                .arguments("{\"paths\": [\"src/Main.java\"]}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));

        var messages = List.<ChatMessage>of(aiWithTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        assertInstanceOf(AiMessage.class, result.get(0));
        var resultAi = (AiMessage) result.get(0);
        assertFalse(resultAi.hasToolExecutionRequests());

        var text = resultAi.text();
        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `addFilesToWorkspace` was invoked with {\"paths\": [\"src/Main.java\"]}"));
    }

    @Test
    void shouldRedactTrue_preservesOriginalAiMessageTextBeforeToolList() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{}")
                .build();
        var aiWithTextAndTools = new AiMessage("Let me search for relevant files.", List.of(toolRequest));

        var messages = List.<ChatMessage>of(aiWithTextAndTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var resultAi = (AiMessage) result.get(0);
        var text = resultAi.text();

        assertTrue(text.startsWith("Let me search for relevant files."));
        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `searchFiles` was invoked with {}"));
    }

    @Test
    void shouldRedactTrue_handlesMultipleToolRequests() {
        var request1 = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var request2 = ToolExecutionRequest.builder()
                .id("call-2")
                .name("addFilesToWorkspace")
                .arguments("{\"paths\": [\"a.java\", \"b.java\"]}")
                .build();
        var aiWithMultipleTools = new AiMessage(List.of(request1, request2));

        var messages = List.<ChatMessage>of(aiWithMultipleTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();

        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `searchFiles` was invoked with {\"pattern\": \"*.java\"}"));
        assertTrue(text.contains("Tool `addFilesToWorkspace` was invoked with {\"paths\": [\"a.java\", \"b.java\"]}"));
    }

    @Test
    void shouldRedactFalse_stillAppliesSRBlockRedactionToAiMessages() {
        var aiWithSRBlock = new AiMessage(
                """
                Here is the fix:
                ```java
                foo.java
                <<<<<<< SEARCH
                old code
                =======
                new code
                >>>>>>> REPLACE
                ```
                """);

        var messages = List.<ChatMessage>of(aiWithSRBlock);

        var result = CodePrompts.redactHistoryMessages(messages, false);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();
        assertTrue(text.contains("[elided SEARCH/REPLACE block]"));
        assertFalse(text.contains("<<<<<<< SEARCH"));
    }

    @Test
    void shouldRedactTrue_aiMessageWithoutToolRequests_appliesSRBlockRedaction() {
        var aiWithSRBlock = new AiMessage(
                """
                file.txt
                <<<<<<< SEARCH
                old
                =======
                new
                >>>>>>> REPLACE
                """);

        var messages = List.<ChatMessage>of(aiWithSRBlock);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();
        assertTrue(text.contains("[elided SEARCH/REPLACE block]"));
    }

    @Test
    void preservesOtherMessageTypes() {
        var user = new UserMessage("Hello");
        var ai = new AiMessage("Hi there");

        var messages = List.<ChatMessage>of(user, ai);

        var resultFalse = CodePrompts.redactHistoryMessages(messages, false);
        var resultTrue = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(2, resultFalse.size());
        assertEquals(2, resultTrue.size());
        assertSame(user, resultFalse.get(0));
        assertSame(user, resultTrue.get(0));
    }

    @Test
    void handlesEmptyMessageList() {
        var result = CodePrompts.redactHistoryMessages(List.of(), true);
        assertTrue(result.isEmpty());
    }
}
