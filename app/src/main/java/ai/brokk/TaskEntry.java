package ai.brokk;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextOutputFragments;
import ai.brokk.util.FragmentUtils;
import ai.brokk.util.LegacyFramingParser;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single task interaction for the Task History, including the user request and the full LLM message log.
 * The log can be compressed to save context space while retaining the most relevant information.
 * Both `log` (original messages) and `summary` can coexist: when present, the AI sees the summary,
 * but the UI prefers to render the full log messages with a visual indicator.
 *
 */
public final class TaskEntry {
    private final int sequence;
    private final String description;
    private final @Nullable String mopMarkdown;
    private final @Nullable String llmMarkdown;
    private final @Nullable String summary;
    private final TaskResult.@Nullable TaskMeta meta;
    private final @Nullable String mopLogId;
    private final @Nullable String llmLogId;

    private transient @Nullable ContextOutputFragments.TaskOutputFragment cachedMopLog;
    private transient @Nullable ContextOutputFragments.TaskOutputFragment cachedLlmLog;

    /** Enforce that at least one of log or summary is non-null */
    public TaskEntry(
            int sequence,
            String description,
            @Nullable String mopMarkdown,
            @Nullable String llmMarkdown,
            @Nullable String summary,
            @Nullable TaskResult.TaskMeta meta) {
        assert (mopMarkdown != null) || (summary != null) : "At least one of mopMarkdown or summary must be non-null";
        assert summary == null || !summary.isEmpty() : "summary must not be empty when present";
        assert !description.isEmpty();
        this.sequence = sequence;
        this.description = description;
        this.mopMarkdown = mopMarkdown;
        this.llmMarkdown = llmMarkdown;
        this.summary = summary;
        this.meta = meta;

        this.mopLogId = mopMarkdown == null ? null : computeTaskLogId(description, mopMarkdown);
        this.llmLogId = llmMarkdown == null ? null : computeTaskLogId(description, llmMarkdown);
    }

    private TaskEntry(
            int sequence,
            String description,
            @Nullable ContextOutputFragments.TaskOutputFragment mopLog,
            @Nullable ContextOutputFragments.TaskOutputFragment llmLog,
            @Nullable String summary,
            @Nullable TaskResult.TaskMeta meta) {
        this(
                sequence,
                description,
                mopLog == null ? null : mopLog.text().join(),
                llmLog == null ? null : llmLog.text().join(),
                summary,
                meta);
        this.cachedMopLog = mopLog;
        this.cachedLlmLog = llmLog;
    }

    public static TaskEntry fromFragments(
            int sequence,
            String description,
            @Nullable ContextOutputFragments.TaskOutputFragment mopLog,
            @Nullable ContextOutputFragments.TaskOutputFragment llmLog,
            @Nullable String summary,
            @Nullable TaskResult.TaskMeta meta) {
        return new TaskEntry(sequence, description, mopLog, llmLog, summary, meta);
    }

    // Some call sites "forge" a TaskEntry where no task existed. This is a smell but for now we allow it.
    public TaskEntry(int sequence, @Nullable String mopMarkdown, @Nullable String summary) {
        this(sequence, "(log)", mopMarkdown, null, summary, null);
    }

    /**
     * Returns a copy with the given non-empty summary attached. Preserves sequence, mopLog and meta,
     * but discards llmLog.
     *
     * If the summary is unchanged, returns this.
     *
     * @param newSummary non-empty summary text
     * @return a new TaskEntry with the provided summary
     */
    public TaskEntry withSummary(String newSummary) {
        assert !newSummary.isEmpty() : "summary must not be empty";
        if (summary != null && summary.equals(newSummary)) {
            return this;
        }
        return new TaskEntry(sequence, description, mopMarkdown, llmMarkdown, newSummary, meta);
    }

    /**
     * Returns a copy of this TaskEntry with additional messages appended to the existing mop log.
     *
     * <p>If additionalMessages is empty, returns this unchanged.
     *
     * <p>If mopLog is null (summary-only entry), this is a no-op; callers should create a new TaskEntry
     * rather than mutating a summary-only entry.
     *
     * <p>Preserves the existing mop log description.
     */
    public TaskEntry withAppendedMopMessages(List<? extends ChatMessage> additionalMessages) {
        if (additionalMessages.isEmpty() || mopMarkdown == null) {
            return this;
        }
        return withAppendedMopMessages(additionalMessages, description());
    }

