package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.ToolOutput;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchAgentTest {
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
        assertJsonEquals(
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
        assertJsonEquals(
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
        assertJsonEquals(
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

    @Test
    void buildTurnDirective_startsWithInitialWorkspaceSummary() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragment initialFragment =
                new ContextFragments.StringFragment(cm, "line1\nline2", "initial-notes", "text/plain");
        Context context = new Context(cm).addFragments(initialFragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());

        var directive = agent.buildTurnDirective(1, List.of());

        assertTrue(directive.contains("<initial_workspace_summary>"));
        assertTrue(directive.contains("summarized for brevity"));
        assertTrue(directive.contains("addFilesToWorkspace / addClassesToWorkspace"));
        assertTrue(directive.contains("<fragment_summary description=\"initial-notes\">"));
        assertTrue(directive.contains("line1\nline2"));
        assertTrue(directive.indexOf("<initial_workspace_summary>") < directive.indexOf("<goal>"));
    }

    @Test
    void buildTurnDirective_workspaceTocMentionsStartingAndAddedFragments() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragment initialFragment =
                new ContextFragments.StringFragment(cm, "initial", "initial-notes", "text/plain");
        ContextFragment addedFragment = new ContextFragments.StringFragment(cm, "added", "added-notes", "text/plain");
        Context context = new Context(cm).addFragments(initialFragment).addFragments(addedFragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());

        var directive = agent.buildTurnDirective(1, List.of());

        assertTrue(directive.contains("including the starting fragments and anything you have added so far"));
    }

    private ai.brokk.tools.ToolExecutionResult executeTool(String name, String arguments) throws InterruptedException {
        var request = ToolExecutionRequest.builder()
                .name(name)
                .arguments(arguments.strip())
                .build();
        return registry.executeTool(request);
    }

    private static void assertJsonEquals(String expected, String actual) {
        try {
            assertEquals(Json.getMapper().readTree(expected), Json.getMapper().readTree(actual));
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to parse JSON during assertion", e);
        }
    }
}
