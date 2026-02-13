package ai.brokk;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.context.ContextFragments;
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
    private final ContextFragments.@Nullable TaskFragment mopLog;
    private final ContextFragments.@Nullable TaskFragment llmLog;
    private final @Nullable String summary;
    private final TaskResult.@Nullable TaskMeta meta;

    /** Enforce that at least one of log or summary is non-null */
    public TaskEntry(
            int sequence,
            @Nullable ContextFragments.TaskFragment mopLog,
            @Nullable ContextFragments.TaskFragment llmLog,
            @Nullable String summary,
            @Nullable TaskResult.TaskMeta meta) {
        assert (mopLog != null) || (summary != null) : "At least one of mopLog or summary must be non-null";
        assert summary == null || !summary.isEmpty() : "summary must not be empty when present";
        this.sequence = sequence;
        this.mopLog = mopLog;
        this.llmLog = llmLog;
        this.summary = summary;
        this.meta = meta;
    }

    // Some call sites "forge" a TaskEntry where no task existed. This is a smell but for now we allow it.
    public TaskEntry(int sequence, @Nullable ContextFragments.TaskFragment log, @Nullable String summary) {
        this(sequence, log, null, summary, null);
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
        return new TaskEntry(sequence, mopLog, null, newSummary, meta);
    }

    /**
     * Returns true if this TaskEntry holds an original message log.
     */
    public boolean hasLog() {
        return mopLog != null;
    }

    /**
     * Returns true if this TaskEntry has a summary that the AI should use.
     */
    public boolean isCompressed() {
        return summary != null;
    }

    public static TaskEntry fromCompressed(
            int sequence, String compressedLog) { // IContextManager not needed for compressed
        return new TaskEntry(sequence, null, null, compressedLog, null);
    }

    @Blocking
    public String description() {
        return summary == null ? requireNonNull(mopLog).shortDescription().join() : summary;
    }

    public @Nullable ContextFragments.TaskFragment llmLog() {
        return llmLog == null ? mopLog : llmLog;
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

        var logText = Messages.format(castNonNull(mopLog).messages());
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

    public ContextFragments.@Nullable TaskFragment mopLog() {
        return mopLog;
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
                && Objects.equals(this.mopLog, that.mopLog)
                && Objects.equals(this.llmLog, that.llmLog)
                && Objects.equals(this.summary, that.summary)
                && Objects.equals(this.meta, that.meta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, mopLog, llmLog, summary, meta);
    }

    public Collection<ChatMessage> mopMessages() {
        return mopLog == null ? List.of(Messages.customSystem(castNonNull(summary))) : mopLog.messages();
    }
}
