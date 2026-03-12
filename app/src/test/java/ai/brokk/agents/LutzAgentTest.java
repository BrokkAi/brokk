package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService.OfflineStreamingModel;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LutzAgentTest {

    @TempDir
    Path tempDir;

    private static LutzAgent newAgent(TestContextManager cm, StreamingChatModel model) throws InterruptedException {
        return new LutzAgent(cm.liveContext(), "goal", model, null);
    }

    private LutzAgent newAgent(TestContextManager cm) throws InterruptedException {
        return new LutzAgent(
                cm.liveContext(),
                "goal",
                new OfflineStreamingModel(),
                ai.brokk.prompts.SearchPrompts.Objective.WORKSPACE_ONLY,
                null,
                new NoOpConsoleIO(),
                LutzAgent.ScanConfig.disabled());
    }

    @Test
    void calculateAllowedToolNames_normalMode_includesDropWhenDroppableFragmentsExist() throws InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        LutzAgent agent = newAgent(cm, new OfflineStreamingModel());

        // Manually add a fragment to make it droppable (originalPinnedFragments is empty in this test)
        Context context = agent.currentState
                .context()
                .addFragments(
                        new ai.brokk.context.ContextFragments.StringFragment(cm, "some content", "desc", "text/plain"));

        assertTrue(
                agent.hasDroppableFragments(context),
                "Workspace should have droppable fragments after adding a new one");
        List<String> allowed = agent.calculateAllowedToolNames(context);
        assertTrue(allowed.contains("dropWorkspaceFragments"), "Allowed tools should include drop tool");
    }

    @Test
    void calculateAllowedToolNames_includesLineRangeWorkspaceTool_notLegacyReadLineRange() throws InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        LutzAgent agent = newAgent(cm, new OfflineStreamingModel());
        List<String> allowed = agent.calculateAllowedToolNames(cm.liveContext());

        assertTrue(allowed.contains("addLineRangeToWorkspace"));
        assertFalse(allowed.contains("readLineRange"));
    }

    @Test
    void calculateAllowedToolNames_delegatesSearchToCallSearchAgent() throws InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        LutzAgent agent = newAgent(cm, new OfflineStreamingModel());
        List<String> allowed = agent.calculateAllowedToolNames(cm.liveContext());

        assertTrue(allowed.contains("callSearchAgent"), "LutzAgent should offer callSearchAgent for delegated search");
        assertFalse(allowed.contains("searchSymbols"), "LutzAgent should not offer raw searchSymbols tool");
    }

    @Test
    void testInequalityPinning() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Create fragments with controlled sizes
        // Tokens approximated: "short" (1), "medium content here" (3), "very long content..." (12)
        ContextFragment f1 =
                new ContextFragments.StringFragment("f1", cm, "short", "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f2 = new ContextFragments.StringFragment(
                "f2", cm, "medium content here", "f2", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f3 = new ContextFragments.StringFragment(
                "f3",
                cm,
                "very long content that should be pinned if cache weight is high",
                "f3",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        // f1 is originally pinned
        Context ctx = new Context(cm).addFragments(List.of(f1, f2, f3)).withPinned(f1, true);

        LutzAgent agent = new LutzAgent(
                ctx, "goal", new OfflineStreamingModel(), null, new NoOpConsoleIO(), LutzAgent.ScanConfig.disabled());

        // Turn 1: No lastTurnContext means score = 1.0 (cacheWeight = 0). Only original f1 should be pinned.
        Context pinned1 = agent.applyPinning(ctx, null);
        assertTrue(pinned1.isPinned(f1));
        assertFalse(pinned1.isPinned(f2));
        assertFalse(pinned1.isPinned(f3));

        // Manually pin something non-original
        pinned1 = pinned1.withPinned(f3, true);
        assertTrue(pinned1.isPinned(f3));

        // Turn 2: Force a state that leads to score < 1.0.
        // We use large strings to exceed the 10,000 token threshold (approx 4 chars per token)
        ContextFragment bigF2 = new ContextFragments.StringFragment(
                "f2", cm, "a".repeat(40000), "f2", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment bigF3 = new ContextFragments.StringFragment(
                "f3", cm, "b".repeat(80000), "f3", SyntaxConstants.SYNTAX_STYLE_NONE);

        // To trigger pinning, we need a state of flux.
        // Let's create a 'last turn' that is significantly different from 'current turn'.
        ContextFragment fluxF =
                new ContextFragments.StringFragment("flux", cm, "flux", "flux", SyntaxConstants.SYNTAX_STYLE_NONE);
        Context lastTurn = new Context(cm).addFragments(List.of(f1, bigF2, fluxF));

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
        Context current =
                new Context(cm).addFragments(List.of(f1, bigF2, bigF3)).withPinned(f1, true);

        Context pinned2 = agent.applyPinning(current, lastTurn);
        assertTrue(pinned2.isPinned(f1));
        assertTrue(pinned2.isPinned(bigF2), "bigF2 should be pinned due to high cache loss in flux state");
        assertFalse(pinned2.isPinned(bigF3));
    }

    @Test
    void testNewFragmentsAreNotPinned() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        // Use very large strings to exceed the 10k token delta (approx 4 chars/token)
        // f1: ~20k tokens, f2: ~20k tokens
        ContextFragment f1 = new ContextFragments.StringFragment(
                "f1", cm, "a".repeat(80000), "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment f2 = new ContextFragments.StringFragment(
                "f2", cm, "b".repeat(160000), "f2", SyntaxConstants.SYNTAX_STYLE_NONE);

        Context initialCtx = new Context(cm).addFragments(f1);
        Context ctx = initialCtx.addFragments(f2);
        LutzAgent agent = new LutzAgent(
                ctx, "goal", new OfflineStreamingModel(), null, new NoOpConsoleIO(), LutzAgent.ScanConfig.disabled());

        // f1 is "old" (in both turns), f2 is "new" (added this turn)
        // To ensure cacheWeight > 0, we need some churn.
        ContextFragment fluxF =
                new ContextFragments.StringFragment("flux", cm, "flux", "flux", SyntaxConstants.SYNTAX_STYLE_NONE);
        Context lastTurn = initialCtx.addFragments(fluxF);

        Context pinned = agent.applyPinning(ctx, lastTurn);

        assertTrue(pinned.isPinned(f1), "Old fragment with high cache loss should be pinned");
        assertFalse(pinned.isPinned(f2), "New fragment should never be pinned");
    }

    @Test
    void testConvergenceScoreEdgeCases() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        Context empty = new Context(cm);
        LutzAgent agent = new LutzAgent(
                empty, "goal", new OfflineStreamingModel(), null, new NoOpConsoleIO(), LutzAgent.ScanConfig.disabled());

        // Case 1: No last turn
        assertTrue(agent.calculateConvergenceScore(empty, null) == 0.0);

        // Case 2: Union is empty (both contexts empty)
        assertTrue(agent.calculateConvergenceScore(empty, empty) == 0.0);

        // Case 3: Identity (converged)
        ContextFragment f1 =
                new ContextFragments.StringFragment(cm, "content", "f1", SyntaxConstants.SYNTAX_STYLE_NONE);
        Context ctx1 = empty.addFragments(f1);
        assertTrue(agent.calculateConvergenceScore(ctx1, ctx1) == 1.0);
    }

    @Test
    void computeOverflowGrowth_reportsNetGrowthAndAddedFragmentsSortedByTokens() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var agent = newAgent(cm);

        Context checkpoint = cm.liveContext()
                .addFragments(new ContextFragments.StringFragment(cm, "base", "base-fragment", "text/plain"));

        Context overflow = checkpoint.addFragments(List.of(
                new ContextFragments.StringFragment(cm, "a".repeat(24000), "large-fragment", "text/plain"),
                new ContextFragments.StringFragment(cm, "b".repeat(12000), "smaller-fragment", "text/plain")));

        LutzAgent.OverflowGrowth growth = agent.computeOverflowGrowth(checkpoint, overflow);

        assertTrue(growth.netGrowthTokens() > 0, "Expected positive token growth from checkpoint to overflow");
        assertEquals(2, growth.addedFragments().size(), "Expected two added fragments in delta");
        assertTrue(
                growth.addedFragments().get(0).tokens()
                        >= growth.addedFragments().get(1).tokens(),
                "Added fragments should be sorted by descending token count");
    }

    @Test
    void buildOverflowRecoveryHarnessNote_includesTokenGrowthAndFragmentDetails() throws InterruptedException {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var agent = newAgent(cm);

        Context checkpoint = cm.liveContext();
        Context overflow = checkpoint.addFragments(List.of(
                new ContextFragments.StringFragment(cm, "x".repeat(16000), "frag-one", "text/plain"),
                new ContextFragments.StringFragment(cm, "y".repeat(8000), "frag-two", "text/plain")));

        LutzAgent.OverflowGrowth growth = agent.computeOverflowGrowth(checkpoint, overflow);
        String note = agent.buildOverflowRecoveryHarnessNote(growth);

        assertTrue(note.startsWith("[HARNESS NOTE:"), "Note should use HARNESS NOTE format");
        assertTrue(note.contains("Context grew by"), "Note should contain token growth statement");
        assertTrue(note.contains("Added fragments:"), "Note should include fragment list wording");
        // Labels for StringFragments default to "fragment <id>" or "Summary of..." if no description is provided to
        // ctor
        assertTrue(note.contains("fragment"), "Note should contain fragment labels");
    }

    @Test
    void scanConfig_factoryMethods_produceCorrectConfigurations() {
        var defaults = LutzAgent.ScanConfig.defaults();
        assertTrue(defaults.autoScan(), "defaults() should have autoScan=true");
        assertNull(defaults.scanModel(), "defaults() should have scanModel=null");
        assertTrue(defaults.appendToScope(), "defaults() should have appendToScope=true");

        var disabled = LutzAgent.ScanConfig.disabled();
        assertFalse(disabled.autoScan(), "disabled() should have autoScan=false");
        assertNull(disabled.scanModel(), "disabled() should have scanModel=null");
        assertTrue(disabled.appendToScope(), "disabled() should have appendToScope=true");

        var noAppend = LutzAgent.ScanConfig.noAppend();
        assertTrue(noAppend.autoScan(), "noAppend() should have autoScan=true");
        assertNull(noAppend.scanModel(), "noAppend() should have scanModel=null");
        assertFalse(noAppend.appendToScope(), "noAppend() should have appendToScope=false");
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_exposes_createOrReplaceTaskList_inAllowedTools() {
        // Requires full SearchAgent instantiation with ContextManager
        // See SearchAgent.calculateAllowedToolNames() which includes the tool
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_categorizesTool_bothTaskListToolsAsTerminal() {
        // Requires full SearchAgent instantiation with ContextManager
        // createOrReplaceTaskList is categorized as TERMINAL
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_assignsPriority_bothTaskListToolsAtSamePriority() {
        // Requires full SearchAgent instantiation with ContextManager
        // The tool has priority 100 (see SearchAgent.priority() method)
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_lutzObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // LUTZ objective exposes createOrReplaceTaskList
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_tasksOnlyObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // TASKS_ONLY objective exposes createOrReplaceTaskList
    }
}
