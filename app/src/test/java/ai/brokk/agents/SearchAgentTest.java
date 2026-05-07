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
    void workspaceComplete_acceptsExactFragmentIdAndExactDescription() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var protocolId = "65fda478-c289-4e4e-afb1-d2753af1f080";
        ContextFragment fragment = new ContextFragments.StringFragment(
                protocolId, cm, "protocol source", "Summary of ai.brokk.acp.AcpProtocol class", "text/plain");
        Context context = new Context(cm).addFragments(fragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());
        registry = new ToolRegistry().builder().register(agent).build();

        var directive = agent.buildTurnDirective(1, List.of());
        assertTrue(directive.contains("description=\"Summary of ai.brokk.acp.AcpProtocol class\""));
        assertTrue(directive.contains("fragmentid=\"" + protocolId + "\""));

        var byId = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["%s"]}
                """.formatted(protocolId));

        assertEquals("TerminalStopOutput", byId.result().getClass().getSimpleName());
        assertJsonEquals(
                """
                {
                  "fragment_ids" : [ "%s" ]
                }"""
                        .formatted(protocolId),
                byId.resultText());

        var byDescription = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["Summary of ai.brokk.acp.AcpProtocol class"]}
                """);

        assertEquals("TerminalStopOutput", byDescription.result().getClass().getSimpleName());
        assertJsonEquals(
                """
                {
                  "fragment_ids" : [ "%s" ]
                }"""
                        .formatted(protocolId),
                byDescription.resultText());
    }

    @Test
    void workspaceComplete_acceptsNormalizedDescriptionPrefixFromToc() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var protocolId = "65fda478-c289-4e4e-afb1-d2753af1f080";
        ContextFragment fragment = new ContextFragments.StringFragment(
                protocolId, cm, "protocol source", "Summary of ai.brokk.acp.AcpProtocol class", "text/plain");
        Context context = new Context(cm).addFragments(fragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());
        registry = new ToolRegistry().builder().register(agent).build();

        var directive = agent.buildTurnDirective(1, List.of());
        assertTrue(directive.contains("description=\"Summary of ai.brokk.acp.AcpProtocol class\""));
        assertTrue(directive.contains("fragmentid=\"" + protocolId + "\""));

        var result = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["summary   of AI.BROKK.ACP.ACPPROTOCOL"]}
                """);

        assertEquals("TerminalStopOutput", result.result().getClass().getSimpleName());
        assertJsonEquals(
                """
                {
                  "fragment_ids" : [ "%s" ]
                }"""
                        .formatted(protocolId),
                result.resultText());
    }

    @Test
    void workspaceComplete_rejectsAmbiguousNormalizedDescriptionPrefix() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var protocolId = "65fda478-c289-4e4e-afb1-d2753af1f080";
        var protocolTestId = "0a18deaf-2b4d-4a85-8d8a-11f3d7b6c912";
        ContextFragment protocolFragment = new ContextFragments.StringFragment(
                protocolId, cm, "protocol source", "Summary of ai.brokk.acp.AcpProtocol class", "text/plain");
        ContextFragment protocolTestFragment = new ContextFragments.StringFragment(
                protocolTestId,
                cm,
                "protocol test source",
                "Summary of ai.brokk.acp.AcpProtocolTest class",
                "text/plain");
        Context context = new Context(cm).addFragments(protocolFragment).addFragments(protocolTestFragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());
        registry = new ToolRegistry().builder().register(agent).build();

        var result = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["summary of ai.brokk.acp.acpprotocol"]}
                """);

        assertTrue(result.result() instanceof ToolOutput.TextOutput);
        assertTrue(result.resultText().contains("ambiguous description"));
        assertTrue(result.resultText().contains(protocolId));
        assertTrue(result.resultText().contains(protocolTestId));
    }

    @Test
    void workspaceComplete_retryPreservesAcceptedIdsAndAcceptsCorrectedId() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var modelSelectorId = "65fda478-c289-4e4e-afb1-d2753af1f080";
        var protocolId = "0a18deaf-2b4d-4a85-8d8a-11f3d7b6c912";
        ContextFragment modelSelectorFragment = new ContextFragments.StringFragment(
                modelSelectorId,
                cm,
                "model selector source",
                "Summary of ai.brokk.gui.components.ModelSelector class",
                "text/plain");
        ContextFragment protocolFragment = new ContextFragments.StringFragment(
                protocolId, cm, "protocol source", "Summary of ai.brokk.acp.AcpProtocol class", "text/plain");
        Context context = new Context(cm).addFragments(modelSelectorFragment).addFragments(protocolFragment);
        var agent = new SearchAgent(context, "test goal", SearchPrompts.Objective.ANSWER_ONLY, new NoOpConsoleIO());
        registry = new ToolRegistry().builder().register(agent).build();

        var firstResult = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["%s","missing-fragment"]}
                """
                        .formatted(modelSelectorId));

        assertTrue(firstResult.result() instanceof ToolOutput.TextOutput);
        assertTrue(firstResult.resultText().contains("Accepted selections are saved"));
        assertTrue(firstResult.resultText().contains(modelSelectorId));

        var secondResult = executeTool(
                "workspaceComplete",
                """
                {"fragmentIdsOrDescriptions":["%s"]}
                """.formatted(protocolId));

        assertEquals("TerminalStopOutput", secondResult.result().getClass().getSimpleName());
        assertJsonEquals(
                """
                {
                  "fragment_ids" : [ "%s", "%s" ]
                }"""
                        .formatted(modelSelectorId, protocolId),
                secondResult.resultText());
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
