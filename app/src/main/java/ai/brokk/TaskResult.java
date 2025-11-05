package ai.brokk;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the outcome of an agent session, containing all necessary information to update the context history.
 *
 * Note that the Context must NOT be frozen.
 */
public record TaskResult(
        String actionDescription,
        ContextFragment.TaskFragment output,
        Context context,
        StopDetails stopDetails,
        @Nullable TaskMeta meta) {

    public TaskResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Context resultingContext,
            StopDetails stopDetails,
            TaskMeta meta) {
        this(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                resultingContext,
                stopDetails,
                meta);
    }

    public static TaskResult humanResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Context resultingContext,
            StopReason simpleReason) {
        return new TaskResult(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                resultingContext,
                new StopDetails(simpleReason),
                null);
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
        /** Lint errors occurred and were not improving after retries. */
        LINT_ERROR,
        /** The LLM attempted to edit a read-only file. */
        READ_ONLY_EDIT,
        /** Unable to write new file contents */
        IO_ERROR,
        /** the LLM determined that it was not possible to fulfil the request */
        LLM_ABORTED,
        /** an error occurred while executing a tool */
        TOOL_ERROR,
        /** the LLM exceeded the context size limit */
        LLM_CONTEXT_SIZE
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
                return new TaskResult.StopDetails(StopReason.LLM_CONTEXT_SIZE, "Context limit exceeded");
            }
            var errorMessage = response.error().getMessage();
            return new TaskResult.StopDetails(
                    TaskResult.StopReason.LLM_ERROR, errorMessage != null ? errorMessage : "Unknown error");
        }
    }

    public record TaskMeta(Type type, Service.ModelConfig primaryModel) {}

    public enum Type {
        NONE,
        ARCHITECT,
        CODE,
        ASK,
        SEARCH,
        CONTEXT,
        MERGE,
        BLITZFORGE;

        public String displayName() {
            if (this == SEARCH) {
                return "Lutz Mode";
            }
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
}
