package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the outcome of a CodeAgent session, containing all necessary information
 * to update the context history.
 */
public record TaskResult(String actionDescription,
                         ContextFragment.TaskFragment output,
                         Set<ProjectFile> changedFiles,
                         StopDetails stopDetails)
{
    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopDetails stopDetails)
    {
        this(actionDescription,
             new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
             changedFiles,
             stopDetails);
    }

    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopReason simpleReason)
    {
        this(actionDescription,
             new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
             changedFiles,
             new StopDetails(simpleReason));
    }

    /**
     * Enum representing the reason a CodeAgent session concluded.
     */
    public enum StopReason {
        /**
         * The agent successfully completed the goal.
         */
        SUCCESS,
        /**
         * The user interrupted the session.
         */
        INTERRUPTED,
        /**
         * The LLM returned an error after retries.
         */
        LLM_ERROR,
        /**
         * The LLM returned an empty or blank response after retries.
         */
        EMPTY_RESPONSE,
        /**
         * The LLM response could not be parsed after retries.
         */
        PARSE_ERROR,
        /**
         * Applying edits failed after retries.
         */
        APPLY_ERROR,
        /**
         * Build errors occurred and were not improving after retries.
         */
        BUILD_ERROR,
        /**
         * The LLM attempted to edit a read-only file.
         */
        READ_ONLY_EDIT,
        /**
         * Unable to write new file contents
         */
        IO_ERROR,
        /**
         * the LLM called answer() but did not provide a result
         */
        SEARCH_INVALID_ANSWER,
        /**
         * the LLM determined that it was not possible to fulfil the request
         */
        LLM_ABORTED,
        /**
         * an error occurred while executing a tool
         */
        TOOL_ERROR
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
    }
}
