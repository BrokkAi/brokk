package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.ToolOutput;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchAgentTerminalActionsTest {
    @TempDir
    Path tempDir;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        Context context = new Context(new TestContextManager(tempDir, new NoOpConsoleIO()));
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());
        registry = new ToolRegistry().builder().register(agent).build();
    }

    @Test
    void answer_returnsJsonTerminalOutput() throws InterruptedException {
        var result = executeTool("answer", """
                {"explanation":"The explanation"}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertEquals(
                """
                {
                  "explanation" : "The explanation"
                }""",
                result.resultText());
    }

    @Test
    void abortSearch_usesSingleArgumentSignature() throws InterruptedException {
        var result = executeTool("abortSearch", """
                {"explanation":"The reason"}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertEquals(
                """
                {
                  "explanation" : "The reason"
                }""",
                result.resultText());
    }

    @Test
    void workspaceComplete_returnsJsonTerminalOutput() throws InterruptedException {
        var result = executeTool(
                "workspaceComplete", """
                {"fragmentIdsOrDescriptions":[]}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertEquals(
                """
                {
                  "fragment_ids" : [ ]
                }""", result.resultText());
    }

    @Test
    void workspaceComplete_retryGuidanceOmitsFurtherInvestigation() throws InterruptedException {
        var result = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["missing-fragment"]}
                """);

        assertTrue(result.result() instanceof ToolOutput.TextOutput);
        assertTrue(result.resultText().contains("only corrected selections"));
        assertTrue(!result.resultText().contains("furtherInvestigation"));
    }

    private ai.brokk.tools.ToolExecutionResult executeTool(String name, String arguments) throws InterruptedException {
        var request = ToolExecutionRequest.builder()
                .name(name)
                .arguments(arguments.strip())
                .build();
        return registry.executeTool(request);
    }
}
