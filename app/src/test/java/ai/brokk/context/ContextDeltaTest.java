package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextDeltaTest {

    @TempDir
    Path tempDir;

    private IContextManager contextManager;

    @BeforeEach
    void setUp() throws Exception {
        var analyzer = new TestAnalyzer(List.of(), Map.of());
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO(), analyzer);
    }

    @Test
    void testDelta_identicalContexts_returnsEmptyDelta() {
        var ctx = new Context(contextManager);

        var delta = ContextDelta.between(ctx, ctx);

        assertTrue(delta.addedFragments().isEmpty(), "No fragments should be added for identical contexts");
        assertTrue(delta.removedFragments().isEmpty(), "No fragments should be removed for identical contexts");
        assertTrue(delta.addedTasks().isEmpty(), "No tasks should be added for identical contexts");
        assertFalse(delta.clearedHistory(), "History was not cleared");
        assertTrue(delta.isEmpty(), "Delta should be empty for identical contexts");
        assertEquals("(No changes)", delta.description(contextManager).join(), "Description should be (No changes) for identical contexts");
    }

    @Test
    void testDelta_identifiesAddedFragments() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Added.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Added {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx1 = new Context(contextManager);
        var ctx2 = ctx1.addFragments(List.of(ppf));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.addedFragments().size(), "Should identify added fragment");
        assertTrue(delta.removedFragments().isEmpty(), "No fragments should be removed");
        assertFalse(delta.isEmpty(), "Delta should not be empty");
        assertEquals("Add Added.java", delta.description(contextManager).join());
    }

    @Test
    void testDelta_identifiesRemovedFragments() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/Keep.java");
        var pf2 = new ProjectFile(tempDir, "src/Removed.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class Keep {}");
        pf2.write("class Removed {}");
        var ppf1 = new ContextFragments.ProjectPathFragment(pf1, contextManager);
        var ppf2 = new ContextFragments.ProjectPathFragment(pf2, contextManager);

        // Include another fragment so it's not a total reset to empty
        var ctx1 = new Context(contextManager).addFragments(List.of(ppf1, ppf2));
        var ctx2 = ctx1.removeFragments(List.of(ppf2));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertTrue(delta.addedFragments().isEmpty(), "No fragments should be added");
        assertEquals(1, delta.removedFragments().size(), "Should identify removed fragment");
        assertFalse(delta.isEmpty(), "Delta should not be empty");
        assertEquals("Remove Removed.java", delta.description(contextManager).join());
    }

    @Test
    void testDelta_identifiesAddedTasks() {
        var ctx1 = new Context(contextManager);

        List<ChatMessage> msgs = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFrag = new ContextFragments.TaskFragment(contextManager, msgs, "task");
        var entry = new TaskEntry(1, taskFrag, null);
        var ctx2 = ctx1.addHistoryEntry(entry, taskFrag);

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.addedTasks().size(), "Should identify added task");
        assertFalse(delta.isEmpty(), "Delta should not be empty");
    }

    @Test
    void testDelta_detectsClearedHistory() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Main.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("content");
        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);

        // Add a fragment so the context isn't empty after clearing history
        var ctx1 = new Context(contextManager).addFragments(List.of(frag));

        List<ChatMessage> msgs = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFrag = new ContextFragments.TaskFragment(contextManager, msgs, "task");
        var entry = new TaskEntry(1, taskFrag, null);
        var ctxWithHistory = ctx1.addHistoryEntry(entry, taskFrag);
        var ctxCleared = ctxWithHistory.clearHistory();

        var delta = ContextDelta.between(ctxWithHistory, ctxCleared);

        assertTrue(delta.clearedHistory(), "Should detect cleared history");
        assertEquals(ContextDelta.CLEARED_TASK_HISTORY, delta.description(contextManager).join());
    }

    @Test
    void testDelta_detectsCompressedHistory() {
        var ctx1 = new Context(contextManager);
        // Create an uncompressed entry (no summary, has log)
        List<ChatMessage> msgs = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFrag = new ContextFragments.TaskFragment(contextManager, msgs, "task");
        var entry1 = new TaskEntry(1, taskFrag, null);
        assertFalse(entry1.isCompressed());
        var ctx2 = ctx1.withHistory(List.of(entry1));

        // Create a compressed version of the same sequence
        var entry1Compressed = TaskEntry.fromCompressed(1, "Compressed log");
        assertTrue(entry1Compressed.isCompressed());
        var ctx3 = ctx2.withHistory(List.of(entry1Compressed));

        var delta = ContextDelta.between(ctx2, ctx3);

        assertTrue(delta.compressedHistory());
    }

    @Test
    void testDelta_detectsUpdatedSpecialFragment() {
        var ctx1 = new Context(contextManager).withSpecial(SpecialTextType.SEARCH_NOTES, "Old Notes");
        var ctx2 = ctx1.withSpecial(SpecialTextType.SEARCH_NOTES, "New Notes");

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.updatedSpecialFragments().size());
    }

    @Test
    void testDelta_detectsExternalChanges() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Main.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("v1");
        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx1 = new Context(contextManager).addFragments(List.of(frag));

        // Update file content but keep same fragment source (ProjectFile)
        pf.write("v2");
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var ctx2 = ctx1.removeFragments(List.of(frag)).addFragments(List.of(frag2));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertTrue(delta.contentsChanged());
    }

    @Test
    void testDelta_detectsResetSession() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Main.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("content");
        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx1 = new Context(contextManager).addFragments(List.of(frag));
        var ctx2 = Context.EMPTY;

        var delta = ContextDelta.between(ctx1, ctx2);

        assertFalse(delta.isEmpty());
        assertTrue(delta.sessionReset());
        assertEquals(ContextDelta.DROPPED_ALL_CONTEXT, delta.description(contextManager).join());
    }

    @Test
    void testDelta_detectsAddedSpecialFragment() {
        var ctx1 = new Context(contextManager);
        var ctx2 = ctx1.withSpecial(SpecialTextType.SEARCH_NOTES, "New Notes");

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.addedFragments().size(), "Special fragment should appear as added");
        assertTrue(delta.updatedSpecialFragments().isEmpty(), "Should not be in updated list when added");
        assertFalse(delta.isEmpty());
    }

    @Test
    void testDelta_detectsRemovedSpecialFragment() {
        var ctx1 = new Context(contextManager).withSpecial(SpecialTextType.SEARCH_NOTES, "Notes");
        var specialFrag = ctx1.getSpecial(SpecialTextType.SEARCH_NOTES.description()).orElseThrow();
        var ctx2 = ctx1.removeFragments(List.of(specialFrag));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.removedFragments().size(), "Special fragment should appear as removed");
        assertTrue(delta.updatedSpecialFragments().isEmpty(), "Should not be in updated list when removed");
        assertFalse(delta.isEmpty());
    }

    @Test
    void testDelta_multipleFragmentsAddedAndRemoved() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/File1.java");
        var pf2 = new ProjectFile(tempDir, "src/File2.java");
        var pf3 = new ProjectFile(tempDir, "src/File3.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class File1 {}");
        pf2.write("class File2 {}");
        pf3.write("class File3 {}");

        var frag1 = new ContextFragments.ProjectPathFragment(pf1, contextManager);
        var frag2 = new ContextFragments.ProjectPathFragment(pf2, contextManager);
        var frag3 = new ContextFragments.ProjectPathFragment(pf3, contextManager);

        var ctx1 = new Context(contextManager).addFragments(List.of(frag1, frag2));
        var ctx2 = ctx1.removeFragments(List.of(frag1)).addFragments(List.of(frag3));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(1, delta.addedFragments().size(), "Should identify frag3 as added");
        assertEquals(1, delta.removedFragments().size(), "Should identify frag1 as removed");
        assertFalse(delta.isEmpty());
    }

    @Test
    void testDelta_multipleFragmentsAdded() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/A.java");
        var pf2 = new ProjectFile(tempDir, "src/B.java");
        var pf3 = new ProjectFile(tempDir, "src/C.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class A {}");
        pf2.write("class B {}");
        pf3.write("class C {}");

        var frag1 = new ContextFragments.ProjectPathFragment(pf1, contextManager);
        var frag2 = new ContextFragments.ProjectPathFragment(pf2, contextManager);
        var frag3 = new ContextFragments.ProjectPathFragment(pf3, contextManager);

        var ctx1 = new Context(contextManager);
        var ctx2 = ctx1.addFragments(List.of(frag1, frag2, frag3));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertEquals(3, delta.addedFragments().size(), "Should identify all three fragments as added");
        assertTrue(delta.removedFragments().isEmpty());
    }

    @Test
    void testDelta_multipleFragmentsRemoved() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/X.java");
        var pf2 = new ProjectFile(tempDir, "src/Y.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class X {}");
        pf2.write("class Y {}");

        var frag1 = new ContextFragments.ProjectPathFragment(pf1, contextManager);
        var frag2 = new ContextFragments.ProjectPathFragment(pf2, contextManager);

        var ctx1 = new Context(contextManager).addFragments(List.of(frag1, frag2));
        var ctx2 = ctx1.removeFragments(List.of(frag1, frag2));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertTrue(delta.addedFragments().isEmpty());
        assertEquals(2, delta.removedFragments().size(), "Should identify both fragments as removed");
    }

    @Test
    void testDelta_findWithSameSource_codeFragment() throws Exception {
        // Use ProjectPathFragment with the same file to test hasSameSource behavior
        // CodeFragment requires a working analyzer, so we use file fragments instead
        var pf = new ProjectFile(tempDir, "src/com/example/MyClass.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class MyClass {}");

        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag1.text().join();
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag2.text().join();

        var ctx1 = new Context(contextManager).addFragments(List.of(frag1));
        var ctx2 = ctx1.removeFragments(List.of(frag1)).addFragments(List.of(frag2));

        var delta = ContextDelta.between(ctx1, ctx2);

        // Same file means same source, so neither added nor removed
        assertTrue(delta.addedFragments().isEmpty(), "Same file should not appear as added");
        assertTrue(delta.removedFragments().isEmpty(), "Same file should not appear as removed");
    }

    @Test
    void testDelta_findWithSameSource_usageFragment() {
        var frag1 = new ContextFragments.UsageFragment(contextManager, "com.example.MyClass.myMethod");
        var frag2 = new ContextFragments.UsageFragment(contextManager, "com.example.MyClass.myMethod");

        var ctx1 = new Context(contextManager).addFragments(List.of(frag1));
        var ctx2 = ctx1.removeFragments(List.of(frag1)).addFragments(List.of(frag2));

        var delta = ContextDelta.between(ctx1, ctx2);

        // Same target identifier means same source
        assertTrue(delta.addedFragments().isEmpty(), "Same target should not appear as added");
        assertTrue(delta.removedFragments().isEmpty(), "Same target should not appear as removed");
    }

    @Test
    void testDelta_findWithSameSource_differentFragmentTypes() throws Exception {
        // ProjectPathFragment and CodeFragment for the same logical file are different sources
        var pf = new ProjectFile(tempDir, "src/com/example/MyClass.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("package com.example; class MyClass {}");

        var fileFrag = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var codeFrag = new ContextFragments.CodeFragment(contextManager, "com.example.MyClass");

        var ctx1 = new Context(contextManager).addFragments(List.of(fileFrag));
        var ctx2 = ctx1.addFragments(List.of(codeFrag));

        var delta = ContextDelta.between(ctx1, ctx2);

        // Different fragment types should be treated as different sources
        assertEquals(1, delta.addedFragments().size(), "CodeFragment should be added");
        assertTrue(delta.removedFragments().isEmpty());
    }

    @Test
    void testDelta_mixedHistoryChanges_tasksAddedAndCompressed() {
        // Start with uncompressed entries
        List<ChatMessage> msgs1 = List.of(UserMessage.from("User1"), AiMessage.from("AI1"));
        var taskFrag1 = new ContextFragments.TaskFragment(contextManager, msgs1, "task1");
        var entry1 = new TaskEntry(1, taskFrag1, null);

        List<ChatMessage> msgs2 = List.of(UserMessage.from("User2"), AiMessage.from("AI2"));
        var taskFrag2 = new ContextFragments.TaskFragment(contextManager, msgs2, "task2");
        var entry2 = new TaskEntry(2, taskFrag2, null);

        var ctx1 = new Context(contextManager).withHistory(List.of(entry1, entry2));

        // Compress first entry and add a new one
        var entry1Compressed = TaskEntry.fromCompressed(1, "Compressed task1");
        List<ChatMessage> msgs3 = List.of(UserMessage.from("User3"), AiMessage.from("AI3"));
        var taskFrag3 = new ContextFragments.TaskFragment(contextManager, msgs3, "task3");
        var entry3 = new TaskEntry(3, taskFrag3, null);

        var ctx2 = ctx1.withHistory(List.of(entry1Compressed, entry2, entry3));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertTrue(delta.compressedHistory(), "Should detect compression");
        assertEquals(1, delta.addedTasks().size(), "Should detect added task");
        assertEquals(entry3, delta.addedTasks().getFirst());
    }

    @Test
    void testDelta_emptyToNonEmpty() throws Exception {
        var pf = new ProjectFile(tempDir, "src/New.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class New {}");
        var frag = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx1 = new Context(contextManager);
        var ctx2 = ctx1.addFragments(List.of(frag));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertFalse(delta.sessionReset(), "Going from empty to non-empty is not a session reset");
        assertEquals(1, delta.addedFragments().size());
        assertFalse(delta.isEmpty());
    }

    @Test
    void testDelta_contentsUnchanged_sameSourceSameText() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Stable.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Stable {}");
        var frag1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Wait for content to be computed
        frag1.text().join();

        var ctx1 = new Context(contextManager).addFragments(List.of(frag1));

        // Create a new fragment for the same file without changing content
        var frag2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        frag2.text().join();

        var ctx2 = ctx1.removeFragments(List.of(frag1)).addFragments(List.of(frag2));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertFalse(delta.contentsChanged(), "Content should not be marked as changed when text is identical");
        assertTrue(delta.addedFragments().isEmpty(), "Same source should not appear as added");
        assertTrue(delta.removedFragments().isEmpty(), "Same source should not appear as removed");
    }
}
