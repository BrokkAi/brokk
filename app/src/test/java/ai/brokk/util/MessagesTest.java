package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.tools.ExplanationRenderer;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagesTest {

    static class TestTools {
        @Tool("Get class sources for testing")
        public String getClassSources(@P("classes") List<String> classNames, @P("reason") String reason) {
            return "ok";
        }
    }

    @Test
    void getTextWithToolCalls_nonAiMessage_delegatesToGetText() {

        var message = new UserMessage("hello");
        assertEquals(Messages.getText(message), Messages.getTextWithToolCalls(message));
    }

    @Test
    void getTextWithToolCalls_aiMessageWithoutToolRequests_delegatesToGetText() {

        var message = AiMessage.from("hello");
        assertEquals(Messages.getText(message), Messages.getTextWithToolCalls(message));
    }

    @Test
    void getTextWithToolCalls_aiMessageWithToolRequests_appendsRenderedToolCalls() {

        var request1 = ToolExecutionRequest.builder()
                .id("0")
                .name("getClassSources")
                .arguments("{\"classes\":[\"a.b.C\"],\"reason\":\"testing\"}")
                .build();
        var request2 = ToolExecutionRequest.builder()
                .id("1")
                .name("getClassSources")
                .arguments("{\"classes\":[\"d.e.F\"],\"reason\":\"more\"}")
                .build();

        var message = AiMessage.from("hi", List.of(request1, request2));

        var expectedRendered = List.of(request1, request2).stream()
                .map(ExplanationRenderer::renderToolRequest)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        var expected = expectedRendered.isBlank() ? "hi" : "hi\n\n" + expectedRendered;

        assertEquals(expected, Messages.getTextWithToolCalls(message));
    }

    @Test
    void shouldDisplayInMop_toolExecutionResult_hiddenByDefault() {
        String original = System.getProperty("brokk.showtoolresult");
        try {
            System.setProperty("brokk.showtoolresult", "false");
            var message = new ToolExecutionResultMessage("tool", "id", "result");
            assertFalse(Messages.shouldDisplayInMop(message));
        } finally {
            if (original != null) System.setProperty("brokk.showtoolresult", original);
            else System.clearProperty("brokk.showtoolresult");
        }
    }

    @Test
    void shouldDisplayInMop_nonToolMessages_alwaysDisplayed() {
        assertTrue(Messages.shouldDisplayInMop(new UserMessage("hello")));
        assertTrue(Messages.shouldDisplayInMop(AiMessage.from("hi")));
    }
}
