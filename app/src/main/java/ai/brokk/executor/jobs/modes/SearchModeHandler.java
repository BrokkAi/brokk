package ai.brokk.executor.jobs.modes;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.agents.LutzAgent;

import java.util.Objects;

/**
 * Handler for SEARCH mode: read-only repository search using a scan model.
 */
public final class SearchModeHandler {
    private SearchModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var spec = ctx.spec();
        var cm = ctx.cm();
        var console = ctx.io();

        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var context = cm.liveContext();

            // Determine scan model: prefer explicit spec.scanModel() if provided,
            // otherwise use project default. The JobExecutionContext may already
            // contain a resolved scan model; prefer that.
            final var scanModelToUse = ctx.scanModel();

            var scanConfig = ai.brokk.agents.SearchAgent.ScanConfig.withModel(scanModelToUse);
            var searchAgent = new LutzAgent(
                    context,
                    spec.taskInput(),
                    Objects.requireNonNull(scanModelToUse, "scan model unavailable for SEARCH jobs"),
                    SearchPrompts.Objective.ANSWER_ONLY,
                    scope,
                    cm.getIo(),
                    scanConfig);
            var result = searchAgent.execute();
            scope.append(result);
        }
    }
}
