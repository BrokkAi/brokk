package ai.brokk;

import static java.util.Objects.requireNonNull;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.exception.OverthinkingException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the outcome of an agent session, containing all necessary information to update the context history.
 *
 * Note that the Context must NOT be frozen.
 */
public record TaskResult(Context context, StopDetails stopDetails) {

    public static TaskResult humanResult(String actionDescription, Context resultingContext, StopReason simpleReason) {
        return new TaskResult(resultingContext, new StopDetails(simpleReason));
    }

    public TaskResult withContext(Context ctx) {
        return new TaskResult(ctx, stopDetails);
    }

    public TaskResult withHistory(List<TaskEntry> taskHistory) {
        return new TaskResult(context.withHistory(taskHistory), stopDetails);
    }

    public @Nullable TaskMeta meta() {
        if (context.getTaskHistory().isEmpty()) {
            return null;
        }
        return context.getTaskHistory().getFirst().meta();
    }

    /** Enum representing the reason a session concluded. */
    public enum StopReason {
        /** The agent successfully completed the goal. */
        SUCCESS,
        /** The user interrupted the session. */
        INTERRUPTED,
        /** The LLM returned an error after retries. */
        LLM_ERROR,
        /** The LLM response could not be parsed after retries. */
        PARSE_ERROR,
        /** Applying edits failed after retries. */
        APPLY_ERROR,
        /** Build errors occurred and were not improving after retries. */
        BUILD_ERROR,
        /** The LLM attempted to edit a read-only file. */
        READ_ONLY_EDIT,
        /** Unable to write new file contents */
        IO_ERROR,
        /** the LLM determined that it was not possible to fulfil the request */
        LLM_ABORTED,
        /** an error occurred while executing a tool */
        TOOL_ERROR,
        /** the LLM exceeded the context size limit */
        LLM_CONTEXT_SIZE,
        /** the LLM exceeded the output size limit */
        LLM_OVERTHINKING,
        /** hit the mercy rule ceiling */
        TURN_LIMIT;
    }

    public record StopDetails(StopReason reason, String explanation) {
        public StopDetails(StopReason reason) {
            this(reason, "");
        }

        @Override
        public String toString() {
            if (explanation.isEmpty()) {
                return reason.toString();
            }
            return "%s:\n%s".formatted(reason.toString(), explanation);
        }

        public static StopDetails fromResponse(Llm.StreamingResult response) {
            if (response.error() == null) {
                return new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            }
            if (response.error() instanceof ContextTooLargeException) {
                return new TaskResult.StopDetails(
                        StopReason.LLM_CONTEXT_SIZE,
                        """
                        Context limit exceeded.

                        The LLM request was too large for the model's maximum input size.

                        Fixes:
                          - Remove or drop unneeded Workspace files/fragments
                          - Prefer summaries over full files where possible
                          - Narrow the goal/query to a smaller scope
                        """
                                .stripIndent()
                                .stripTrailing());
            }
            if (response.error() instanceof OverthinkingException) {
                return new TaskResult.StopDetails(
                        StopReason.LLM_OVERTHINKING,
                        """
                        The LLM exhausted its output tokens before generating a response.

                        This is a bug in the model or its configuration, there's nothing we can do from the client side here.
                        """
                                .stripIndent()
                                .stripTrailing());
            }
            var errorMessage = response.error().getMessage();
            return new TaskResult.StopDetails(
                    TaskResult.StopReason.LLM_ERROR,
                    """
                    LLM request failed.

                    Cause:
                      %s
                    """
                            .stripIndent()
                            .formatted(
                                    errorMessage != null && !errorMessage.isBlank() ? errorMessage : "(no message)"));
        }
    }

    public record TaskMeta(Type type, AbstractService.ModelConfig primaryModel) {}

    public enum Type {
        NONE,
        ARCHITECT,
        CODE,
        ASK,
        SEARCH,
        SCAN, // ContextAgent
        MERGE,
        BLITZFORGE,
        REVIEW,
        JANITOR,
        SUMMARIZE, // also "describe"
        CLASSIFY,
        EDIT;

        public String displayName() {
            var lower = name().toLowerCase(Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }

        public static Optional<Type> safeParse(@Nullable String value) {
            if (value == null) {
                return Optional.empty();
            }
            var s = value.trim();
            if (s.isEmpty()) {
                return Optional.empty();
            }

            for (var t : values()) {
                if (t.name().equalsIgnoreCase(s)) {
                    return Optional.of(t);
                }
            }
            for (var t : values()) {
                if (t.displayName().equalsIgnoreCase(s)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    public ContextFragments.TaskFragment output() {
        return requireNonNull(context.getTaskHistory().getLast().mopLog());
    }
}
