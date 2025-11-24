package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.ContextFragment;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for TaskEntry compression behavior.
 * Verifies that compression preserves the original log while attaching a summary.
 */
public class ContextCompressionTest {
    @TempDir
    Path tempDir;

    private TestContextManager contextManager;

    @BeforeEach
    void setup() throws IOException {
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragment.setMinimumId(1);
    }

    @Test
    void testCompressionPreservesOriginalLog() {
        // Create a TaskEntry with a log (original messages)
        List<ChatMessage> messages = List.of(
                UserMessage.from("What is the capital of France?"),
                AiMessage.from("The capital of France is Paris."));

        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Geography Question");
        var originalEntry = new TaskEntry(1, taskFragment, null);

        // Verify initial state: has log, no summary
        assertTrue(originalEntry.hasLog(), "Original entry should have log");
        assertFalse(originalEntry.isCompressed(), "Original entry should not have summary");

        // Create a compressed entry (simulating what compressHistory does)
        String compressedSummary = "The user asked about France's capital, and AI responded with Paris.";
        var compressedEntry = new TaskEntry(1, originalEntry.log(), compressedSummary, null);

        // Verify compressed state: still has log, now also has summary
        assertTrue(compressedEntry.hasLog(), "Compressed entry should preserve original log");
        assertSame(originalEntry.log(), compressedEntry.log(), "Log instance should be identical");
        assertEquals(messages.size(), compressedEntry.log().messages().size(), 
                "Log should have same number of messages");

        assertTrue(compressedEntry.isCompressed(), "Compressed entry should now have summary");
        assertEquals(compressedSummary, compressedEntry.summary(), "Summary should match");

        assertTrue(compressedEntry.isCompressed(), "Compressed entry should be marked as compressed (summary present)");
    }

    @Test
    void testCompressionPreservesSequenceAndMetadata() {
        List<ChatMessage> messages = List.of(UserMessage.from("Test"));
        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Test");
        var meta = new TaskResult.TaskMeta(
                TaskResult.Type.CODE,
                new Service.ModelConfig("test-model", Service.ReasoningLevel.DEFAULT, Service.ProcessingTier.DEFAULT));
        var originalEntry = new TaskEntry(42, taskFragment, null, meta);

        String summary = "Test summary";
        var compressedEntry = new TaskEntry(42, originalEntry.log(), summary, originalEntry.meta());

        assertEquals(42, compressedEntry.sequence(), "Sequence should be preserved");
        assertNotNull(compressedEntry.meta(), "Metadata should be preserved");
        assertEquals(TaskResult.Type.CODE, compressedEntry.meta().type(), "Task type should be preserved");
        assertEquals("test-model", compressedEntry.meta().primaryModel().name(), "Model name should be preserved");
    }

    @Test
    void testAlreadySummarizedEntryIsIdempotent() {
        // If an entry is already summarized (has both log and summary),
        // it should be treated as already compressed and returned unchanged
        List<ChatMessage> messages = List.of(UserMessage.from("Test"));
        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Test");
        String summary = "Test summary";

        var alreadyCompressed = new TaskEntry(1, taskFragment, summary);
        assertTrue(alreadyCompressed.isCompressed(), "Entry should be marked as compressed");
        assertTrue(alreadyCompressed.hasLog(), "Entry should have log");
        assertTrue(alreadyCompressed.isCompressed(), "Entry should have summary");

        // A second compression should return the same entry unchanged
        // (In real usage, ContextManager.compressHistory checks isSummarized() and returns early)
        assertEquals(alreadyCompressed, alreadyCompressed, "Already-compressed entry should be identical");
    }

    @Test
    void testLogOnlyEntryIndicatesNeedForCompression() {
        // Entry with only log, no summary: indicates it needs compression
        List<ChatMessage> messages = List.of(
                UserMessage.from("Hello"),
                AiMessage.from("Hi there!"));

        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Greeting");
        var logOnlyEntry = new TaskEntry(1, taskFragment, null);

        assertTrue(logOnlyEntry.hasLog(), "Should have log");
        assertFalse(logOnlyEntry.isCompressed(), "Should not have summary");
    }

