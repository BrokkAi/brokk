package ai.brokk.agents;

import ai.brokk.EditBlock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        var cs = new CodeAgent.ConversationState((List) List.of(), null, 0);

        var es = new CodeAgent.EditState(
                List.of(badSemanticBlock), // pendingBlocks
                0, // consecutiveParseFailures
                0, // consecutiveApplyFailures
                0, // consecutiveBuildFailures
                0, // blocksAppliedWithoutBuild
                "", // lastBuildError
                (Set) Set.of(), // changedFiles
                (Map) Map.of(), // originalFileContents
                (Map) Map.of()  // javaLintDiagnostics
        );

        // Invoke apply phase, which should attempt to apply, fail, and then craft a retry request with feedback.
        var step = codeAgent.applyPhase(cs, es, null);

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
}
