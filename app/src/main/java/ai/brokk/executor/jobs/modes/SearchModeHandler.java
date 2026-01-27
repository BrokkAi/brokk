package ai.brokk.executor.jobs.modes;

import ai.brokk.agents.LutzAgent;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.prompts.SearchPrompts;
import java.util.Objects;

/**
 * Handler for SEARCH mode: read-only repository search using a scan model.
 */
public final class SearchModeHandler {
    private SearchModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var spec = ctx.spec();
        var cm = ctx.cm();

        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var context = cm.liveContext();

            // Determine scan model: prefer explicit spec.scanModel() if provided,
            // otherwise use project default. The JobExecutionContext may already
            // contain a resolved scan model; prefer that.
            var scanModelToUse = ctx.scanModel();
            if (scanModelToUse == null) {
                var resolver = new ai.brokk.executor.jobs.JobModelResolver(cm);
                String rawScanModel = spec.scanModel();
                String trimmedScanModel = rawScanModel == null ? "" : rawScanModel.trim();
                scanModelToUse = !trimmedScanModel.isEmpty()
                        ? resolver.resolveModelOrThrow(trimmedScanModel, spec.reasoningLevel(), spec.temperature())
                        : resolver.defaultScanModel(spec);
            }

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
