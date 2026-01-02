package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
        TestConsoleIO consoleIO = new TestConsoleIO();
        contextManager = new TestContextManager(tempDir, consoleIO);
        ContextFragments.setMinimumId(1);
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

        var initialFragment = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Seed computed text to simulate live context readiness
        initialFragment.text().await(Duration.ofSeconds(2));

        var initialContext = new Context(contextManager, List.of(initialFragment), List.of(), null);

        var history = new ContextHistory(initialContext);

        // Modify file for new context
        Files.writeString(pf.absPath(), "Initial content\nModified content with more text\n");
        var modifiedFragment = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var modifiedContext = new Context(contextManager, List.of(modifiedFragment), List.of(), null);

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
        var initialContext = new Context(contextManager, List.of(), List.of(), null);
        var history = new ContextHistory(initialContext);

        // Create new file and context that includes it
        var pf = new ProjectFile(tempDir, "src/NewFile.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "New file content\nline2\n");

        var projectFrag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var extendedContext = new Context(contextManager, List.of(projectFrag), List.of(), null);

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

        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx1 = new Context(contextManager, List.of(frag1), List.of(), null);
        var history = new ContextHistory(ctx1);

        // Create a second context with the same file content
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(contextManager, List.of(frag2), List.of(), null);
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

        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Seed computed text to snapshot original content for diff
        frag1.text().await(Duration.ofSeconds(2));
        var ctx1 = new Context(contextManager, List.of(frag1), List.of(), null);
        var history = new ContextHistory(ctx1);

        Files.writeString(pf.absPath(), "Original content\nModified content with more text\n");
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(contextManager, List.of(frag2), List.of(), null);
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

        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Seed computed text to snapshot original content for diff
        frag1.text().await(Duration.ofSeconds(2));
        var ctx1 = new Context(contextManager, List.of(frag1), List.of(), null);
        var history = new ContextHistory(ctx1);

        Files.writeString(pf.absPath(), "Modified content\n");
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(contextManager, List.of(frag2), List.of(), null);
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

        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var initialContext = new Context(contextManager, List.of(frag), List.of(), null);

        var history = new ContextHistory(initialContext);

        // Add task history only; no file changes
        var taskFragment = new ContextFragments.TaskFragment(
                contextManager, List.of(new UserMessage("Test task")), "Test Session");
        var taskEntry = new TaskEntry(1, taskFragment, null);
        CompletableFuture.completedFuture("Action");
        var contextWithHistory = initialContext.addHistoryEntry(taskEntry, null);
        history.pushContext(contextWithHistory);

        var diffs = history.getDiffService().diff(contextWithHistory).join();

        // Since file didn't change, expect no diffs; we just ensure computation succeeded
        assertTrue(diffs.isEmpty(), diffs.toString());
    }

    /**
     * Verifies that undo/redo operations restore file content from the context snapshot,
     * not from the live file system state at the time of undo/redo.
     */
    @Test
    public void testUndoRedoRestoresSnapshotContent() throws Exception {
        // 1. Initial state: file with "version 1"
        var pf = new ProjectFile(tempDir, "src/Test.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "version 1");

        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx1 = new Context(contextManager, List.of(frag1), List.of(), null);
        var history = new ContextHistory(ctx1);

        // 2. Second state: file with "version 2"
        Files.writeString(pf.absPath(), "version 2");
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx2 = new Context(contextManager, List.of(frag2), List.of(), null);
        history.pushContext(ctx2); // This should trigger snapshot of ctx1

        // 3. External change: file is now "version 3"
        Files.writeString(pf.absPath(), "version 3");
        assertEquals("version 3", Files.readString(pf.absPath()), "File should have external changes before undo");

        // 4. UNDO: should revert to ctx1 state, writing "version 1"
        history.undo(1, contextManager.getIo(), contextManager.getProject());
        assertEquals("version 1", Files.readString(pf.absPath()), "Undo should restore content from first snapshot");
        assertEquals(ctx1.id(), history.liveContext().id(), "Live context should be ctx1 after undo");

        // 5. REDO: should restore ctx2 state, writing "version 2"
        history.redo(contextManager.getIo(), contextManager.getProject());
        assertEquals("version 2", Files.readString(pf.absPath()), "Redo should restore content from second snapshot");
        assertEquals(ctx2.id(), history.liveContext().id(), "Live context should be ctx2 after redo");
    }

    @Test
    public void testProcessExternalFileChanges_noContentChange_returnsNullAndNoPush() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Noop.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "v1");

        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag.text().await(Duration.ofSeconds(2));

        var initialContext = new Context(contextManager, List.of(frag), List.of(), null);
        var history = new ContextHistory(initialContext);

        var initialSize = history.getHistory().size();
        var initialId = history.liveContext().id();

        // Touch the file with the same content (mtime may change, content does not)
        Files.writeString(pf.absPath(), "v1");

        var updated = history.processExternalFileChangesIfNeeded(Set.of(pf));

        assertNull(updated, "No new context should be created for no-op content changes");
        assertEquals(initialSize, history.getHistory().size(), "History size should remain unchanged");
        assertEquals(initialId, history.liveContext().id(), "Live context should remain the initial one");
        Context context = history.liveContext();
        assertFalse(
                context.getAction(initialContext).join().startsWith("Load external changes"),
                "Action should not be an external-changes label for no-op changes");
    }

    @Test
    public void testProcessExternalFileChanges_contentChange_pushesEntryAndDiffsNotEmpty() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Changed.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "v1\n");

        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag.text().await(Duration.ofSeconds(2));

        var initialContext = new Context(contextManager, List.of(frag), List.of(), null);
        var history = new ContextHistory(initialContext);

        Files.writeString(pf.absPath(), "v1\nv2\n");

        var updated = history.processExternalFileChangesIfNeeded(Set.of(pf));

        assertNotNull(updated, "A context should be returned when content changes");
        assertEquals(updated.id(), history.liveContext().id(), "Returned context should be the new live context");
        assertTrue(
                updated.getAction(initialContext).join().startsWith("Load External Changes"),
                "Action should indicate external changes");

        var prev = history.previousOf(updated);
        assertNotNull(prev, "There must be a previous context to diff against");

        var changedFiles = DiffService.getChangedFiles(updated, prev);
        assertFalse(changedFiles.isEmpty(), "Changed files should not be empty");
        assertTrue(changedFiles.contains(pf), "Changed files should include the modified ProjectFile");

        var diffs = history.getDiffService().diff(updated).join();
        assertNotNull(diffs, "Diffs should be computed");
        assertFalse(diffs.isEmpty(), "Diffs should reflect content change");
    }

    @Test
    public void testProcessExternalFileChanges_continuationCounterIncrementsAndReplacesTop() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Counter.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "one\n");

        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag.text().await(Duration.ofSeconds(2));

        var initialContext = new Context(contextManager, List.of(frag), List.of(), null);
        var history = new ContextHistory(initialContext);

        var sizeBefore = history.getHistory().size();

        // First external change -> should push a new entry
        Files.writeString(pf.absPath(), "one\ntwo\n");
        var first = history.processExternalFileChangesIfNeeded(Set.of(pf));
        assertNotNull(first, "First external change should produce a new context");
        assertEquals(sizeBefore + 1, history.getHistory().size(), "History should grow by one on first change");
        assertEquals(
                "Load External Changes", first.getAction(initialContext).join(), "First change has no counter suffix");

        var prevOfFirst = history.previousOf(first);
        assertNotNull(prevOfFirst, "Previous of first should be the original context");

        // Second external change -> should replace top
        Files.writeString(pf.absPath(), "one\ntwo\nthree\n");
        var second = history.processExternalFileChangesIfNeeded(Set.of(pf));
        assertNotNull(second, "Second external change should produce an updated context");
        assertEquals(sizeBefore + 1, history.getHistory().size(), "Second change should replace the top (no growth)");
        assertEquals("Load External Changes", second.getAction(initialContext).join());

        var prevOfSecond = history.previousOf(second);
        assertNotNull(prevOfSecond, "Previous of second should exist");
        assertEquals(
                prevOfFirst.id(),
                prevOfSecond.id(),
                "Previous of second should still be the original context (top replaced)");

        var changed = DiffService.getChangedFiles(second, prevOfSecond);
        assertTrue(changed.contains(pf), "Changed files should include the modified ProjectFile");
    }

    /**
     * We use a mock usage fragment as it will satisfy control flow such as `ContextFragment.isEditable` while
     * avoiding dependencies and computation from analyzers/usage finders.
     */
    private static final class MockUsageFragment extends ContextFragments.AbstractStaticFragment {
        public MockUsageFragment(IContextManager cm, String id, String text) {
            super(
                    id,
                    "Mock Usage",
                    "Usage",
                    "text",
                    ContextFragments.ContentSnapshot.textSnapshot(text, Set.of(), Set.of()));
        }

        @Override
        public ContextFragment.FragmentType getType() {
            return ContextFragment.FragmentType.USAGE;
        }
    }

    @Test
    public void
            testProcessExternalFileChanges_withUsageFragment_fileChange_producesNonEmptyDiffsAndExcludesUsageInDiffs()
                    throws Exception {
        var pf = new ProjectFile(tempDir, "src/WithUsage.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "base\n");

        var projectFrag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        projectFrag1.text().await(Duration.ofSeconds(2)); // seed snapshot
        var usageFrag1 = new MockUsageFragment(contextManager, "U1", "U1");

        var initialContext = new Context(contextManager, List.of(projectFrag1, usageFrag1), List.of(), null);
        var history = new ContextHistory(initialContext);

        // Change the file on disk
        Files.writeString(pf.absPath(), "base\nchanged\n");

        var updated = history.processExternalFileChangesIfNeeded(Set.of(pf));
        assertNotNull(updated, "Expected a new context for actual content change");
        assertEquals(updated.id(), history.liveContext().id(), "Returned context should be live");
        assertNotEquals(initialContext, updated);
        assertTrue(
                updated.getAction(initialContext).join().startsWith("Load External Changes"),
                "Action should indicate external change");

        var prev = history.previousOf(updated);
        assertNotNull(prev, "There must be a previous context to diff against");
        var changed = DiffService.getChangedFiles(updated, prev);
        assertFalse(changed.isEmpty(), "Changed files should not be empty");
        assertTrue(changed.contains(pf), "Changed files should include the modified ProjectFile");

        // Identify the refreshed project fragment in the updated context
        var projectFrag2 = updated.allFragments()
                .filter(f -> f instanceof ContextFragments.PathFragment)
                .map(f -> (ContextFragments.PathFragment) f)
                .filter(f -> f.file().equals(pf))
                .findFirst()
                .orElseThrow();

        var diffs = history.getDiffService().diff(updated).join();
        assertNotNull(diffs, "Diffs should be computed");
        assertFalse(diffs.isEmpty(), "Diffs should reflect content change");

        var includesFile = diffs.stream().anyMatch(de -> de.fragment().id().equals(projectFrag2.id()));
        var excludesUsage = diffs.stream().noneMatch(de -> de.fragment().id().equals(usageFrag1.id()));

        assertTrue(includesFile, "Diffs should include the project fragment that changed");
        assertTrue(excludesUsage, "Diffs should not include unrelated usage fragments");
    }

    @Test
    public void testProcessExternalFileChanges_withUsageFragment_noFileContentChange_returnsNull() throws Exception {
        var pf = new ProjectFile(tempDir, "src/UsageNoop.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "v1");

        var projectFrag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        projectFrag.text().await(Duration.ofSeconds(2)); // seed snapshot
        var usageFrag = new MockUsageFragment(contextManager, "U2", "something");

        var initialContext = new Context(contextManager, List.of(projectFrag, usageFrag), List.of(), null);
        var history = new ContextHistory(initialContext);

        var beforeSize = history.getHistory().size();
        var beforeId = history.liveContext().id();

        // Touch the file with the same content
        Files.writeString(pf.absPath(), "v1");

        var updated = history.processExternalFileChangesIfNeeded(Set.of(pf));
        assertNull(updated, "No content change => no new context");
        assertEquals(beforeSize, history.getHistory().size(), "History size should be unchanged");
        assertEquals(beforeId, history.liveContext().id(), "Live context should remain the same");
        Context context = history.liveContext();
        assertFalse(
                context.getAction(initialContext).join().startsWith("Load external changes"),
                "Action should not indicate external changes");
    }

    @Test
    public void testDiffServiceIncludesUsageFragmentDiffWhenUsageContentChanges() {
        var usageFrag1 = new MockUsageFragment(contextManager, "U-usage", "alpha\n");
        var initialContext = new Context(contextManager, List.of(usageFrag1), List.of(), null);
        var history = new ContextHistory(initialContext);

        var usageFrag2 = new MockUsageFragment(contextManager, "U-usage", "alpha\nbeta\n");
        var updatedContext = new Context(contextManager, List.of(usageFrag2), List.of(), null);
        history.pushContext(updatedContext);

        var diffs = history.getDiffService().diff(updatedContext).join();

        assertNotNull(diffs);
        assertFalse(diffs.isEmpty(), "Usage fragment content change should produce a diff");

        var usageDiff = diffs.stream()
                .filter(de -> de.fragment().id().equals("U-usage"))
                .findFirst()
                .orElse(null);
        assertNotNull(usageDiff, "Diffs should include the UsageFragment");
        assertFalse(usageDiff.diff().isEmpty(), "Usage diff output should not be empty");
        assertTrue(usageDiff.linesAdded() > 0 || usageDiff.linesDeleted() > 0, "Expected changes in usage diff");
    }

    @Test
    public void testAreDiverged() {
        Context a = new Context(contextManager, List.of(), List.of(), null);
        Context b = new Context(contextManager, List.of(), List.of(), null);
        Context c = new Context(contextManager, List.of(), List.of(), null);

        // [A, B]
        ContextHistory h1 = new ContextHistory(a);
        h1.pushContext(b);

        // [A, B]
        ContextHistory h2 = new ContextHistory(a);
        h2.pushContext(b);

        assertFalse(ContextHistory.areDiverged(h1, h2), "Identical histories should not be diverged");

        // [A, B, C]
        ContextHistory h3 = new ContextHistory(a);
        h3.pushContext(b);
        h3.pushContext(c);

        assertFalse(ContextHistory.areDiverged(h1, h3), "Prefix history should not be diverged (h1 is prefix of h3)");
        assertFalse(ContextHistory.areDiverged(h3, h1), "Prefix history should not be diverged (h1 is prefix of h3)");

        // [A, C]
        ContextHistory h4 = new ContextHistory(a);
        h4.pushContext(c);

        assertTrue(ContextHistory.areDiverged(h1, h4), "Diverged at index 1 ([A, B] vs [A, C])");

        // [A] vs [B]
        ContextHistory hA = new ContextHistory(a);
        ContextHistory hB = new ContextHistory(b);
        assertTrue(ContextHistory.areDiverged(hA, hB), "Diverged at index 0");
    }

    @Test
    public void testMerge() {
        Context a = new Context(contextManager, List.of(), List.of(), null);
        Context b = new Context(contextManager, List.of(), List.of(), null);
        Context c = new Context(contextManager, List.of(), List.of(), null);
        Context d = new Context(contextManager, List.of(), List.of(), null);
        Context e = new Context(contextManager, List.of(), List.of(), null);
        Context f = new Context(contextManager, List.of(), List.of(), null);

        // Older: [A, B, C]
        ContextHistory older = new ContextHistory(a);
        older.pushContext(b);
        older.pushContext(c);

        // Newer: [A, B, D, E]
        ContextHistory newer = new ContextHistory(a);
        newer.pushContext(b);
        newer.pushContext(d);
        newer.pushContext(e);

        // Add redo to newer: push F then undo, so history is [A, B, D, E] and redo has [F]
        newer.pushContext(f);
        newer.undo(1, new TestConsoleIO(), contextManager.getProject());

        // Add auxiliary data
        older.addResetEdge(a, c);
        newer.addResetEdge(a, d);

        var gsOlder = new ContextHistory.GitState("hashOlder", null);
        older.addGitState(c.id(), gsOlder);
        var gsNewer = new ContextHistory.GitState("hashNewer", null);
        newer.addGitState(d.id(), gsNewer);

        ContextHistory merged = ContextHistory.merge(older, newer);

        // Expected history: [A, B, C, D, E]
        List<Context> expectedHistory = List.of(a, b, c, d, e);
        assertEquals(expectedHistory, merged.getHistory(), "Merged history should be [A, B, C, D, E]");

        // Verify redo stack is preserved (should have F)
        assertTrue(merged.hasRedoStates(), "Merged history should have redo states from newer");
        ContextHistory.RedoResult redoResult = merged.redo(new TestConsoleIO(), contextManager.getProject());
        assertTrue(redoResult.wasRedone());
        assertEquals(f.id(), merged.liveContext().id(), "Redo should restore context F");

        // Verify auxiliary data
        assertEquals(2, merged.getResetEdges().size(), "Should combine reset edges");
        assertEquals(
                gsOlder, merged.getGitState(c.id()).orElse(null), "Should preserve git state from older");
        assertEquals(
                gsNewer, merged.getGitState(d.id()).orElse(null), "Should preserve git state from newer");
    }
}
