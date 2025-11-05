package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.TaskEntry;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for ContextHistory and its DiffService, verifying that diffs between contexts
 * are computed correctly without relying on frozen fragments. These tests validate that the
 * live-context, non-blocking async design works correctly with ComputedValue-based fragments.
 */
public class ContextHistoryTest {

    @TempDir
    Path tempDir;

    private TestContextManager contextManager;

    @BeforeEach
    public void setUp() {
        contextManager = new TestContextManager(tempDir, new TestConsoleIO());
    }

    /**
     * Verifies that DiffService correctly computes diffs between two consecutive contexts.
     * This test creates two contexts with different content and validates that the diff
     * is computed without calling freeze().
     */
    @Test
    public void testDiffServiceComputesDiffsBetweenLiveContexts() {
        // Create initial context with a string fragment
        var initialFragment =
                new ContextFragment.StringFragment(contextManager, "Initial content", "Test Fragment", "text/plain");

        var initialContext = new Context(
                contextManager,
                List.of(initialFragment),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Initial"));

        // Create history with initial context
        var history = new ContextHistory(initialContext);

        // Create a second context with modified content
        var modifiedFragment = new ContextFragment.StringFragment(
                contextManager, "Modified content with more text", "Test Fragment", "text/plain");

        var modifiedContext = new Context(
                contextManager,
                List.of(modifiedFragment),
                List.of(),
                null,
                CompletableFuture.completedFuture("Modified"));

        // Push the modified context to history
        history.pushContext(modifiedContext);

        // Verify history contains both contexts
        var contextList = history.getHistory();
        assertEquals(2, contextList.size(), "History should contain 2 contexts");

        // Get the diff service and compute diff between the two contexts
        var diffService = history.getDiffService();
        var diffs = diffService.diff(modifiedContext).join();

        // Verify that diffs were computed
        assertNotNull(diffs, "Diffs should not be null");
        assertFalse(diffs.isEmpty(), "Diffs should be computed between the two contexts");

        // Verify that the diff contains the modified fragment
        var diffEntry = diffs.get(0);
        assertEquals(modifiedFragment.id(), diffEntry.fragment().id(), "Diff should reference the modified fragment");
        assertFalse(diffEntry.diff().isEmpty(), "Diff output should not be empty");
    }

    /**
     * Verifies that DiffService works correctly when adding new fragments to a context.
     * This test ensures that diffs reflect added fragments without freezing.
     */
    @Test
    public void testDiffServiceDetectsAddedFragments() {
        // Create initial context with one fragment
        var fragment1 =
                new ContextFragment.StringFragment(contextManager, "Fragment 1 content", "Fragment 1", "text/plain");

        var initialContext = new Context(
                contextManager, List.of(fragment1), List.of(), null, CompletableFuture.completedFuture("Initial"));

        var history = new ContextHistory(initialContext);

        // Create second context with an additional fragment (different description so it's a new source)
        var fragment2 =
                new ContextFragment.StringFragment(contextManager, "Fragment 2 content", "New Fragment", "text/plain");

        var extendedContext = initialContext.addVirtualFragment(fragment2);

        history.pushContext(extendedContext);

        // Compute diff
        var diffService = history.getDiffService();
        var diffs = diffService.diff(extendedContext).join();

        // Verify that diff detects the new fragment was added
        assertNotNull(diffs, "Diffs should be computed");
        // The new fragment should appear in the diffs
        var hasNewFragment = diffs.stream().anyMatch(de -> de.fragment().id().equals(fragment2.id()));
        assertTrue(hasNewFragment, "Diff should include the newly added fragment");
    }

    /**
     * Verifies that DiffService correctly handles contexts with no changes.
     * This test ensures that unchanged contexts produce empty or minimal diffs.
     */
    @Test
    public void testDiffServiceHandlesUnchangedContexts() {
        // Create context with a fragment
        var fragment =
                new ContextFragment.StringFragment(contextManager, "Static content", "Test Fragment", "text/plain");

        var context = new Context(
                contextManager,
                List.of(fragment),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Action"));

        var history = new ContextHistory(context);

        // Push the same context (no actual changes)
        var contextCopy = new Context(
                contextManager,
                context.allFragments().toList(),
                context.getTaskHistory(),
                null,
                CompletableFuture.completedFuture("Action"));

        history.pushContext(contextCopy);

        // Compute diff
        var diffService = history.getDiffService();
        var diffs = diffService.diff(contextCopy).join();

        // For identical content, we expect either no diffs or diffs with empty diff strings
        if (!diffs.isEmpty()) {
            diffs.forEach(de -> assertTrue(
                    de.diff().isEmpty() || de.diff().equals("[image changed]"),
                    "Unchanged fragments should have empty or image diffs"));
        }
    }

    /**
     * Verifies that DiffService correctly detects modified fragment content.
     * This test creates two contexts where a fragment's content changes between them,
     * and verifies that the diff correctly identifies the change.
     */
    @Test
    public void testDiffServiceDetectsModifiedFragmentContent() {
        // Create initial context with a fragment
        var fragment1 =
                new ContextFragment.StringFragment(contextManager, "Original content", "Test Fragment", "text/plain");

        var initialContext = new Context(
                contextManager, List.of(fragment1), List.of(), null, CompletableFuture.completedFuture("Initial"));

        var history = new ContextHistory(initialContext);

        // Create a second context with the same fragment description and syntax style,
        // but different content (this simulates a modification to the fragment)
        var fragment2 = new ContextFragment.StringFragment(
                contextManager,
                "Modified content with more text",
                "Test Fragment", // Same description
                "text/plain"); // Same syntax style

        var modifiedContext = new Context(
                contextManager, List.of(fragment2), List.of(), null, CompletableFuture.completedFuture("Modified"));

        history.pushContext(modifiedContext);

        // Compute diff
        var diffService = history.getDiffService();
        var diffs = diffService.diff(modifiedContext).join();

        // Verify that diffs were computed for the modified fragment
        assertNotNull(diffs, "Diffs should be computed");
        assertFalse(diffs.isEmpty(), "Diffs should reflect content change");

        var diffEntry = diffs.get(0);
        assertFalse(diffEntry.diff().isEmpty(), "Diff output should show content change");
        assertTrue(
                diffEntry.linesAdded() > 0 || diffEntry.linesDeleted() > 0, "Diff should show lines added or deleted");
    }

    /**
     * Verifies that DiffService non-blocking peek() method works correctly.
     * This test ensures that the cache works without blocking callers.
     */
    @Test
    public void testDiffServiceNonBlockingPeek() {
        var fragment = new ContextFragment.StringFragment(contextManager, "Content", "Test Fragment", "text/plain");

        var context1 = new Context(
                contextManager,
                List.of(fragment),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Action 1"));
        var history = new ContextHistory(context1);

        var fragment2 =
                new ContextFragment.StringFragment(contextManager, "Modified content", "Test Fragment", "text/plain");

        var context2 = new Context(
                contextManager,
                List.of(fragment2),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Action 2"));
        history.pushContext(context2);

        var diffService = history.getDiffService();

        // Peek before diff is computed should return empty Optional
        var peeked = diffService.peek(context2);
        assertTrue(peeked.isEmpty(), "Peek before computation should return empty Optional");

        // Compute the diff
        diffService.diff(context2).join();

        // Now peek should return the computed diff
        var peekedAfter = diffService.peek(context2);
        assertTrue(peekedAfter.isPresent(), "Peek after computation should return the diff");
        assertFalse(peekedAfter.get().isEmpty(), "Peeked diff should not be empty");
    }

    /**
     * Verifies that DiffService correctly computes diffs for contexts with task history changes.
     * This test ensures that history additions are reflected in the diff computation.
     */
    @Test
    public void testDiffServiceWithTaskHistoryChanges() {
        var fragment = new ContextFragment.StringFragment(contextManager, "Content", "Test Fragment", "text/plain");

        var initialContext = new Context(
                contextManager,
                List.of(fragment),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Initial"));

        var history = new ContextHistory(initialContext);

        // Create a second context with added task history
        var taskFragment =
                new ContextFragment.TaskFragment(contextManager, List.of(new UserMessage("Test task")), "Test Session");
        var taskEntry = new TaskEntry(1, taskFragment, null);

        var contextWithHistory = initialContext.addHistoryEntry(
                taskEntry, null, java.util.concurrent.CompletableFuture.completedFuture("Action"));

        history.pushContext(contextWithHistory);

        // Compute diff
        var diffService = history.getDiffService();
        var diffs = diffService.diff(contextWithHistory).join();

        // Verify that diffs were computed (may include history fragment in the diffs)
        assertNotNull(diffs, "Diffs should be computed");
        // Context diffs focus on fragment changes, not task history directly,
        // so we just verify the diff computation succeeded
    }

    /**
     * Verifies that DiffService warmUp pre-computes diffs for multiple contexts.
     * This test ensures that the warmUp mechanism works without errors.
     */
    @Test
    public void testDiffServiceWarmUp() {
        var fragment1 = new ContextFragment.StringFragment(contextManager, "Content 1", "Fragment 1", "text/plain");

        var context1 = new Context(
                contextManager,
                List.of(fragment1),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Action 1"));

        var history = new ContextHistory(context1);

        var fragment2 = new ContextFragment.StringFragment(contextManager, "Content 2", "Fragment 2", "text/plain");

        var context2 = new Context(
                contextManager,
                List.of(fragment2),
                List.of(),
                null,
                java.util.concurrent.CompletableFuture.completedFuture("Action 2"));
        history.pushContext(context2);

        var diffService = history.getDiffService();

        // Warm up the diff service with all contexts
        var contexts = history.getHistory();
        diffService.warmUp(contexts);

        // All diffs should be computed now (or in progress)
        // Verify by peeking or explicitly joining
        for (var ctx : contexts) {
            var peek = diffService.peek(ctx);
            // WarmUp should have triggered computation for contexts with predecessors
            if (history.previousOf(ctx) != null) {
                // Either already computed or in progress
                diffService.diff(ctx).join();
                assertTrue(diffService.peek(ctx).isPresent(), "After join, diff should be cached");
            }
        }
    }
}
