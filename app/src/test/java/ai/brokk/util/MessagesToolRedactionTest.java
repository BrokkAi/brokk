package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

class MessagesToolRedactionTest {

    @Test
    void simpleToolNameWithEmptyArgs() {
        var request = ToolExecutionRequest.builder()
                .name("searchFiles")
                .arguments("{}")
                .build();

        String result = Messages.getRedactedRepr(request);

        assertEquals("Tool `searchFiles` was invoked with {}", result);
    }

    @Test
    void toolNameContainingPunctuationAndUnderscores() {
        var request = ToolExecutionRequest.builder()
                .name("add_files_to_workspace")
                .arguments("{\"paths\": [\"src/Main.java\"]}")
                .build();

        String result = Messages.getRedactedRepr(request);

        assertEquals("Tool `add_files_to_workspace` was invoked with {\"paths\": [\"src/Main.java\"]}", result);
    }

    @Test
    void argumentsContainingQuotesAndWhitespacePreservedVerbatim() {
        String verbatimArgs = "{\"query\": \"find \\\"foo bar\\\" in files\", \"path\": \"src/test resources\"}";
        var request = ToolExecutionRequest.builder()
                .name("search.code")
                .arguments(verbatimArgs)
                .build();

        String result = Messages.getRedactedRepr(request);

        assertEquals("Tool `search.code` was invoked with " + verbatimArgs, result);
    }

    @Test
    void toolNameWithSpecialCharacters() {
        var request = ToolExecutionRequest.builder()
                .name("mcp-tool.execute")
                .arguments("{\"command\": \"run\"}")
                .build();

        String result = Messages.getRedactedRepr(request);

        assertEquals("Tool `mcp-tool.execute` was invoked with {\"command\": \"run\"}", result);
    }
}
