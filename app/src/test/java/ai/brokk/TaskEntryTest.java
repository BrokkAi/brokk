package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEntryTest {
    @Test
    void withAppendedMopMessages_emptyAdditional_returnsSameInstance() {
        var msg1 = (ChatMessage) UserMessage.from("one");
        var md = Messages.format(List.of(msg1));

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, "desc", md, md, null, meta);

        assertSame(entry, entry.withAppendedMopMessages(List.of(), "new-desc"));
    }

    @Test
    void withAppendedMopMessages_appendsAndPreservesSequenceMetaAndSummary() {
        var msg1 = (ChatMessage) UserMessage.from("one");
        var msg2 = (ChatMessage) UserMessage.from("two");
        var md = Messages.format(List.of(msg1));

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, "old-desc", md, md, "existing-summary", meta);

        var updated = entry.withAppendedMopMessages(List.of(msg2), "new-desc");

        assertEquals(7, updated.sequence());
        assertEquals(meta, updated.meta());
        assertEquals("existing-summary", updated.summary());
        assertEquals("new-desc", updated.description());

        assertEquals(md + "\n\n" + Messages.format(List.of(msg2)), updated.mopMarkdown());

        // llmLog should be kept consistent when present
        assertEquals(md + "\n\n" + Messages.format(List.of(msg2)), updated.llmMarkdown());
    }

    @Test
    void withAppendedMopMessages_appendsVerbatim_andKeepsLlmLogConsistent() {
        var msg1 = (ChatMessage) UserMessage.from("msg1");
        var msg2 = (ChatMessage) UserMessage.from("msg2");
        var md = Messages.format(List.of(msg1));

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, "old-desc", md, md, "existing-summary", meta);

        var updated = entry.withAppendedMopMessages(List.of(msg2), "new-desc");

        assertEquals(md + "\n\n" + Messages.format(List.of(msg2)), updated.mopMarkdown());
        assertEquals(md + "\n\n" + Messages.format(List.of(msg2)), updated.llmMarkdown());
    }

    @Test
    void withAppendedMopMessages_fullyRedundantAdditional_returnsSameInstance_andDoesNotChangeDescription() {
        var msg2 = (ChatMessage) UserMessage.from("msg2");
        var msg3 = (ChatMessage) UserMessage.from("msg3");
        var md = Messages.format(List.of(msg2, msg3));

        var meta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, null);
        var entry = new TaskEntry(7, "old-desc", md, md, null, meta);

        var updated = entry.withAppendedMopMessages(List.of(msg2, msg3), "new-desc");

        assertEquals(7, updated.sequence());
        assertEquals("new-desc", updated.description());
        assertEquals(md + "\n\n" + Messages.format(List.of(msg2, msg3)), updated.mopMarkdown());
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
