package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.EditBlock;
import ai.brokk.TaskResult;
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.testutil.TestContextManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Integration test: Verify CodeAgent's retry loop when semantic-aware edit blocks (BRK_CLASS/BRK_FUNCTION)
 * fail to resolve. We call applyPhase directly with a pending block using a missing BRK_CLASS target and assert:
 *  (a) the block leads to a retry (not a fatal stop),
 *  (b) feedback text includes actionable messaging,
 *  (c) consecutiveApplyFailures is incremented,
 *  (d) the agent does not fail immediately on first error (Step.Retry).
 *
 * This test reuses the CodeAgentTest harness (setUp/tearDown) for constructing a working CodeAgent.
 */
public class CodeAgentSemanticRetryTest extends CodeAgentTest {

    @Test
    void testApplyPhase_semanticFailure_brkClass_triggersRetry_andGeneratesFeedback() {
        // Construct a pending block that will fail semantic resolution (non-existent class)
        var badSemanticBlock = new EditBlock.SearchReplaceBlock(
                "A.java",
                "BRK_CLASS NoSuchClass",
                """
                package test;
                public class A {}
                """.stripIndent());

        // Minimal conversation and edit state; use raw generics to avoid direct ChatMessage dependency.
        var cs = new CodeAgent.ConversationState(List.of(), null, 0);

        var es = new CodeAgent.EditState(
                new LinkedHashSet<>(List.of(badSemanticBlock)), // pendingBlocks
                0, // consecutiveParseFailures
                0, // consecutiveApplyFailures
                0, // consecutiveBuildFailures
                0, // blocksAppliedWithoutBuild
                "", // lastBuildError
                Set.of(), // changedFiles
                Map.of(), // originalFileContents
                Map.of(), // javaLintDiagnostics
                Map.of() // simulatedContents
                );

        // Ensure Java-only editable workspace to satisfy BRK_* guard
        var cm = codeAgent.contextManager;
        var tcm = (TestContextManager) cm;
        tcm.getFilesInContext().clear();
        tcm.addEditableFile(cm.toFile("A.java"));

        // Invoke apply phase, which should attempt to apply, fail, and then craft a retry request with feedback.
        var step = codeAgent.applyPhase(cs, es, null, Set.of());

        // (d) Ensure we don't fail immediately; a retry should be requested.
        assertTrue(step instanceof CodeAgent.Step.Retry, "Expected Step.Retry after semantic apply failure");

        // Extract updated conversation and state
        var newCs = step.cs();
        var newEs = step.es();

        // (c) Verify apply failure count incremented (proxy for retry metric behavior)
        assertEquals(1, newEs.consecutiveApplyFailures(), "consecutiveApplyFailures should be incremented by 1");

        // (b) Check the nextRequest user message is present (feedback prompting a retry)
        var next = newCs.nextRequest();
        assertNotNull(next, "Expected a follow-up user request prompting a retry with failure details");

        // (a) Implicitly covered by the presence of the follow-up request and Step.Retry outcome.
    }

    @Test
    void testEndToEnd_semanticEdit_brkFunction_failure_incorrectMethod_andTelemetry() throws Exception {
        // 1) Create a Java file with a valid method
        var cm = codeAgent.contextManager;
        var file = cm.toFile("src/main/java/p/B.java");
        file.write(
                """
                package p;

                public class B {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """
                        .stripIndent());
        cm.getAnalyzerWrapper().updateFiles(Set.of(file)).get();

        // Force Java-only editable workspace to satisfy BRK_* guard
        var tcm = (TestContextManager) cm;
        tcm.getFilesInContext().clear();
        tcm.addEditableFile(file);

        // 2) Simulate an LLM response with BRK_FUNCTION pointing to a non-existent method
        var llmText = buildSrBlock(
                "src/main/java/p/B.java",
                "BRK_FUNCTION p.B.subtract", // wrong method name
                """
                public int subtract(int a, int b) {
                    return a - b;
                }
                """
                        .stripIndent());

        var cs = new CodeAgent.ConversationState(new ArrayList<>(), null, 0);
        var es = new CodeAgent.EditState(new LinkedHashSet<>(), 0, 0, 0, 0, "", Set.of(), Map.of(), Map.of(), Map.of());

        // parsePhase
        var parseStep = codeAgent.parsePhase(cs, es, llmText, false, EditBlockParser.instance, null);
        assertTrue(parseStep instanceof CodeAgent.Step.Continue, "parsePhase should Continue on clean block");
        cs = parseStep.cs();
        es = parseStep.es();
        assertEquals(1, es.pendingBlocks().size(), "One pending block expected");

        // applyPhase should produce a Retry with commentary via getApplyFailureMessage
        var applyStep = codeAgent.applyPhase(cs, es, null, Set.of());
        assertTrue(applyStep instanceof CodeAgent.Step.Retry, "Expected Retry on semantic failure");
        var retry = (CodeAgent.Step.Retry) applyStep;
        assertEquals(1, retry.es().consecutiveApplyFailures(), "Failures counter should increment");
        assertNotNull(retry.cs().nextRequest(), "LLM should receive feedback request");

        // 3) Telemetry shape check for an APPLY_ERROR outcome (invoke Metrics.print directly)
        var errBefore = System.err;
        var out = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));

            CodeAgent.Metrics metrics = new CodeAgent.Metrics();
            metrics.totalEditBlocks = 1;
            metrics.failedEditBlocks = 1;
            metrics.applyRetries = 1;

            var stop = new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "Unable to resolve method");
            metrics.print(Set.of(), stop);

            var s = out.toString(StandardCharsets.UTF_8);
            assertTrue(s.contains("BRK_CODEAGENT_METRICS="), "Metrics line should be printed");
            var json = s.substring(s.indexOf("BRK_CODEAGENT_METRICS=") + "BRK_CODEAGENT_METRICS=".length())
                    .trim();

            var obj = new ObjectMapper().readTree(json);
            assertEquals("APPLY_ERROR", obj.get("stopReason").asText());
            assertTrue(obj.get("editBlocksFailed").asInt() >= 1, "Should record at least one failed block");
        } finally {
            System.setErr(errBefore);
        }
    }

    // --- helpers ---

    private static String buildSrBlock(String filePath, String search, String replace) {
        return """
                ```
                %s
                <<<<<<< SEARCH
                %s
                =======
                %s
                >>>>>>> REPLACE
                ```
                """
                .stripIndent()
                .formatted(filePath, search, replace);
    }
}
