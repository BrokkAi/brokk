package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for ContextHistory and its DiffService, verifying that diffs between contexts
 * are computed correctly with file-backed ProjectPathFragments (hermetic via @TempDir).
 */
public class ContextHistoryTest {

    @TempDir
    Path tempDir;

    private TestContextManager contextManager;

    @BeforeEach
    public void setUp() {
        contextManager = new TestContextManager(tempDir, new TestConsoleIO());
        ContextFragment.setMinimumId(1);
    }

    /**
     * Verifies that DiffService correctly computes diffs between two consecutive contexts
     * using real files inside the temp project.
     */
    @Test
    public void testDiffServiceComputesDiffsBetweenLiveContexts() throws Exception {
        // Arrange: create a file in the temp project
        var pf = new ProjectFile(tempDir, "src/Test1.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "Initial content\n");

        var initialFragment = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Seed computed text to simulate live context readiness
        initialFragment.computedText().await(Duration.ofSeconds(2));

        var initialContext = new Context(
                contextManager,
                List.of(initialFragment),
                List.of(),
                null,
                CompletableFuture.completedFuture("Initial"));

        var history = new ContextHistory(initialContext);

        // Modify file for new context
        Files.writeString(pf.absPath(), "Initial content\nModified content with more text\n");
        var modifiedFragment = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var modifiedContext = new Context(
                contextManager,
                List.of(modifiedFragment),
                List.of(),
                null,
                CompletableFuture.completedFuture("Modified"));

        history.pushContext(modifiedContext);

        // Act
        var diffs = history.getDiffService().diff(modifiedContext).join();

        // Assert
        assertNotNull(diffs, "Diffs should not be null");
        assertFalse(diffs.isEmpty(), "Diffs should be computed between the two contexts");
        var diffEntry = diffs.getFirst();
        assertEquals(modifiedFragment.id(), diffEntry.fragment().id(), "Diff should reference the modified fragment");
        assertFalse(diffEntry.diff().isEmpty(), "Diff output should not be empty");
        assertTrue(diffEntry.linesAdded() > 0 || diffEntry.linesDeleted() > 0, "Expected some changes");
    }

    /**
     * Verifies that DiffService detects added project file fragments.
     * New untracked files should produce diffs vs. empty.
     */
    @Test
    public void testDiffServiceDetectsAddedFragments() throws Exception {
        // Initial context with no files
        var initialContext =
                new Context(contextManager, List.of(), List.of(), null, CompletableFuture.completedFuture("Initial"));
        var history = new ContextHistory(initialContext);

        // Create new file and context that includes it
        var pf = new ProjectFile(tempDir, "src/NewFile.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "New file content\nline2\n");

        var projectFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var extendedContext = new Context(
                contextManager, List.of(projectFrag), List.of(), null, CompletableFuture.completedFuture("Extended"));

        history.pushContext(extendedContext);

        // Compute diff
        var diffs = history.getDiffService().diff(extendedContext).join();

        // Verify that the new project file appears in the diffs
        assertNotNull(diffs, "Diffs should be computed");
        assertFalse(diffs.isEmpty(), "Adding a new file should produce a diff");
        var hasNewFile = diffs.stream().anyMatch(de -> de.fragment().id().equals(projectFrag.id()));
        assertTrue(hasNewFile, "Diff should include the newly added project file fragment");
    }

    /**
     * Verifies that DiffService correctly handles contexts with no changes.
     * Unchanged project files should not produce diffs.
     */
    @Test
    public void testDiffServiceHandlesUnchangedContexts() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Static.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "Static content\n");

        var frag1 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var ctx1 = new Context(
                contextManager, List.of(frag1), List.of(), null, CompletableFuture.completedFuture("Action"));
        var history = new ContextHistory(ctx1);

        // Create a second context with the same file content
        var frag2 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(
                contextManager, List.of(frag2), List.of(), null, CompletableFuture.completedFuture("Action"));
        history.pushContext(ctx2);

        var diffs = history.getDiffService().diff(ctx2).join();

        // Expect no diffs
        assertNotNull(diffs);
        assertTrue(diffs.isEmpty(), "Unchanged project files should not produce diffs");
    }

