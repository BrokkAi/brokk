package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService.OfflineStreamingModel;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchAgentCachePinningTest {

    @TempDir
    Path tempDir;

    @Test
    void testInequalityPinning() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Create fragments with controlled sizes
        // Tokens approximated: "short" (1), "medium content here" (3), "very long content..." (12)
        ContextFragment f1 = new ContextFragments.StringFragment(cm, "short", "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f2 =
                new ContextFragments.StringFragment(cm, "medium content here", "f2", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f3 = new ContextFragments.StringFragment(
                cm,
                "very long content that should be pinned if cache weight is high",
                "f3",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        // f1 is originally pinned
        Context ctx = new Context(cm).addFragments(List.of(f1, f2, f3)).withPinned(f1, true);

        SearchAgent agent = new SearchAgent(
                ctx, "goal", new OfflineStreamingModel(), null, new NoOpConsoleIO(), SearchAgent.ScanConfig.disabled());

        // Turn 1: No lastTurnContext means score = 1.0 (cacheWeight = 0). Only original f1 should be pinned.
        agent.lastTurnContext = null;
        agent.resetPinsToOriginal();
        agent.applyPinning();
        assertTrue(agent.context.isPinned(f1));
        assertFalse(agent.context.isPinned(f2));
        assertFalse(agent.context.isPinned(f3));

        // Manually pin something non-original
        agent.context = agent.context.withPinned(f3, true);
        assertTrue(agent.context.isPinned(f3));

        // Turn 2: Force a state that leads to score < 1.0.
        // We use large strings to exceed the 10,000 token threshold (approx 4 chars per token)
        ContextFragment bigF2 =
                new ContextFragments.StringFragment(cm, "a".repeat(40000), "f2", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment bigF3 =
                new ContextFragments.StringFragment(cm, "b".repeat(80000), "f3", SyntaxConstants.SYNTAX_STYLE_NONE);

        // To trigger pinning, we need a state of flux.
        // Let's create a 'last turn' that is significantly different from 'current turn'.
        ContextFragment fluxF =
                new ContextFragments.StringFragment(cm, "flux", "flux", SyntaxConstants.SYNTAX_STYLE_NONE);
        agent.lastTurnContext = new Context(cm).addFragments(List.of(f1, bigF2, fluxF));

        // current context has {f1, bigF2, bigF3}.
        // intersection: {f1, bigF2}. size 2.
        // union: {f1, bigF2, fluxF, bigF3}. size 4.
        // stability = 2/4 = 0.5.
        // novelty = {bigF3} / 4 = 0.25.
        // convergence = 0.5 * (1 - 0.25) = 0.375.
        // cacheWeight = 1 - 0.375 = 0.625.

        // bigF3 is ~20k tokens. bigF2 is ~10k tokens.
        // bigF2: lostTokens (bigF3) = 20000. freedTokens (0.9 * bigF2) = 9000.
        // (0.625 * 20000) = 12500.
        // 12500 > 9000 AND (20000 - 9000) > 10000. SHOULD PIN.
        agent.context = new Context(cm).addFragments(List.of(f1, bigF2, bigF3)).withPinned(f1, true);

        agent.applyPinning();
        assertTrue(agent.context.isPinned(f1));
        assertTrue(agent.context.isPinned(bigF2), "bigF2 should be pinned due to high cache loss in flux state");
        assertFalse(agent.context.isPinned(bigF3));
    }

    @Test
    void testNewFragmentsAreNotPinned() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        // Use very large strings to exceed the 10k token delta (approx 4 chars/token)
        // f1: ~20k tokens, f2: ~20k tokens
        ContextFragment f1 =
                new ContextFragments.StringFragment(cm, "a".repeat(80000), "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f2 =
                new ContextFragments.StringFragment(cm, "b".repeat(80000), "f2", SyntaxConstants.SYNTAX_STYLE_NONE);

        Context initialCtx = new Context(cm).addFragments(f1);
        Context ctx = initialCtx.addFragments(f2);
        SearchAgent agent = new SearchAgent(
                ctx, "goal", new OfflineStreamingModel(), null, new NoOpConsoleIO(), SearchAgent.ScanConfig.disabled());

        // f1 is "old" (in both turns), f2 is "new" (added this turn)
        // To ensure cacheWeight > 0, we need some churn.
        ContextFragment fluxF =
                new ContextFragments.StringFragment(cm, "flux", "flux", SyntaxConstants.SYNTAX_STYLE_NONE);
        agent.lastTurnContext = initialCtx.addFragments(fluxF);

        agent.applyPinning();

        assertTrue(agent.context.isPinned(f1), "Old fragment with high cache loss should be pinned");
        assertFalse(agent.context.isPinned(f2), "New fragment should never be pinned");
    }
}
