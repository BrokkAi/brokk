package ai.brokk.executor.jobs.modes;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.JobModelResolver;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.agents.LutzAgent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Handler for ASK mode: read-only question answering with optional pre-scan.
 */
public final class AskModeHandler {
    private AskModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var spec = ctx.spec();
        var cm = ctx.cm();
        var store = ctx.store();
        var plannerModel = ctx.plannerModel();

        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
            var context = cm.liveContext();

            // Optional pre-scan: resolve scan model similarly to SEARCH mode.
            if (spec.preScan()) {
                var resolver = new JobModelResolver(cm);

                // Emit deterministic start NOTIFICATION so headless clients/tests can observe the pre-scan start.
                try {
                    store.appendEvent(
                            ctx.jobId(),
                            ai.brokk.executor.jobs.JobEvent.of(
                                    "NOTIFICATION",
                                    "Brokk Context Engine: analyzing repository context..."));
                } catch (IOException ioe) {
                    // best-effort
                }

                ai.brokk.agents.SearchAgent.ScanConfig scanConfig = null;
                try {
                    var scanModelToUse = ctx.scanModel();
                    if (scanModelToUse == null) {
                        String rawScanModel = spec.scanModel();
                        String trimmedScanModel = rawScanModel == null ? "" : rawScanModel.trim();
                        scanModelToUse = !trimmedScanModel.isEmpty()
                                ? resolver.resolveModelOrThrow(trimmedScanModel, spec.reasoningLevel(), spec.temperature())
                                : resolver.defaultScanModel(spec);
                    }
                    scanConfig = ai.brokk.agents.SearchAgent.ScanConfig.withModel(scanModelToUse);
                } catch (IllegalArgumentException iae) {
                    // resolveModelOrThrow may throw; log and continue without failing job.
                }

                if (scanConfig != null) {
                    try {
                        var searchAgent = new LutzAgent(
                                context,
                                spec.taskInput(),
                                Objects.requireNonNull(plannerModel, "plannerModel required for ASK jobs"),
                                SearchPrompts.Objective.ANSWER_ONLY,
                                scope,
                                cm.getIo(),
                                scanConfig);
                        try {
                            context = searchAgent.scanContext();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (Exception ex) {
                        // Any other exception during pre-scan should not fail the job.
                    }
                }

                // Emit deterministic completion NOTIFICATION
                try {
                    store.appendEvent(
                            ctx.jobId(),
                            ai.brokk.executor.jobs.JobEvent.of(
                                    "NOTIFICATION",
                                    "Brokk Context Engine: complete — contextual insights added to Workspace."));
                } catch (IOException ioe) {
                    // best-effort
                }
            }

            try {
                // Use helper that builds a workspace-only prompt and calls the planner model.
                TaskResult askResult = new AskModeHelper(cm).askUsingPlannerModel(
                        context,
                        Objects.requireNonNull(plannerModel),
                        spec.taskInput());
                scope.append(askResult);
            } catch (Throwable t) {
                // Append a non-fatal TaskResult indicating the failure so the task has a record, but do not rethrow.
                var stopDetails = new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR,
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                List<ChatMessage> ui = List.of(
                        new UserMessage(spec.taskInput()),
                        new SystemMessage("ASK direct-answer failed: " + stopDetails.explanation()));
                var failureResult = new TaskResult(
                        cm,
                        "ASK: " + spec.taskInput() + " [LLM_ERROR]",
                        ui,
                        context,
                        stopDetails,
                        null);
                try {
                    scope.append(failureResult);
                } catch (Throwable e2) {
                    // best-effort
                }
            }
        }
    }

    // Small helper that replicates the former JobRunner.askUsingPlannerModel behavior but
    // is local to the ASK handler to avoid pulling more JobRunner internals.
    private static final class AskModeHelper {
        private final ContextManager cm;

        AskModeHelper(ContextManager cm) {
            this.cm = cm;
        }

        TaskResult askUsingPlannerModel(Context ctx, dev.langchain4j.model.chat.StreamingChatModel model, String question) {
            var svc = cm.getService();
            var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, ai.brokk.Service.ModelConfig.from(model, svc));

            List<dev.langchain4j.data.message.ChatMessage> messages;
            messages = SearchPrompts.instance.buildAskPrompt(ctx, question, meta);

            var llm = cm.getLlm(new Llm.Options(model, "Answer: " + question).withEcho());
            llm.setOutput(cm.getIo());

            TaskResult.StopDetails stop = null;
            Llm.StreamingResult response = null;
            try {
                response = llm.sendRequest(messages);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
            }

            if (response != null) {
                stop = TaskResult.StopDetails.fromResponse(response);
            }

            Objects.requireNonNull(stop);
            return new TaskResult(
                    cm,
                    "Ask: " + question,
                    List.copyOf(cm.getIo().getLlmRawMessages()),
                    ctx,
                    stop,
                    meta);
        }
    }
}
