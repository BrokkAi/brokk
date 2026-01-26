package ai.brokk.executor.jobs.modes;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.agents.LutzAgent;
import ai.brokk.tasks.TaskList;

import java.util.Objects;

/**
 * Handler for LUTZ mode (generate task list and execute tasks).
 */
public final class LutzModeHandler {
    private LutzModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var jobId = ctx.jobId();
        var spec = ctx.spec();
        var cm = ctx.cm();
        var console = ctx.io();
        var cancelledSupplier = ctx.cancelled();
        var plannerModel = ctx.plannerModel();
        var codeModel = ctx.codeModel();

        // Phase 1: Use SearchAgent to generate a task list from the initial task
        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var context = cm.liveContext();
            var searchAgent = new LutzAgent(
                    context,
                    spec.taskInput(),
                    Objects.requireNonNull(plannerModel, "plannerModel required for LUTZ jobs"),
                    SearchPrompts.Objective.TASKS_ONLY,
                    scope);
            var taskListResult = searchAgent.execute();
            scope.append(taskListResult);
        }
        // Task list is now in the live context and persisted by the scope

        // Phase 2: Check if task list was generated; if empty, mark job complete
        var generatedTasks = cm.getTaskList().tasks();
        if (generatedTasks.isEmpty()) {
            var msg = "SearchAgent generated no tasks for: " + spec.taskInput();
            if (console != null) {
                try {
                    console.showNotification(IConsoleIO.NotificationRole.INFO, msg);
                } catch (Throwable ignore) {
                    // Non-critical: event writing failed
                }
            }
            // No tasks generated; outer loop will handle completion/progress
            return;
        }

        // Phase 3: Execute each generated incomplete task sequentially
        var incompleteTasks = generatedTasks.stream()
                .filter(t -> !t.done())
                .toList();

        for (TaskList.TaskItem generatedTask : incompleteTasks) {
            if (cancelledSupplier.getAsBoolean()) {
                return; // Cancelled: exit early
            }

            try {
                cm.executeTask(
                        generatedTask,
                        plannerModel,
                        Objects.requireNonNull(codeModel, "code model unavailable for LUTZ jobs"));
            } catch (Exception e) {
                throw e;
            }
        }
    }
}
