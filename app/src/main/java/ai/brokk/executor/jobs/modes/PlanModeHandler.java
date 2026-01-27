package ai.brokk.executor.jobs.modes;

import ai.brokk.agents.LutzAgent;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.JobModelResolver;
import ai.brokk.prompts.SearchPrompts;
import java.util.Objects;

/**
 * Handler for PLAN mode: planning-only (generate task list and persist it, do not execute).
 */
public final class PlanModeHandler {
    private PlanModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var spec = ctx.spec();
        var cm = ctx.cm();
        var plannerModel = ctx.plannerModel();

        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var context = cm.liveContext();

            // Resolve scan model for PLAN mode: prefer explicit spec.scanModel() if provided;
            // otherwise use project default via JobModelResolver.
            var scanModelToUse = ctx.scanModel();
            if (scanModelToUse == null) {
                var resolver = new JobModelResolver(cm);
                String rawScanModel = spec.scanModel();
                String trimmedScanModel = rawScanModel == null ? null : rawScanModel.trim();
                scanModelToUse = (trimmedScanModel != null && !trimmedScanModel.isEmpty())
                        ? resolver.resolveModelOrThrow(trimmedScanModel, spec.reasoningLevel(), spec.temperature())
                        : resolver.defaultScanModel(spec);
            }

            // Ensure we have a non-null scan model before constructing the ScanConfig.
            var nonNullScanModel = Objects.requireNonNull(scanModelToUse, "scan model unavailable for PLAN jobs");
            var scanConfig = ai.brokk.agents.SearchAgent.ScanConfig.withModel(nonNullScanModel);

            var searchAgent = new LutzAgent(
                    context,
                    spec.taskInput(),
                    Objects.requireNonNull(plannerModel, "plannerModel required for PLAN jobs"),
                    SearchPrompts.Objective.TASKS_ONLY,
                    scope,
                    cm.getIo(),
                    scanConfig);
            var taskListResult = searchAgent.execute();
            scope.append(taskListResult);
        }
    }
}
