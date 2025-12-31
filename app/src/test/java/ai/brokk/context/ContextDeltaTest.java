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
        assertFalse(delta.isEmpty(), "Delta should not be empty when history cleared");
    }
}
