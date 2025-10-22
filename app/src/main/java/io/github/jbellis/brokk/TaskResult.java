package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the outcome of a CodeAgent session, containing all necessary information to update the context history.
 */
public record TaskResult(
        String actionDescription,
        ContextFragment.TaskFragment output,
        Set<ProjectFile> changedFiles,
        StopDetails stopDetails,
        @Nullable UUID taskId,
        @Nullable UUID beforeContextId,
        @Nullable UUID afterContextId,
        long createdAtEpochMillis,
        @Nullable String summaryText) {

    /**
     * Preserves binary compatibility for existing call sites of the old 4-argument canonical constructor.
     * This constructor delegates to the new canonical constructor with default values.
     */
    public TaskResult(
            String actionDescription,
            ContextFragment.TaskFragment output,
            Set<ProjectFile> changedFiles,
            StopDetails stopDetails) {
        this(
                actionDescription,
                output,
                changedFiles,
                stopDetails,
                null, // taskId
                null, // beforeContextId
                null, // afterContextId
                System.currentTimeMillis(),
                null // summaryText
                );
    }

    public TaskResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Set<ProjectFile> changedFiles,
            StopDetails stopDetails) {
        this(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                changedFiles,
                stopDetails);
    }

    public TaskResult(
            IContextManager contextManager,
            String actionDescription,
            List<ChatMessage> uiMessages,
            Set<ProjectFile> changedFiles,
            StopReason simpleReason) {
        this(
                actionDescription,
                new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription),
                changedFiles,
                new StopDetails(simpleReason));
    }

    /** Creates a new TaskResult by replacing the messages in an existing one. */
    public TaskResult(TaskResult base, List<ChatMessage> newMessages, IContextManager contextManager) {
        this(
                base.actionDescription(),
                new ContextFragment.TaskFragment(contextManager, newMessages, base.actionDescription()),
                base.changedFiles(),
                base.stopDetails(),
                base.taskId(),
                base.beforeContextId(),
                base.afterContextId(),
                base.createdAtEpochMillis(),
                base.summaryText());
    }

    /** Enum representing the reason a CodeAgent session concluded. */
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
        /** the LLM called answer() but did not provide a result */
        SEARCH_INVALID_ANSWER,
        /** the LLM determined that it was not possible to fulfil the request */
        LLM_ABORTED,
        /** an error occurred while executing a tool */
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
