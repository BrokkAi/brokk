package ai.brokk.context;

import ai.brokk.TaskResult;

public record ViewingPolicy(TaskResult.Type taskType, boolean useTaskList) {
    public ViewingPolicy(TaskResult.Type taskType) {
        this(taskType, false);
    }
}
