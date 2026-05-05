package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        assertEquals(md + "\n\n" + Messages.formatForDisplay(List.of(msg2)), updated.mopMarkdown());

        // llmLog should be kept consistent when present (still framed for LLM consumption)
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

        assertEquals(md + "\n\n" + Messages.formatForDisplay(List.of(msg2)), updated.mopMarkdown());
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
        assertEquals(md + "\n\n" + Messages.formatForDisplay(List.of(msg2, msg3)), updated.mopMarkdown());
    }

    @Test
    void withAppendedMopMessages_summaryOnly_isNoOp() {
        var entry = TaskEntry.fromCompressed(3, "compressed");

        var updated = entry.withAppendedMopMessages(List.of(UserMessage.from("extra")), "new-desc");

        assertSame(entry, updated);
        assertNull(updated.mopLog());
        assertEquals("compressed", updated.summary());
    }

    @Test
    void description_compressed_returnsSummaryForDisplay() {
        var entry = TaskEntry.fromCompressed(3, "compressed summary");
        assertEquals("compressed summary", entry.description());
    }

    @Test
    void mopMessages_markdownBacked_returnsSystemMessageWithMarkdown() {
        var md = "# Log\n\nhello";
        var entry = new TaskEntry(7, "desc", md, md, null, null);
        assertEquals(List.of(Messages.customSystem(md)), entry.mopMessages());
    }

    @Test
    void mopMessages_compressed_returnsSystemMessageWithSummary() {
        var entry = TaskEntry.fromCompressed(3, "compressed summary");
        assertEquals(List.of(Messages.customSystem("compressed summary")), entry.mopMessages());
    }

    @Test
    void mopMessages_legacyFramedMarkdown_returnsTypedSegments() {
        // Pre-#3443 sessions persist task content as markdown that already contains
        // <message type=X>...</message> framing. mopMessages() must parse that back
        // into per-segment typed ChatMessage instances rather than wrapping the whole
        // blob in a single CustomMessage (which would leak the literal framing tags
        // to display-side renderers).
        var framed =
                """
                <message type=user>
                  hello
                </message>

                <message type=ai>
                  hi
                </message>
                """;
        var entry = new TaskEntry(7, "desc", framed, framed, null, null);

        var msgs = List.copyOf(entry.mopMessages());

        assertEquals(2, msgs.size());
        assertInstanceOf(UserMessage.class, msgs.get(0));
        assertInstanceOf(AiMessage.class, msgs.get(1));
    }

    @Test
    void mopMessages_legacyFramedFromFixture_reconstructsTypedMessages() throws IOException {
        // Real-data fixture: shaped after a pre-#3443 persisted session that originally
        // tripped parser regressions (empty leading custom wrapper, AI block with
        // Reasoning/Text/Tool calls section labels, and a trailing custom tool-result
        // block). Locks the call-site contract against future parser changes.
        var fixture = Files.readString(Path.of("src/test/resources/legacy-framing/sample-session.md"));
        var entry = new TaskEntry(11, "fixture", fixture, fixture, null, null);

        var msgs = List.copyOf(entry.mopMessages());

        assertEquals(4, msgs.size());
        assertInstanceOf(UserMessage.class, msgs.get(0));
        assertInstanceOf(AiMessage.class, msgs.get(1));
        assertInstanceOf(AiMessage.class, msgs.get(2));
        assertInstanceOf(CustomMessage.class, msgs.get(3));
        assertEquals("list the open ACP issues in this repo", Messages.getText(msgs.get(0)));
        assertEquals("Found 17 open issues with the ACP label.", Messages.getText(msgs.get(2)));
    }
}