    /**
     * Verifies that DiffService correctly detects modified project file content.
     */
    @Test
    public void testDiffServiceDetectsModifiedFragmentContent() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Modified.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "Original content\n");

        var frag1 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Seed computed text to snapshot original content for diff
        frag1.computedText().await(Duration.ofSeconds(2));
        var ctx1 = new Context(
                contextManager, List.of(frag1), List.of(), null, CompletableFuture.completedFuture("Initial"));
        var history = new ContextHistory(ctx1);

        Files.writeString(pf.absPath(), "Original content\nModified content with more text\n");
        var frag2 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(
                contextManager, List.of(frag2), List.of(), null, CompletableFuture.completedFuture("Modified"));
        history.pushContext(ctx2);

        var diffs = history.getDiffService().diff(ctx2).join();

        assertNotNull(diffs);
        assertFalse(diffs.isEmpty(), "Diffs should reflect content change");
        var de = diffs.getFirst();
        assertTrue(de.linesAdded() > 0 || de.linesDeleted() > 0, "Expected lines added/deleted");
        assertFalse(de.diff().isEmpty(), "Diff output should not be empty");
    }

    /**
     * Verifies that DiffService non-blocking peek() works with file-backed contexts.
     */
    @Test
    public void testDiffServiceNonBlockingPeek() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Peek.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "Content\n");

        var frag1 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Seed computed text to snapshot original content for diff
        frag1.computedText().await(Duration.ofSeconds(2));
        var ctx1 = new Context(
                contextManager, List.of(frag1), List.of(), null, CompletableFuture.completedFuture("Action 1"));
        var history = new ContextHistory(ctx1);

        Files.writeString(pf.absPath(), "Modified content\n");
        var frag2 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(
                contextManager, List.of(frag2), List.of(), null, CompletableFuture.completedFuture("Action 2"));
        history.pushContext(ctx2);

        var diffService = history.getDiffService();

        // Peek before diff is computed should return empty Optional
        var peeked = diffService.peek(ctx2);
        assertTrue(peeked.isEmpty(), "Peek before computation should return empty Optional");

        // Compute and then peek
        diffService.diff(ctx2).join();
        var peekedAfter = diffService.peek(ctx2);
        assertTrue(peekedAfter.isPresent(), "Peek after computation should return the diff");
        assertFalse(peekedAfter.get().isEmpty(), "Peeked diff should not be empty");
    }

    /**
     * Verifies that DiffService correctly computes diffs for contexts with task history changes
     * while operating on file-backed project fragments.
     */
    @Test
    public void testDiffServiceWithTaskHistoryChanges() throws Exception {
        var pf = new ProjectFile(tempDir, "src/WithHistory.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "Content\n");

        var frag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var initialContext = new Context(
                contextManager, List.of(frag), List.of(), null, CompletableFuture.completedFuture("Initial"));

        var history = new ContextHistory(initialContext);

        // Add task history only; no file changes
        var taskFragment =
                new ContextFragment.TaskFragment(contextManager, List.of(new UserMessage("Test task")), "Test Session");
        var taskEntry = new TaskEntry(1, taskFragment, null);
        var contextWithHistory =
                initialContext.addHistoryEntry(taskEntry, null, CompletableFuture.completedFuture("Action"));
        history.pushContext(contextWithHistory);

        var diffs = history.getDiffService().diff(contextWithHistory).join();

        // Since file didn't change, expect no diffs; we just ensure computation succeeded
        assertNotNull(diffs, "Diffs should be computed");
        assertTrue(diffs.isEmpty(), "No file changes means no diffs");
    }

    /**
     * Verifies that DiffService warmUp pre-computes diffs for multiple contexts using project files.
     */
    @Test
    public void testDiffServiceWarmUp() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/Content1.txt");
        Files.createDirectories(pf1.absPath().getParent());
        Files.writeString(pf1.absPath(), "Content 1\n");

        var frag1 = new ContextFragment.ProjectPathFragment(pf1, contextManager);
        // Seed computed text to snapshot original content for diff
        frag1.computedText().await(Duration.ofSeconds(2));
        var context1 = new Context(
                contextManager, List.of(frag1), List.of(), null, CompletableFuture.completedFuture("Action 1"));

        var history = new ContextHistory(context1);

        // Modify to create a second context with changes
        Files.writeString(pf1.absPath(), "Content 1\nMore\n");
        var frag2 = new ContextFragment.ProjectPathFragment(pf1, contextManager);
        var context2 = new Context(
                contextManager, List.of(frag2), List.of(), null, CompletableFuture.completedFuture("Action 2"));
        history.pushContext(context2);

        var diffService = history.getDiffService();

        // Warm up the diff service with all contexts
        var contexts = history.getHistory();
        diffService.warmUp(contexts);

        for (var ctx : contexts) {
            if (history.previousOf(ctx) != null) {
                diffService.diff(ctx).join();
                assertTrue(diffService.peek(ctx).isPresent(), "After join, diff should be cached");
            }
        }
    }
}
