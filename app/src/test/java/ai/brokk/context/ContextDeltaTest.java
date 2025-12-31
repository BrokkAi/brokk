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
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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
    }

    @Test
    void testDelta_identifiesRemovedFragments() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Removed.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Removed {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx1 = new Context(contextManager).addFragments(List.of(ppf));
        var ctx2 = ctx1.removeFragments(List.of(ppf));

        var delta = ContextDelta.between(ctx1, ctx2);

        assertTrue(delta.addedFragments().isEmpty(), "No fragments should be added");
        assertEquals(1, delta.removedFragments().size(), "Should identify removed fragment");
        assertFalse(delta.isEmpty(), "Delta should not be empty");
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
    void testDelta_detectsClearedHistory() {
        var ctx1 = new Context(contextManager);

        List<ChatMessage> msgs = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFrag = new ContextFragments.TaskFragment(contextManager, msgs, "task");
        var entry = new TaskEntry(1, taskFrag, null);
        var ctxWithHistory = ctx1.addHistoryEntry(entry, taskFrag);
        var ctxCleared = ctxWithHistory.clearHistory();

        var delta = ContextDelta.between(ctxWithHistory, ctxCleared);

        assertTrue(delta.clearedHistory(), "Should detect cleared history");
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

        assertTrue(delta.externalChanges());
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
    }
}
