package ai.brokk;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import java.util.List;

/**
 * Represents the outcome of an agent session, containing all necessary information to update the context history.
 *
 * Note that the Context must NOT be frozen.
 */
public record TaskResult(
        String actionDescription, ContextFragment.TaskFragment output, Context context, StopDetails stopDetails) {

    public TaskResult {
        assert !context.containsFrozenFragments();
    }

    public TaskResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Context resultingContext,
            StopDetails stopDetails) {
        this(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                resultingContext,
                stopDetails);
    }

    public TaskResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Context resultingContext,
            StopReason simpleReason) {
        this(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                resultingContext,
                new StopDetails(simpleReason));
    }

    /** Creates a new TaskResult by replacing the messages in an existing one while preserving the resulting context. */
    public TaskResult(TaskResult base, List<ChatMessage> newMessages, IContextManager contextManager) {
        this(
                base.actionDescription(),
                new ContextFragment.TaskFragment(contextManager, newMessages, base.actionDescription()),
                base.context(),
                base.stopDetails());
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
            return new TaskResult.StopDetails(
                    TaskResult.StopReason.LLM_ERROR, response.error().getMessage());
        }
    }
}