    /**
     * Returns a copy of this TaskEntry with additional messages appended to the existing mop log, and sets the
     * resulting mop/llm log descriptions to the provided value.
     *
     * <p>If additionalMessages is empty, returns this unchanged.
     *
     * <p>If mopLog is null (summary-only entry), this is a no-op; callers should create a new TaskEntry
     * rather than mutating a summary-only entry.
     *
     * <p>Always preserves sequence, meta, and summary.
     */
    public TaskEntry withAppendedMopMessages(List<? extends ChatMessage> additionalMessages, String description) {
        if (additionalMessages.isEmpty() || mopMarkdown == null) {
            return this;
        }

        var copied = List.<ChatMessage>copyOf(additionalMessages);
        String mopAppended = Messages.formatForDisplay(copied);
        String llmAppended = Messages.format(copied);
        String newMopMarkdown = mopMarkdown + "\n\n" + mopAppended;
        String newLlmMarkdown = llmMarkdown == null ? null : (llmMarkdown + "\n\n" + llmAppended);
        return new TaskEntry(sequence, description, newMopMarkdown, newLlmMarkdown, summary, meta);
    }

    /**
     * Returns true if this TaskEntry holds an original message log.
     */
    public boolean hasLog() {
        return mopMarkdown != null;
    }

    /**
     * Returns true if this TaskEntry has a summary that the AI should use.
     */
    public boolean isCompressed() {
        return summary != null;
    }

    public static TaskEntry fromCompressed(
            int sequence, String compressedLog) { // IAppContextManager not needed for compressed
        return new TaskEntry(sequence, "(compressed)", (String) null, (String) null, compressedLog, null);
    }

    public static TaskEntry fromCompressedStable(String compressedLog) {
        return fromCompressed(compressedLog.hashCode(), compressedLog);
    }

    @Blocking
    public String description() {
        return (mopMarkdown == null && summary != null) ? castNonNull(summary) : description;
    }

    public @Nullable String llmMarkdown() {
        return llmMarkdown == null ? mopMarkdown : llmMarkdown;
    }

    public @Nullable ContextOutputFragments.TaskOutputFragment mopLog() {
        if (mopMarkdown == null) {
            return null;
        }
        if (cachedMopLog != null) {
            return cachedMopLog;
        }
        String id = requireNonNull(mopLogId);
        cachedMopLog = new ContextOutputFragments.TaskOutputFragment(id, description, mopMarkdown, true);
        return cachedMopLog;
    }

    public @Nullable ContextOutputFragments.TaskOutputFragment llmLog() {
        var md = llmMarkdown();
        if (md == null) {
            return null;
        }
        if (cachedLlmLog != null) {
            return cachedLlmLog;
        }
        String id = llmLogId != null ? llmLogId : computeTaskLogId(description, md);
        cachedLlmLog = new ContextOutputFragments.TaskOutputFragment(id, description, md, true);
        return cachedLlmLog;
    }

    /** Provides a string representation suitable for logging or context display. => what the AI sees */
    @Override
    public String toString() {
        if (isCompressed()) {
            return """
                    <task sequence=%s summarized=true>
                    %s
                    </task>
                    """
                    .formatted(sequence, castNonNull(summary).indent(2).stripTrailing());
        }

        var logText = castNonNull(mopMarkdown);
        return """
                <task sequence=%s>
                %s
                </task>
                """
                .formatted(sequence, logText.indent(2).stripTrailing());
    }

    public int sequence() {
        return sequence;
    }

    public @Nullable String mopMarkdown() {
        return mopMarkdown;
    }

    public @Nullable String summary() {
        return summary;
    }

    public TaskResult.@Nullable TaskMeta meta() {
        return meta;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (TaskEntry) obj;
        return this.sequence == that.sequence
                && Objects.equals(this.description, that.description)
                && Objects.equals(this.mopMarkdown, that.mopMarkdown)
                && Objects.equals(this.llmMarkdown, that.llmMarkdown)
                && Objects.equals(this.summary, that.summary)
                && Objects.equals(this.meta, that.meta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, description, mopMarkdown, llmMarkdown, summary, meta);
    }

    public Collection<ChatMessage> mopMessages() {
        // Keep for call sites that still need ChatMessage collections (app-side only).
        // Parse legacy <message type=X>...</message> framing back into per-segment messages so
        // display-side renderers receive properly typed bubbles instead of one big CustomMessage
        // whose text leaks framing tags to the user.
        if (mopMarkdown != null) {
            return LegacyFramingParser.parse(mopMarkdown).stream()
                    .map(seg -> Messages.create(seg.content(), seg.type()))
                    .toList();
        }
        return List.of(Messages.customSystem(castNonNull(summary)));
    }

    private static String computeTaskLogId(String description, String markdown) {
        return FragmentUtils.calculateContentHash(
                ContextFragment.FragmentType.TASK,
                description,
                markdown,
                ContextFragments.SYNTAX_STYLE_MARKDOWN,
                ContextOutputFragments.TaskOutputFragment.class.getName());
    }
}
