package ai.brokk.executor.jobs.modes;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.agents.CodeAgent;
import ai.brokk.tasks.TaskList;

import java.util.Objects;

/**
 * Handler for CODE mode: runs CodeAgent in a single task scope.
 */
public final class CodeModeHandler {
    private CodeModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var spec = ctx.spec();
        var cm = ctx.cm();
        var codeModel = ctx.codeModel();

        var agent = new CodeAgent(
                cm,
                Objects.requireNonNull(codeModel, "code model unavailable for CODE jobs"));
        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var result = agent.execute(spec.taskInput(), java.util.Set.of());
            scope.append(result);
        }
    }
}
