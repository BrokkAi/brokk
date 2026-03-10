package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.brokk.context.ContextFragments;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEntryTest {
    @Test
    void withAppendedMopMessages_emptyAdditional_returnsSameInstance() {
        var msg1 = (ChatMessage) UserMessage.from("one");
        var mopLog = new ContextFragments.TaskFragment(List.of(msg1), "desc");

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, mopLog, mopLog, null, meta);

        assertSame(entry, entry.withAppendedMopMessages(List.of(), "new-desc"));
    }

    @Test
    void withAppendedMopMessages_appendsAndPreservesSequenceMetaAndSummary() {
        var msg1 = (ChatMessage) UserMessage.from("one");
        var msg2 = (ChatMessage) UserMessage.from("two");
        var mopLog = new ContextFragments.TaskFragment(List.of(msg1), "old-desc");

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, mopLog, mopLog, "existing-summary", meta);

        var updated = entry.withAppendedMopMessages(List.of(msg2), "new-desc");

        assertEquals(7, updated.sequence());
        assertEquals(meta, updated.meta());
        assertEquals("existing-summary", updated.summary());

        assertEquals(List.of(msg1, msg2), updated.mopLog().messages());
        assertEquals("new-desc", updated.mopLog().description().join());

        // llmLog should be kept consistent when present
        assertEquals(List.of(msg1, msg2), updated.llmLog().messages());
        assertEquals("new-desc", updated.llmLog().description().join());
    }

    @Test
    void withAppendedMopMessages_trimsOverlapSuffixPrefix_noDuplicates_andKeepsLlmLogConsistent() {
        var msg1 = (ChatMessage) UserMessage.from("msg1");
        var msg2 = (ChatMessage) UserMessage.from("msg2");
        var msg3 = (ChatMessage) UserMessage.from("msg3");
        var msg4 = (ChatMessage) UserMessage.from("msg4");
        var msg5 = (ChatMessage) UserMessage.from("msg5");
        var msg6 = (ChatMessage) UserMessage.from("msg6");

        var mopLog = new ContextFragments.TaskFragment(List.of(msg1, msg2, msg3, msg4, msg5), "old-desc");
        var llmLog = new ContextFragments.TaskFragment(List.of(msg1, msg2, msg3, msg4, msg5), "old-desc");

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, mopLog, llmLog, "existing-summary", meta);

        var updated = entry.withAppendedMopMessages(List.of(msg4, msg5, msg6), "new-desc");

        assertEquals(
                List.of(msg1, msg2, msg3, msg4, msg5, msg6), updated.mopLog().messages());
        assertEquals("new-desc", updated.mopLog().description().join());

        assertEquals(
                List.of(msg1, msg2, msg3, msg4, msg5, msg6), updated.llmLog().messages());
        assertEquals("new-desc", updated.llmLog().description().join());
    }

    @Test
    void withAppendedMopMessages_fullyRedundantAdditional_returnsSameInstance_andDoesNotChangeDescription() {
        var msg1 = (ChatMessage) UserMessage.from("msg1");
        var msg2 = (ChatMessage) UserMessage.from("msg2");
        var msg3 = (ChatMessage) UserMessage.from("msg3");
        var mopLog = new ContextFragments.TaskFragment(List.of(msg1, msg2, msg3), "old-desc");

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, mopLog, mopLog, null, meta);

        var updated = entry.withAppendedMopMessages(List.of(msg2, msg3), "new-desc");

        assertSame(entry, updated);
        assertEquals("old-desc", updated.mopLog().description().join());
    }

    @Test
    void withAppendedMopMessages_summaryOnly_isNoOp() {
        var entry = TaskEntry.fromCompressed(3, "compressed");

        var updated = entry.withAppendedMopMessages(List.of(UserMessage.from("extra")), "new-desc");

        assertSame(entry, updated);
        assertNull(updated.mopLog());
        assertEquals("compressed", updated.summary());
    }
}
