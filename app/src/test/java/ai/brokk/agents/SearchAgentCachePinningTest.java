package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService.OfflineStreamingModel;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchAgentCachePinningTest {

    @TempDir
    Path tempDir;

    @Test
    void testConvergenceScoreEdgeCases() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        Context empty = new Context(cm);
        SearchAgent agent = new SearchAgent(
                empty,
                "goal",
                new OfflineStreamingModel(),
                null,
                new NoOpConsoleIO(),
                SearchAgent.ScanConfig.disabled());

        // Case 1: No last turn
        assertTrue(agent.calculateConvergenceScore(empty, null) == 1.0);

        // Case 2: Union is empty (both contexts empty)
        assertTrue(agent.calculateConvergenceScore(empty, empty) == 1.0);

        // Case 3: Identity (converged)
        ContextFragment f1 =
                new ContextFragments.StringFragment(cm, "content", "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        Context ctx1 = empty.addFragments(f1);
        assertTrue(agent.calculateConvergenceScore(ctx1, ctx1) == 1.0);
    }
}
