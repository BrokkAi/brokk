package ai.brokk.context;

import ai.brokk.TaskResult;

/**
 * Viewing policy for content visibility filtering across agents.
 * - taskType: which agent/task is viewing the content
 * - isLutz: whether the objective is LUTZ (affects Task List visibility for Search)
 */
public record ViewingPolicy(TaskResult.Type taskType, boolean isLutz) {
    public ViewingPolicy(TaskResult.Type taskType) {
        this(taskType, false);
    }
}