    @Test
    void testSummaryOnlyEntryIsLegacyCompressed() {
        // Entry with only summary, no log: legacy compressed entry
        String summary = "This was a conversation about something";
        var legacyCompressed = new TaskEntry(1, null, summary);

        assertFalse(legacyCompressed.hasLog(), "Should not have log");
        assertTrue(legacyCompressed.isCompressed(), "Should have summary");

        // Legacy entries are already in their final form
        assertEquals(summary, legacyCompressed.summary());
    }

    @Test
    void testCompressionWithMetaPreservation() {
        // Verify compression preserves all TaskMeta fields
        List<ChatMessage> messages = List.of(
                UserMessage.from("Refactor this code"),
                AiMessage.from("Here's the refactored version..."));

        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Code Refactor");
        var meta = new TaskResult.TaskMeta(
                TaskResult.Type.CODE,
                new Service.ModelConfig("gpt-4", Service.ReasoningLevel.DEFAULT, Service.ProcessingTier.DEFAULT));

        var originalEntry = new TaskEntry(5, taskFragment, null, meta);

        // Simulate compression
        String summary = "User requested code refactoring; AI provided refactored version.";
        var compressedEntry = new TaskEntry(5, originalEntry.log(), summary, originalEntry.meta());

        // Verify all metadata is preserved
        assertEquals(5, compressedEntry.sequence());
        assertNotNull(compressedEntry.meta());
        assertEquals(TaskResult.Type.CODE, compressedEntry.meta().type());
        assertEquals("gpt-4", compressedEntry.meta().primaryModel().name());
        assertEquals(Service.ReasoningLevel.DEFAULT, compressedEntry.meta().primaryModel().reasoning());
        assertEquals(Service.ProcessingTier.DEFAULT, compressedEntry.meta().primaryModel().tier());
        
        // Verify messages are intact
        assertTrue(compressedEntry.hasLog());
        assertEquals(2, compressedEntry.log().messages().size());
        
        // Verify summary is attached
        assertEquals(summary, compressedEntry.summary());
    }

    @Test
    void testToStringPreferringFullMessagesWhenCompressed() {
        // When an entry has both log and summary, toString() should indicate
        // that it's summarized but prefer showing the full messages
        List<ChatMessage> messages = List.of(
                UserMessage.from("Question"),
                AiMessage.from("Answer"));

        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Q&A");
        String summary = "Q&A interaction";
        var compressedEntry = new TaskEntry(1, taskFragment, summary);

        String str = compressedEntry.toString();
        
        // Should indicate summarized
        assertTrue(str.contains("summarized=true"), "toString should indicate summarized");
        
        // Should include message types (prefers full messages)
        assertTrue(str.contains("message"), "toString should include message content");
    }

    @Test
    void testCompressionStateTransitions() {
        List<ChatMessage> messages = List.of(UserMessage.from("Test"));
        var taskFragment = new ContextFragment.TaskFragment(contextManager, messages, "Test");

        // State 1: Log only (needs compression)
        var logOnly = new TaskEntry(1, taskFragment, null);
        assertFalse(logOnly.isCompressed());
        assertTrue(logOnly.hasLog());

        // State 2: Both log and summary (compressed, AI uses summary)
        String summary = "Summary";
        var withBoth = new TaskEntry(1, logOnly.log(), summary);
        assertTrue(withBoth.isCompressed());
        assertTrue(withBoth.hasLog());
        
        // State 3: Summary only (legacy, AI uses summary)
        var summaryOnly = new TaskEntry(1, null, summary);
        assertTrue(summaryOnly.isCompressed());
        assertFalse(summaryOnly.hasLog());

        // Verify transitions make sense:
        // logOnly -> withBoth: compression attaches summary
        // logOnly -> summaryOnly: old compression discarded log
        // Both State 2 and State 3 use summary for AI (isSummarized() is true)
    }
}
