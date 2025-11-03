package ai.brokk;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.context.ContextFragment;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single task interaction for the Task History, including the user request ("description") and the full
 * LLM message log. The log can be compressed to save context space while retaining the most relevant information.
 *
 * @param sequence A unique sequence number for ordering tasks.
 * @param log The uncompressed list of chat messages for this task. Null if compressed.
 * @param summary The compressed representation of the chat messages (summary). Null if uncompressed.
 */
public record TaskEntry(
        int sequence,
        @Nullable ContextFragment.TaskFragment log,
        @Nullable String summary,
        @Nullable TaskResult.TaskMeta meta) {

    /** Enforce that exactly one of log or summary is non-null */
    public TaskEntry {
        assert (log == null) != (summary == null) : "Exactly one of log or summary must be non-null";
        assert summary == null || !summary.isEmpty();
    }

    // Backward-compatible overload for existing call-sites (pre-meta)
    public TaskEntry(int sequence, @Nullable ContextFragment.TaskFragment log, @Nullable String summary) {
        this(sequence, log, summary, null);
    }

    /**
     * Creates a TaskHistory instance from a list of ChatMessages representing a session. Creates a TaskEntry instance
     * from a list of ChatMessages representing a full session interaction. The first message *must* be a UserMessage,
     * its content is stored as the `description`. The remaining messages (AI responses, tool calls/results) are stored
     * in the `log`. The TaskEntry starts uncompressed.
     */
    // IContextManager is not needed here, TaskFragment itself will get it via SessionResult.output()
    // which is created with a contextManager in the agents
    public static TaskEntry fromSession(int sequence, TaskResult result) {
        return new TaskEntry(sequence, result.output(), null, result.meta());
    }

    public static TaskEntry fromCompressed(
            int sequence, String compressedLog) { // IContextManager not needed for compressed
        return new TaskEntry(sequence, null, compressedLog, null);
    }

    /**
     * Returns true if this TaskEntry holds a compressed summary instead of the full message log.
     *
     * @return true if compressed, false otherwise.
     */
    public boolean isCompressed() {
        return summary != null;
    }

    /** Provides a string representation suitable for logging or context display. */
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

        var logText = formatMessages(castNonNull(log).messages());
        return """
          <task sequence=%s>
          %s
          </task>
          """
                .formatted(sequence, logText.indent(2).stripTrailing());
    }

    public static String formatMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> {
                    var text = Messages.getRepr(message);
                    return (CharSequence)
                            """
                      <message type=%s>
                      %s
                      </message>
                      """
                                    .formatted(
                                            message.type().name().toLowerCase(Locale.ROOT),
                                            text.indent(2).stripTrailing());
                })
                .collect(Collectors.joining("\n"));
    }
}
