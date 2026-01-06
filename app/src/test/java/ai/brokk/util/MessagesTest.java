package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.tools.ExplanationRenderer;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
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
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        var expected = expectedRendered.isBlank() ? "hi" : "hi\n" + expectedRendered;

        assertEquals(expected, Messages.getTextWithToolCalls(message));
    }
}
