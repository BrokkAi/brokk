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
    void answer_returnsPlaintextTerminalOutputWithFurtherInvestigation() throws InterruptedException {
        var result = executeTool(
                "answer",
                """
                {"explanation":"The explanation","furtherInvestigation":"The investigation"}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertTrue(result.resultText().contains("# Answer"));
        assertTrue(result.resultText().contains("The explanation"));
        assertTrue(result.resultText().contains("### Further Investigation"));
        assertTrue(result.resultText().contains("The investigation"));
    }

    @Test
    void abortSearch_usesSingleArgumentSignature() throws InterruptedException {
        var result = executeTool("abortSearch", """
                {"explanation":"The reason"}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertEquals("The reason", result.resultText());
    }

    @Test
    void workspaceComplete_returnsPlaintextTerminalOutputWithFurtherInvestigation() throws InterruptedException {
        var result = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":[],"furtherInvestigation":"The investigation"}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertTrue(result.resultText().contains("Selected Fragments:"));
        assertTrue(result.resultText().contains("### Further Investigation"));
        assertTrue(result.resultText().contains("The investigation"));
    }

    @Test
    void workspaceComplete_retryGuidanceMentionsFurtherInvestigation() throws InterruptedException {
        var result = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["missing-fragment"],"furtherInvestigation":""}
                """);

        assertTrue(result.result() instanceof ToolOutput.TextOutput);
        assertTrue(result.resultText().contains("furtherInvestigation"));
        assertTrue(result.resultText().contains("empty string"));
    }

    private ai.brokk.tools.ToolExecutionResult executeTool(String name, String arguments) throws InterruptedException {
        var request = ToolExecutionRequest.builder()
                .name(name)
                .arguments(arguments.strip())
                .build();
        return registry.executeTool(request);
    }
}
