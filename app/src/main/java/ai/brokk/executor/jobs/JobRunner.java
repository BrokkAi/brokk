package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.IssueRewriterAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.agents.ReviewScope;
import ai.brokk.context.Context;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.git.GitRepo;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.issues.IssueHeader;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.ReviewParser;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Executes long-running Brokk jobs using ContextManager, producing durable events
 * via HeadlessHttpConsole and updating JobStore status.
 *
 * <p>Each JobRunner instance processes one job at a time on a single-threaded executor.
 * Callers should instantiate one JobRunner per session or share a single instance across
 * sessions, ensuring only one job executes at a time (enforced by activeJobId guard).
 */
public final class JobRunner {
    private static final Logger logger = LogManager.getLogger(JobRunner.class);

    static final String COMMAND_RESULT_EVENT_TYPE = "COMMAND_RESULT";

    static final class CommandResultEvent {
        private static final int MAX_OUTPUT_CHARS = 25_000;

        private final String stage;
        private final String command;
        private final @Nullable Integer attempt;
        private final boolean skipped;
        private final @Nullable String skipReason;
        private final boolean success;
        private final String output;
        private final @Nullable String exception;

        CommandResultEvent(
                String stage,
                String command,
                @Nullable Integer attempt,
                boolean skipped,
                @Nullable String skipReason,
                boolean success,
                String output,
                @Nullable String exception) {
            this.stage = stage;
            this.command = command;
            this.attempt = attempt;
            this.skipped = skipped;
            this.skipReason = skipReason;
            this.success = success;
            this.output = output;
            this.exception = exception;
        }

        Map<String, Object> toJson() {
            var data = new LinkedHashMap<String, Object>();
            data.put("stage", stage);
            data.put("command", command);
            if (attempt != null) {
                data.put("attempt", attempt.intValue());
            }
            data.put("skipped", skipped);
            if (skipReason != null && !skipReason.isBlank()) {
                data.put("skipReason", skipReason);
            }
            data.put("success", success);
            if (output.length() > MAX_OUTPUT_CHARS) {
                data.put("output", output.substring(0, MAX_OUTPUT_CHARS));
                data.put("outputTruncated", true);
            } else {
                data.put("output", output);
            }
            if (exception != null && !exception.isBlank()) {
                data.put("exception", exception);
            }
            return data;
        }
    }

    public static CommandResultEvent commandResult(
            String stage,
            @Nullable String command,
            @Nullable Integer attempt,
            boolean skipped,
            @Nullable String skipReason,
            boolean success,
            @Nullable String output,
            @Nullable Throwable exception) {
        String safeCommand = command == null ? "" : command;
        String safeOutput = output == null ? "" : output;

        @Nullable String exceptionText = null;
        if (exception != null) {
            var msg = exception.getMessage();
            exceptionText = (msg == null || msg.isBlank())
                    ? exception.getClass().getSimpleName()
                    : exception.getClass().getSimpleName() + ": " + msg;
        }

        return new CommandResultEvent(
                stage, safeCommand, attempt, skipped, skipReason, success, safeOutput, exceptionText);
    }

    public static void emitCommandResult(
            String jobId, JobStore store, IConsoleIO io, CommandResultEvent event, String summaryMessage) {
        try {
            store.appendEvent(jobId, JobEvent.of(COMMAND_RESULT_EVENT_TYPE, event.toJson()));
        } catch (IOException e) {
            logger.warn(
                    "Failed to append {} event for job {}: {}", COMMAND_RESULT_EVENT_TYPE, jobId, e.getMessage(), e);
        }

        if (!event.success) {
            try {
                io.showNotification(IConsoleIO.NotificationRole.INFO, summaryMessage);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }

    public static String runAndEmitCommand(
            String jobId,
            JobStore store,
            IConsoleIO io,
            String stage,
            String command,
            int attempt,
            Function<String, String> commandRunner) {
        try {
            String output = Objects.requireNonNullElse(commandRunner.apply(command), "");
            boolean success = output.isBlank();
            emitCommandResult(
                    jobId,
                    store,
                    io,
                    commandResult(
                            stage,
                            command,
                            attempt,
                            /* skipped= */ false,
                            /* skipReason= */ null,
                            success,
                            output,
                            null),
                    stage + ": " + (success ? "PASS" : "FAIL"));
            return output;
        } catch (RuntimeException re) {
            emitCommandResult(
                    jobId,
                    store,
                    io,
                    commandResult(stage, command, attempt, /* skipped= */ false, /* skipReason= */ null, false, "", re),
                    stage + ": ERROR");
            throw re;
        }
    }

    public static void emitSkippedCommand(
            String jobId,
            JobStore store,
            IConsoleIO io,
            String stage,
            @Nullable String command,
            int attempt,
            String skipReason) {
        emitCommandResult(
                jobId,
                store,
                io,
                commandResult(stage, command, attempt, /* skipped= */ true, skipReason, /* success= */ true, "", null),
                stage + ": SKIP");
    }

    static final class IssueCancelledException extends IssueExecutionException {
        IssueCancelledException(String message) {
            super(message);
        }
    }

    static final PrReviewService.Severity DEFAULT_REVIEW_SEVERITY_THRESHOLD = PrReviewService.Severity.HIGH;
    static final int DEFAULT_REVIEW_MAX_INLINE_COMMENTS = 3;

    private final ContextManager cm;
    private final JobStore store;

    private final ExecutorService runner;
    private volatile @Nullable HeadlessHttpConsole console;
    private volatile @Nullable String activeJobId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    enum Mode {
        ARCHITECT,
        CODE,
        ASK,
        SEARCH,
        REVIEW,
        GUIDED_REVIEW,
        LUTZ,
        PLAN,
        ISSUE,
        ISSUE_DIAGNOSE,
        ISSUE_WRITER
    }

    static SearchPrompts.Objective objectiveForMode(Mode mode) {
        return switch (mode) {
            case ASK, SEARCH, REVIEW, GUIDED_REVIEW -> SearchPrompts.Objective.ANSWER_ONLY;
            case LUTZ -> SearchPrompts.Objective.LUTZ;
            case PLAN, ARCHITECT, CODE, ISSUE, ISSUE_DIAGNOSE, ISSUE_WRITER -> SearchPrompts.Objective.TASKS_ONLY;
        };
    }

    static SearchPrompts.Objective objectiveForLutzSearchPhase() {
        return objectiveForMode(Mode.LUTZ);
    }

    public static Mode parseMode(JobSpec spec) {
        try {
            var tags = spec.tags();
            var raw = tags.getOrDefault("mode", "").trim();
            if (raw.isEmpty()) return Mode.ARCHITECT;
            return Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return Mode.ARCHITECT;
        }
    }

    /**
     * Create a new JobRunner.
     *
     * @param cm The ContextManager for task execution
     * @param store The JobStore for persistence
     */
    public JobRunner(ContextManager cm, JobStore store) {
        this.cm = cm;
        this.store = store;
        this.runner = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "JobRunner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Shut down the runner executor and await termination.
     */
    public void shutdown() {
        logger.info("Shutting down JobRunner");
        runner.shutdownNow();
        try {
            if (!runner.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("JobRunner executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for JobRunner shutdown");
        }
    }

    /**
     * Execute a job asynchronously.
     *
     * @param jobId The unique job identifier
     * @param spec The job specification
     * @return A CompletableFuture that completes when the job finishes or fails
     * @throws IllegalStateException if another job is already running and has a different jobId
     */
    public synchronized CompletableFuture<Void> runAsync(String jobId, JobSpec spec) {
        // Guard: prevent concurrent execution of different jobs
        if (activeJobId != null && !activeJobId.equals(jobId)) {
            var fut = new CompletableFuture<Void>();
            fut.completeExceptionally(new IllegalStateException("Another job is already running: " + activeJobId));
            return fut;
        }

        activeJobId = jobId;
        cancelled.set(false);
        console = new HeadlessHttpConsole(store, jobId, null);
        final var previousIo = cm.getIo();
        final var previousAutoCommit = cm.isAutoCommit();
        cm.setIo(console);
        logger.info("Job {} attaching streaming console", jobId);

        // Transition status to RUNNING
        try {
            JobStatus status = store.loadStatus(jobId);
            if (status == null) {
                status = JobStatus.queued(jobId);
            }
            status = status.withState(JobStatus.State.RUNNING.name());
            store.updateStatus(jobId, status);
            logger.info("Job {} transitioned to RUNNING", jobId);
        } catch (Exception e) {
            logger.warn("Failed to update job status to RUNNING for {}", jobId, e);
        }

        // Emit start event
        try {
            console.showNotification(IConsoleIO.NotificationRole.INFO, "Job started: " + jobId);
        } catch (Throwable ignore) {
            // Non-critical: event writing failed, continue
        }

        var future = new CompletableFuture<Void>();

        runner.execute(() -> {
            Throwable[] futureFailure = {null};
            try {
                // Determine execution mode (default ARCHITECT)
                Mode mode = parseMode(spec);

                var completed = new AtomicInteger(0);

                var rawCodeModelName = spec.codeModel() != null
                        ? spec.codeModel()
                        : spec.tags().get("code_model");
                var trimmedCodeModelName =
                        (rawCodeModelName != null && !rawCodeModelName.isBlank()) ? rawCodeModelName.trim() : null;
                var hasCodeModelOverride = trimmedCodeModelName != null;

                final StreamingChatModel architectPlannerModel =
                        (mode == Mode.ARCHITECT || mode == Mode.LUTZ || mode == Mode.PLAN || mode == Mode.ISSUE)
                                ? resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                                : null;
                final StreamingChatModel architectCodeModel =
                        (mode == Mode.ARCHITECT || mode == Mode.LUTZ || mode == Mode.ISSUE)
                                ? (trimmedCodeModelName != null
                                        ? resolveModelOrThrow(
                                                trimmedCodeModelName, spec.reasoningLevelCode(), spec.temperatureCode())
                                        : defaultCodeModel(spec))
                                : null;
                final StreamingChatModel reviewPlannerModel = mode == Mode.REVIEW
                        ? resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                        : null;
                // Resolve scan model for REVIEW mode (prefer explicit spec.scanModel() if provided; otherwise project
                // default)
                final StreamingChatModel reviewScanModel = mode == Mode.REVIEW
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? resolveModelOrThrow(
                                        spec.scanModel().trim(), spec.reasoningLevel(), spec.temperature())
                                : defaultScanModel(spec))
                        : null;
                final StreamingChatModel askPlannerModel = mode == Mode.ASK || mode == Mode.ISSUE
                        ? resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                        : null;
                final StreamingChatModel codeModeModel = mode == Mode.CODE
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(
                                        requireNonNull(trimmedCodeModelName),
                                        spec.reasoningLevelCode(),
                                        spec.temperatureCode())
                                : defaultCodeModel(spec))
                        : null;

                var service = cm.getService();

                // Resolve a scan model for SEARCH mode if needed (prefer explicit spec.scanModel() if provided;
                // otherwise project default)
                final StreamingChatModel searchPlannerModel = mode == Mode.SEARCH
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? resolveModelOrThrow(
                                        spec.scanModel().trim(), spec.reasoningLevel(), spec.temperature())
                                : defaultScanModel(spec))
                        : null;

                String plannerModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ, PLAN, ISSUE -> service.nameOf(requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(requireNonNull(askPlannerModel));
                            case SEARCH -> service.nameOf(requireNonNull(searchPlannerModel));
                            case CODE -> {
                                var plannerName = spec.plannerModel();
                                yield plannerName.isBlank() ? "(unused)" : plannerName.trim();
                            }
                            case REVIEW -> service.nameOf(requireNonNull(reviewPlannerModel));
                            case GUIDED_REVIEW -> spec.plannerModel().trim();
                            case ISSUE_DIAGNOSE, ISSUE_WRITER -> "(unused)";
                        };
                String codeModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> service.nameOf(requireNonNull(architectCodeModel));
                            case ASK -> "(default, ignored for ASK)";
                            case SEARCH -> "(default, ignored for SEARCH)";
                            case PLAN -> "(default, ignored for PLAN)";
                            case CODE -> service.nameOf(requireNonNull(codeModeModel));
                            case REVIEW -> "(default, ignored for REVIEW)";
                            case GUIDED_REVIEW -> "(unused)";
                            case ISSUE_DIAGNOSE, ISSUE_WRITER -> "(unused)";
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> !hasCodeModelOverride;
                            case ASK, SEARCH, PLAN, REVIEW, GUIDED_REVIEW -> true;
                            case CODE -> !hasCodeModelOverride;
                            case ISSUE_DIAGNOSE, ISSUE_WRITER -> true;
                        };
                if (plannerModelNameForLog == null || plannerModelNameForLog.isBlank()) {
                    plannerModelNameForLog = (mode == Mode.CODE) ? "(unused)" : "(unknown)";
                }
                if (codeModelNameForLog == null || codeModelNameForLog.isBlank()) {
                    codeModelNameForLog = usesDefaultCodeModel ? "(default)" : "(unknown)";
                } else if (usesDefaultCodeModel && mode != Mode.ASK && mode != Mode.SEARCH && mode != Mode.REVIEW) {
                    codeModelNameForLog = codeModelNameForLog + " (default)";
                }

                logger.info(
                        "Job {} mode={} plannerModel={} codeModel={}",
                        jobId,
                        mode,
                        plannerModelNameForLog,
                        codeModelNameForLog);

                // Execute within submitLlmAction to honor cancellation semantics
                cm.setAutoCommit(spec.autoCommit());
                cm.submitLlmAction(() -> {
                            if (cancelled.get()) {
                                logger.info("Job {} execution cancelled by user", jobId);
                                return;
                            }
                            try {
                                switch (mode) {
                                    case ARCHITECT -> {
                                        cm.executeTask(
                                                new TaskList.TaskItem("", spec.taskInput(), false),
                                                requireNonNull(
                                                        architectPlannerModel,
                                                        "plannerModel required for ARCHITECT jobs"),
                                                requireNonNull(
                                                        architectCodeModel,
                                                        "code model unavailable for ARCHITECT jobs"));
                                    }
                                    case LUTZ -> {
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            new LutzExecutor(cm, cancelled::get, console)
                                                    .execute(
                                                            spec.taskInput(),
                                                            requireNonNull(
                                                                    architectPlannerModel,
                                                                    "plannerModel required for LUTZ jobs"),
                                                            architectCodeModel,
                                                            scope);
                                        }
                                    }
                                    case PLAN -> {
                                        // PLAN mode: LUTZ Phase 1+2 only (generate task list, no execution)
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    Objects.requireNonNull(
                                                            architectPlannerModel,
                                                            "plannerModel required for PLAN jobs"),
                                                    objectiveForMode(Mode.PLAN),
                                                    scope);
                                            scope.append(searchAgent.execute());
                                        }
                                    }
                                    case CODE -> {
                                        var agent = new CodeAgent(
                                                cm,
                                                requireNonNull(codeModeModel, "code model unavailable for CODE jobs"));
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var result = agent.execute(spec.taskInput(), Set.of());
                                            scope.append(result);
                                        }
                                    }
                                    case ASK -> {
                                        // Read-only ASK execution: perform optional pre-scan (Context Agent)
                                        // then generate a single, final written answer using the plannerModel
                                        // and the current Workspace. Do NOT invoke SearchAgent.execute() or
                                        // any tools that could modify the workspace. Append the answer to the
                                        // task scope and continue.
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();

                                            // Optional pre-scan: resolve scan model similarly to SEARCH mode.
                                            if (spec.preScan()) {
                                                // Construct agent only for potential pre-scan usage (no execute).
                                                var searchAgent = new LutzAgent(
                                                        context,
                                                        spec.taskInput(),
                                                        requireNonNull(
                                                                askPlannerModel, "plannerModel required for ASK jobs"),
                                                        objectiveForMode(Mode.ASK),
                                                        scope);

                                                String rawScanModel = spec.scanModel();
                                                String trimmedScanModel =
                                                        rawScanModel == null ? "" : rawScanModel.trim();

                                                // Emit deterministic start NOTIFICATION so headless clients/tests
                                                // can observe the pre-scan start.
                                                try {
                                                    store.appendEvent(
                                                            jobId,
                                                            JobEvent.of(
                                                                    "NOTIFICATION",
                                                                    "Brokk Context Engine: analyzing repository context..."));
                                                } catch (IOException ioe) {
                                                    logger.warn(
                                                            "Failed to append pre-scan start notification event for job {}: {}",
                                                            jobId,
                                                            ioe.getMessage(),
                                                            ioe);
                                                }

                                                StreamingChatModel scanModelToUse = null;
                                                try {
                                                    scanModelToUse = !trimmedScanModel.isEmpty()
                                                            ? resolveModelOrThrow(
                                                                    trimmedScanModel,
                                                                    spec.reasoningLevel(),
                                                                    spec.temperature())
                                                            : defaultScanModel(spec);
                                                } catch (IllegalArgumentException iae) {
                                                    // resolveModelOrThrow may throw; log and continue without
                                                    // failing job.
                                                    logger.warn(
                                                            "Pre-scan model unavailable for job {}: {}",
                                                            jobId,
                                                            iae.getMessage());
                                                } catch (Exception e) {
                                                    logger.warn(
                                                            "Unexpected error during pre-scan model resolution for job {}: {}",
                                                            jobId,
                                                            e.getMessage(),
                                                            e);
                                                }

                                                if (scanModelToUse == null) {
                                                    // No scan model available; log and skip pre-scan but still emit
                                                    // completion below.
                                                    logger.warn(
                                                            "ASK pre-scan requested but no scan model is available (spec.scanModel='{}'). Skipping pre-scan for job {}.",
                                                            trimmedScanModel,
                                                            jobId);
                                                } else {
                                                    // Attempt the pre-scan, but do not allow failures to abort the
                                                    // job.
                                                    try {
                                                        context = searchAgent.scanContext();
                                                    } catch (InterruptedException ie) {
                                                        // Preserve interruption status but continue with the job.
                                                        Thread.currentThread().interrupt();
                                                        logger.warn(
                                                                "Pre-scan interrupted for job {}: {}",
                                                                jobId,
                                                                ie.getMessage(),
                                                                ie);
                                                    } catch (IllegalArgumentException iae) {
                                                        // Model resolution or argument problems: log and continue.
                                                        logger.warn(
                                                                "Pre-scan skipped due to model error for job {}: {}",
                                                                jobId,
                                                                iae.getMessage());
                                                    } catch (Exception ex) {
                                                        // Any other exception during pre-scan should not fail the
                                                        // job.
                                                        logger.warn(
                                                                "Pre-scan failed for job {}: {}",
                                                                jobId,
                                                                ex.getMessage(),
                                                                ex);
                                                    }
                                                }

                                                // Emit deterministic completion NOTIFICATION so headless
                                                // clients/tests can reliably observe that the Context Engine
                                                // pre-scan phase finished.
                                                try {
                                                    store.appendEvent(
                                                            jobId,
                                                            JobEvent.of(
                                                                    "NOTIFICATION",
                                                                    "Brokk Context Engine: complete — contextual insights added to Workspace."));
                                                } catch (IOException ioe) {
                                                    logger.warn(
                                                            "Failed to append pre-scan completion event for job {}: {}",
                                                            jobId,
                                                            ioe.getMessage(),
                                                            ioe);
                                                }
                                            }

                                            try {
                                                // Use helper that builds a workspace-only prompt and calls the
                                                // planner model.
                                                TaskResult askResult = askUsingPlannerModel(
                                                        context, requireNonNull(askPlannerModel), spec.taskInput());
                                                scope.append(askResult);
                                            } catch (Throwable t) {
                                                // Do not allow a planner-model failure to abort the entire job.
                                                logger.error(
                                                        "ASK direct-answer failed for job {}: {}",
                                                        jobId,
                                                        t.getMessage(),
                                                        t);
                                                // Report to headless console if available (best-effort).
                                                if (console != null) {
                                                    try {
                                                        console.toolError(
                                                                "ASK direct-answer failed: "
                                                                        + (t.getMessage() == null
                                                                                ? t.getClass()
                                                                                        .getSimpleName()
                                                                                : t.getMessage()),
                                                                "ASK error");
                                                    } catch (Throwable ignore) {
                                                        // best-effort only
                                                    }
                                                }
                                                // Append a non-fatal TaskResult indicating the failure so the task
                                                // has a record,
                                                // but do not rethrow (so job status handling can complete
                                                // normally).
                                                var stopDetails = new TaskResult.StopDetails(
                                                        TaskResult.StopReason.LLM_ERROR,
                                                        t.getMessage() == null
                                                                ? t.getClass().getSimpleName()
                                                                : t.getMessage());
                                                List<ChatMessage> uiMessages = List.of(
                                                        new UserMessage(spec.taskInput()),
                                                        new SystemMessage("ASK direct-answer failed: "
                                                                + stopDetails.explanation()));
                                                context = context.addHistoryEntry(
                                                        uiMessages,
                                                        TaskResult.Type.ASK,
                                                        requireNonNull(askPlannerModel),
                                                        spec.taskInput());
                                                var failureResult = new TaskResult(context, stopDetails);
                                                try {
                                                    scope.append(failureResult);
                                                } catch (Throwable e2) {
                                                    // If appending also fails, log it but keep proceeding so we can
                                                    // update job status normally.
                                                    logger.warn(
                                                            "Failed to append ASK failure result for job {}: {}",
                                                            jobId,
                                                            e2.getMessage(),
                                                            e2);
                                                }
                                                // Do not rethrow; allow outer flow to mark job completed
                                                // (read-only) where appropriate.
                                            }
                                        }
                                    }
                                    case SEARCH -> {
                                        // Read-only repository search using a scan model (spec.scanModel preferred,
                                        // otherwise project default)
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();

                                            // Determine scan model: prefer explicit spec.scanModel() if provided,
                                            // otherwise use project default
                                            String rawScanModel = spec.scanModel();
                                            String trimmedScanModel = rawScanModel == null ? null : rawScanModel.trim();
                                            final StreamingChatModel scanModelToUse = (trimmedScanModel != null
                                                            && !trimmedScanModel.isEmpty())
                                                    ? resolveModelOrThrow(
                                                            trimmedScanModel, spec.reasoningLevel(), spec.temperature())
                                                    : defaultScanModel(spec);

                                            // SearchAgent now handles scanning internally via execute()
                                            var scanConfig = LutzAgent.ScanConfig.withModel(scanModelToUse);
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    requireNonNull(
                                                            scanModelToUse, "scan model unavailable for SEARCH jobs"),
                                                    objectiveForMode(Mode.SEARCH),
                                                    scope,
                                                    cm.getIo(),
                                                    scanConfig);
                                            var result = searchAgent.execute();
                                            scope.append(result);
                                        }
                                    }
                                    case REVIEW -> {
                                        // 1. Extract and validate PR metadata
                                        String githubToken = spec.getGithubToken();
                                        String repoOwner = spec.getRepoOwner();
                                        String repoName = spec.getRepoName();
                                        Integer prNumber = spec.getPrNumber();

                                        if (githubToken == null || githubToken.isBlank()) {
                                            throw new IllegalArgumentException("REVIEW requires github_token in tags");
                                        }
                                        if (repoOwner == null || repoOwner.isBlank()) {
                                            throw new IllegalArgumentException("REVIEW requires repo_owner in tags");
                                        }
                                        if (repoName == null || repoName.isBlank()) {
                                            throw new IllegalArgumentException("REVIEW requires repo_name in tags");
                                        }
                                        if (prNumber == null) {
                                            throw new IllegalArgumentException("REVIEW requires pr_number in tags");
                                        }

                                        var severityThreshold = spec.getSeverityThreshold();

                                        try (var scope = cm.beginTaskUngrouped("PR Review #" + prNumber)) {
                                            var context = cm.liveContext();

                                            // 2. Create GitHubAuth and get PR
                                            var gitHubAuth = new GitHubAuth(repoOwner, repoName, null, githubToken);
                                            var ghRepo = gitHubAuth.getGhRepository();
                                            var pr = ghRepo.getPullRequest(prNumber);

                                            // 3. Fetch PR details (base branch, head SHA)
                                            var prDetails = PrReviewService.fetchPrDetails(ghRepo, prNumber);
                                            String baseBranch = prDetails.baseBranch();
                                            String headSha = prDetails.headSha();

                                            // 3a. Fetch PR refs and base branch from a single resolved remote to
                                            // ensure the refs we diff against exist locally.
                                            var gitRepo =
                                                    (GitRepo) cm.getProject().getRepo();

                                            String remoteName = gitRepo.remote().getOriginRemoteNameWithFallback();
                                            if (remoteName == null) {
                                                throw new IllegalStateException(
                                                        "PR review requires a configured git remote (no remote found; expected 'origin' or a fallback remote)");
                                            }

                                            try {
                                                store.appendEvent(
                                                        jobId,
                                                        JobEvent.of(
                                                                "NOTIFICATION",
                                                                "Fetching PR refs from remote '" + remoteName
                                                                        + "'..."));
                                            } catch (IOException ioe) {
                                                logger.warn(
                                                        "Failed to append fetch notification event for job {}: {}",
                                                        jobId,
                                                        ioe.getMessage(),
                                                        ioe);
                                            }

                                            try {
                                                gitRepo.remote().fetchPrRef(prNumber, remoteName, githubToken);
                                            } catch (GitAPIException e) {
                                                logger.warn(
                                                        "Failed to fetch PR ref for PR #{} from remote '{}': {}",
                                                        prNumber,
                                                        remoteName,
                                                        e.getMessage());
                                                throw new IllegalStateException(
                                                        "Failed to fetch PR ref for PR #" + prNumber + " from remote '"
                                                                + remoteName + "': " + e.getMessage(),
                                                        e);
                                            }

                                            try {
                                                gitRepo.remote().fetchBranch(remoteName, baseBranch, githubToken);
                                            } catch (GitAPIException e) {
                                                logger.warn(
                                                        "Failed to fetch base branch '{}' for PR #{} from remote '{}': {}",
                                                        baseBranch,
                                                        prNumber,
                                                        remoteName,
                                                        e.getMessage());
                                                throw new IllegalStateException(
                                                        "Failed to fetch base branch '" + baseBranch + "' for PR #"
                                                                + prNumber + " from remote '" + remoteName + "': "
                                                                + e.getMessage(),
                                                        e);
                                            }

                                            String baseRef = remoteName + "/" + baseBranch;
                                            String prRef = remoteName + "/pr/" + prNumber;

                                            // 4. Compute PR diff using fetched refs
                                            String diff = PrReviewService.computePrDiff(gitRepo, baseRef, prRef);

                                            // 4a. Annotate diff with line numbers for LLM review
                                            String annotatedDiff = PrReviewService.annotateDiffWithLineNumbers(diff);

                                            // Pre-scan to load related context from the diff
                                            try {
                                                store.appendEvent(
                                                        jobId,
                                                        JobEvent.of(
                                                                "NOTIFICATION",
                                                                "Brokk Context Engine: analyzing repository context for PR review..."));

                                                var scanGoal =
                                                        "Analyzing changes in this PR diff to identify related code context:\n```diff\n"
                                                                + annotatedDiff + "\n```";
                                                var searchAgent = new LutzAgent(
                                                        context,
                                                        scanGoal,
                                                        requireNonNull(
                                                                reviewScanModel,
                                                                "scan model unavailable for REVIEW pre-scan"),
                                                        objectiveForMode(Mode.REVIEW),
                                                        scope);

                                                context = searchAgent.scanContext();

                                                store.appendEvent(
                                                        jobId,
                                                        JobEvent.of(
                                                                "NOTIFICATION",
                                                                "Brokk Context Engine: complete — contextual insights added to Workspace."));
                                            } catch (InterruptedException ie) {
                                                Thread.currentThread().interrupt();
                                                logger.warn(
                                                        "Pre-scan interrupted for REVIEW job {}: {}",
                                                        jobId,
                                                        ie.getMessage());
                                            } catch (Exception ex) {
                                                logger.warn(
                                                        "Pre-scan failed for REVIEW job {}: {}",
                                                        jobId,
                                                        ex.getMessage());
                                            }

                                            // 5. Call reviewDiff() to get LLM review with enriched context
                                            var plannerModel = requireNonNull(
                                                    reviewPlannerModel, "planner model unavailable for REVIEW jobs");

                                            var reviewExecutor = new PrReviewExecutor(cm);
                                            var review = reviewExecutor.reviewDiff(
                                                    context,
                                                    plannerModel,
                                                    annotatedDiff,
                                                    prDetails.title(),
                                                    prDetails.body(),
                                                    severityThreshold);
                                            TaskResult reviewResult = review.taskResult();
                                            scope.append(reviewResult);

                                            // 6. Parse review output (JSON only)
                                            String reviewText = review.responseText();

                                            var reviewResponse = PrReviewService.parsePrReviewResponse(reviewText);

                                            if (reviewResponse == null) {
                                                if (reviewText.isBlank()) {
                                                    var stopDetails = reviewResult.stopDetails();
                                                    logger.error(
                                                            "LLM returned empty response for review job {}. Stop reason: {}, explanation: {}",
                                                            jobId,
                                                            stopDetails.reason(),
                                                            stopDetails.explanation());
                                                    String causeDetail =
                                                            stopDetails.reason() == TaskResult.StopReason.SUCCESS
                                                                    ? ""
                                                                    : " Cause: "
                                                                            + stopDetails
                                                                                    .explanation()
                                                                                    .lines()
                                                                                    .findFirst()
                                                                                    .orElse(stopDetails
                                                                                            .reason()
                                                                                            .name());
                                                    throw new IllegalStateException(
                                                            "LLM returned empty response for PR review." + causeDetail);
                                                }
                                                // JSON parsing failed - treat as hard error
                                                String preview = reviewText.length() > 500
                                                        ? reviewText.substring(0, 500) + "..."
                                                        : reviewText;
                                                logger.error(
                                                        "PR review response was not valid JSON for job {}. Response preview: {}",
                                                        jobId,
                                                        preview);
                                                throw new IllegalStateException(
                                                        "PR review response was not valid JSON. Expected JSON object with 'summaryMarkdown' field. Response preview: "
                                                                + preview);
                                            }

                                            // JSON parsing succeeded - use structured format
                                            logger.debug("PR review parsed as JSON successfully");
                                            String summary = reviewResponse.summaryMarkdown();

                                            // 7. Post summary comment to PR
                                            PrReviewService.postReviewComment(pr, summary);
                                            logger.info("Posted PR review summary to PR #{}", prNumber);

                                            // 8. Post inline comments from structured response (filtered)
                                            int postedComments = 0;
                                            int skippedComments = 0;

                                            var filteredComments = PrReviewService.filterInlineComments(
                                                    reviewResponse.comments(),
                                                    severityThreshold,
                                                    DEFAULT_REVIEW_MAX_INLINE_COMMENTS);

                                            for (var comment : filteredComments) {
                                                String path = comment.path();
                                                int line = comment.line();
                                                String bodyMarkdown = comment.bodyMarkdown();

                                                try {
                                                    if (!PrReviewService.hasExistingLineComment(pr, path, line)) {
                                                        PrReviewService.postLineComment(
                                                                pr, path, line, bodyMarkdown, headSha);
                                                        postedComments++;
                                                        logger.debug(
                                                                "Posted line comment on {}:{} severity={}",
                                                                path,
                                                                line,
                                                                comment.severity());
                                                    } else {
                                                        skippedComments++;
                                                        logger.debug(
                                                                "Skipped duplicate comment on {}:{} severity={}",
                                                                path,
                                                                line,
                                                                comment.severity());
                                                    }
                                                } catch (Exception e) {
                                                    logger.warn(
                                                            "Failed to post line comment on {}:{}: {}",
                                                            path,
                                                            line,
                                                            e.getMessage());
                                                }
                                            }

                                            logger.info(
                                                    "PR Review complete for PR #{}: posted {} line comments, skipped {} duplicates",
                                                    prNumber,
                                                    postedComments,
                                                    skippedComments);
                                        }
                                    }
                                    case GUIDED_REVIEW -> {
                                        try (var taskScope = cm.beginTaskUngrouped("Guided Review")) {
                                            var reviewScope = ReviewScope.fromDefaultBranch(cm);

                                            var plannerModel = resolveModelOrThrow(
                                                    spec.plannerModel(), spec.reasoningLevel(), spec.temperature());
                                            var modelConfig = Service.ModelConfig.from(plannerModel, cm.getService());

                                            var reviewAgent = new ReviewAgent(reviewScope, modelConfig, false, cm);

                                            reviewAgent.setProgressUpdater((stage, progress) -> {
                                                try {
                                                    var progressData = new LinkedHashMap<String, Object>();
                                                    progressData.put("stage", stage);
                                                    progressData.put("percent", progress);
                                                    store.appendEvent(
                                                            jobId, JobEvent.of("REVIEW_PROGRESS", progressData));
                                                } catch (IOException e) {
                                                    logger.warn(
                                                            "Failed to emit review progress event for job {}: {}",
                                                            jobId,
                                                            e.getMessage());
                                                }
                                            });

                                            var result = reviewAgent.execute();

                                            var review = result.review();
                                            var reviewData = new LinkedHashMap<String, Object>();
                                            reviewData.put("overview", review.overview());

                                            var keyChanges = new ArrayList<Map<String, Object>>();
                                            for (var kc : review.keyChanges()) {
                                                var kcMap = new LinkedHashMap<String, Object>();
                                                kcMap.put("title", kc.title());
                                                kcMap.put("description", kc.description());
                                                kcMap.put("excerpts", convertExcerpts(kc.excerpts()));
                                                keyChanges.add(kcMap);
                                            }
                                            reviewData.put("keyChanges", keyChanges);

                                            var designNotes = new ArrayList<Map<String, Object>>();
                                            for (var dn : review.designNotes()) {
                                                var dnMap = new LinkedHashMap<String, Object>();
                                                dnMap.put("title", dn.title());
                                                dnMap.put("description", dn.description());
                                                dnMap.put("excerpts", convertExcerpts(dn.excerpts()));
                                                dnMap.put("recommendation", dn.recommendation());
                                                designNotes.add(dnMap);
                                            }
                                            reviewData.put("designNotes", designNotes);

                                            var tacticalNotes = new ArrayList<Map<String, Object>>();
                                            for (var tn : review.tacticalNotes()) {
                                                var tnMap = new LinkedHashMap<String, Object>();
                                                tnMap.put("title", tn.title());
                                                tnMap.put("description", tn.description());
                                                tnMap.put("excerpts", convertExcerpts(List.of(tn.excerpt())));
                                                tnMap.put("recommendation", tn.recommendation());
                                                tacticalNotes.add(tnMap);
                                            }
                                            reviewData.put("tacticalNotes", tacticalNotes);

                                            var additionalTests = new ArrayList<Map<String, Object>>();
                                            for (var at : review.additionalTests()) {
                                                var atMap = new LinkedHashMap<String, Object>();
                                                atMap.put("title", at.title());
                                                atMap.put("recommendation", at.recommendation());
                                                additionalTests.add(atMap);
                                            }
                                            reviewData.put("additionalTests", additionalTests);

                                            store.appendEvent(jobId, JobEvent.of("REVIEW_COMPLETE", reviewData));

                                            taskScope.append(new TaskResult(
                                                    result.context(),
                                                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)));
                                        }
                                    }
                                    case ISSUE -> {
                                        StreamingChatModel issuePlannerModel = requireNonNull(
                                                architectPlannerModel, "plannerModel required for ISSUE jobs");
                                        StreamingChatModel issueCodeModel = requireNonNull(
                                                architectCodeModel, "code model required for ISSUE jobs");

                                        var issueExecutor =
                                                new IssueExecutor(cm, store, jobId, cancelled::get, console);
                                        issueExecutor.executeSolve(spec, issuePlannerModel, issueCodeModel);
                                    }
                                    case ISSUE_DIAGNOSE -> {
                                        var model = resolveModelOrThrow(
                                                spec.plannerModel(), spec.reasoningLevel(), spec.temperature());
                                        var issueExecutor = new IssueExecutor(cm, store, jobId);
                                        issueExecutor.executeDiagnose(spec, model);
                                    }
                                    case ISSUE_WRITER -> {
                                        String githubToken = spec.getGithubToken();
                                        String repoOwner = spec.getRepoOwner();
                                        String repoName = spec.getRepoName();

                                        if (githubToken == null || githubToken.isBlank()) {
                                            throw new IllegalArgumentException(
                                                    "ISSUE_WRITER requires github_token in tags");
                                        }
                                        if (repoOwner == null || repoOwner.isBlank()) {
                                            throw new IllegalArgumentException(
                                                    "ISSUE_WRITER requires repo_owner in tags");
                                        }
                                        if (repoName == null || repoName.isBlank()) {
                                            throw new IllegalArgumentException(
                                                    "ISSUE_WRITER requires repo_name in tags");
                                        }

                                        if (cm.getProject().isEmptyProject()) {
                                            String msg =
                                                    "ISSUE_WRITER requires a materialized repository in the workspace. Clone/open the repo in the selected workspace directory (e.g., via HeadlessExecCli which clones for ISSUE/REVIEW/ISSUE_WRITER).";
                                            try {
                                                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", msg));
                                            } catch (IOException ioe) {
                                                logger.warn(
                                                        "Failed to append ISSUE_WRITER empty-workspace notification for job {}: {}",
                                                        jobId,
                                                        ioe.getMessage(),
                                                        ioe);
                                            }
                                            throw new IllegalStateException(msg);
                                        }

                                        try {
                                            store.appendEvent(
                                                    jobId,
                                                    JobEvent.of(
                                                            "NOTIFICATION",
                                                            "ISSUE_WRITER: starting repository discovery"));
                                        } catch (IOException ioe) {
                                            logger.warn(
                                                    "Failed to append ISSUE_WRITER start notification for job {}: {}",
                                                    jobId,
                                                    ioe.getMessage(),
                                                    ioe);
                                        }

                                        var model = resolveModelOrThrow(
                                                spec.plannerModel(), spec.reasoningLevel(), spec.temperature());
                                        var writerService =
                                                new IssueRewriterAgent(cm.liveContext(), model, spec.taskInput());
                                        var parsed = writerService.execute();
                                        cm.pushContext(ctx -> parsed.context());

                                        try {
                                            store.appendEvent(
                                                    jobId,
                                                    JobEvent.of(
                                                            "NOTIFICATION",
                                                            "ISSUE_WRITER: discovery complete (title: " + parsed.title()
                                                                    + ")"));
                                        } catch (IOException ioe) {
                                            logger.warn(
                                                    "Failed to append ISSUE_WRITER discovery-complete notification for job {}: {}",
                                                    jobId,
                                                    ioe.getMessage(),
                                                    ioe);
                                        }

                                        logger.info(
                                                "ISSUE_WRITER job {}: creating GitHub issue in {}/{}",
                                                jobId,
                                                repoOwner,
                                                repoName);

                                        var auth = new GitHubAuth(repoOwner, repoName, null, githubToken);
                                        var githubIssueService = new GitHubIssueService(cm.getProject(), auth);

                                        IssueHeader created =
                                                githubIssueService.createIssue(parsed.title(), parsed.bodyMarkdown());

                                        logger.info(
                                                "ISSUE_WRITER job {} created GitHub issue in {}/{}: id={} url={}",
                                                jobId,
                                                repoOwner,
                                                repoName,
                                                created.id(),
                                                created.htmlUrl());

                                        String createdMsg = "ISSUE_WRITER: issue created";
                                        if (!created.id().isBlank()) {
                                            createdMsg += " " + created.id();
                                        }
                                        if (created.htmlUrl() != null) {
                                            createdMsg += " " + created.htmlUrl();
                                        }

                                        try {
                                            store.appendEvent(jobId, JobEvent.of("NOTIFICATION", createdMsg));

                                            var issueCreatedData = new LinkedHashMap<String, Object>();
                                            issueCreatedData.put("issueId", created.id());
                                            if (created.htmlUrl() != null) {
                                                issueCreatedData.put(
                                                        "issueUrl",
                                                        created.htmlUrl().toString());
                                            }
                                            issueCreatedData.put("repoOwner", repoOwner);
                                            issueCreatedData.put("repoName", repoName);
                                            store.appendEvent(jobId, JobEvent.of("ISSUE_CREATED", issueCreatedData));
                                        } catch (IOException ioe) {
                                            logger.warn(
                                                    "Failed to append ISSUE_WRITER issue-created notification for job {}: {}",
                                                    jobId,
                                                    ioe.getMessage(),
                                                    ioe);
                                        }
                                    }
                                    default -> throw new IllegalStateException("Unhandled job mode: " + mode);
                                }

                                completed.incrementAndGet();

                                try {
                                    JobStatus s = store.loadStatus(jobId);
                                    if (s != null) {
                                        store.updateStatus(jobId, s);
                                    }
                                } catch (Exception e) {
                                    logger.debug("Unable to update job {} for {}", jobId, e);
                                }
                            } catch (Exception e) {
                                logger.warn("Task execution failed for job {}: {}", jobId, e.getMessage());
                                // Continue to next task or exit depending on requirements
                                throw e;
                            }
                        })
                        .join();

                // Optional compress after execution:
                // - For ARCHITECT/LUTZ: per-task compression already honored via spec.autoCompress().
                if (mode != Mode.ARCHITECT && mode != Mode.LUTZ && spec.autoCompress()) {
                    logger.info("Job {} auto-compressing history", jobId);
                    cm.compressGlobalHistory();
                }

                // Determine final status: completed or cancelled
                JobStatus current = store.loadStatus(jobId);
                if (current != null) {
                    if (cancelled.get()) {
                        current = current.cancelled();
                        logger.info("Job {} marked as CANCELLED", jobId);
                    } else {
                        current = current.completed(null);
                        logger.info("Job {} completed successfully", jobId);
                    }
                    if (console != null) {
                        long lastSeq = console.getLastSeq();
                        current = current.withMetadata("lastSeq", Long.toString(lastSeq));
                    }
                    store.updateStatus(jobId, current);
                }
            } catch (Throwable t) {
                var failure = unwrapFailure(t);

                boolean isCancellation = cancelled.get()
                        || failure instanceof IssueCancelledException
                        || t instanceof IssueCancelledException;

                if (isCancellation) {
                    logger.info("Job {} cancelled", jobId, failure);

                    try {
                        JobStatus s = store.loadStatus(jobId);
                        if (s == null) {
                            s = JobStatus.queued(jobId);
                        }

                        boolean alreadyCancelled =
                                JobStatus.State.CANCELLED.name().equals(s.state());
                        boolean hadLastSeq = s.metadata().containsKey("lastSeq");

                        if (!alreadyCancelled) {
                            s = s.cancelled();
                        }

                        if (console != null) {
                            long lastSeq = console.getLastSeq();
                            s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                        }

                        // Update if state changed or if we enriched with missing metadata
                        if (!alreadyCancelled || !hadLastSeq) {
                            store.updateStatus(jobId, s);
                        } else {
                            logger.debug(
                                    "Job {} already marked CANCELLED with metadata, skipping redundant update", jobId);
                        }
                    } catch (Exception e2) {
                        logger.warn("Failed to persist CANCELLED status for job {}", jobId, e2);
                    }
                } else {
                    logger.error("Job {} execution failed", jobId, t);

                    var errorMessage = formatThrowableMessage(failure);

                    // Emit error event
                    if (console != null) {
                        try {
                            console.toolError(errorMessage, "Job error");
                        } catch (Throwable ignore) {
                            // Non-critical: event writing failed
                        }
                    }

                    // Update status to FAILED
                    try {
                        JobStatus s = store.loadStatus(jobId);
                        if (s == null) {
                            s = JobStatus.queued(jobId);
                        }
                        s = s.failed(errorMessage);
                        if (console != null) {
                            long lastSeq = console.getLastSeq();
                            s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                        }
                        store.updateStatus(jobId, s);
                    } catch (Exception e2) {
                        logger.warn("Failed to persist FAILED status for job {}", jobId, e2);
                    }

                    futureFailure[0] = failure;
                }
            } finally {
                // Clean up
                if (console != null) {
                    try {
                        // No-op: events are written synchronously, so nothing to await.
                    } catch (Throwable ignore) {
                        // Non-critical: shutdown failed
                    }
                }
                // Restore original console. HeadlessHttpConsole is installed only for the job duration so that
                // all ContextManager/agents IConsoleIO callbacks flow to the JobStore; then the previous console is
                // restored.
                cm.setAutoCommit(previousAutoCommit);
                cm.setIo(previousIo);
                activeJobId = null;
                logger.info("Job {} execution ended", jobId);

                if (futureFailure[0] != null) {
                    future.completeExceptionally(futureFailure[0]);
                } else {
                    future.complete(null);
                }
            }
        });

        return future;
    }

    /**
     * Request cancellation of an active job.
     *
     * <p>This is a cooperative cancel: it interrupts the LLM action and attempts to transition
     * the job status to CANCELLED if not already in a terminal state. The actual cancellation
     * is enforced by the cancelled flag checked during task loop execution.
     *
     * @param jobId The job ID to cancel
     */
    public void cancel(String jobId) {
        if (activeJobId == null || !Objects.equals(activeJobId, jobId)) {
            logger.info("Cancel requested for job {}, but no active job or different jobId", jobId);
            return;
        }

        logger.info("Cancelling job {}", jobId);
        cancelled.set(true);

        // Interrupt LLM action to stop streaming/task execution
        try {
            cm.interruptLlmAction();
        } catch (Throwable ignore) {
            logger.debug("Failed to interrupt LLM action", ignore);
        }

        // Update status to CANCELLED if not already terminal
        try {
            JobStatus s = store.loadStatus(jobId);
            if (s != null) {
                String state = s.state();
                // Only transition if not already in a terminal state
                if (!JobStatus.State.COMPLETED.name().equals(state)
                        && !JobStatus.State.FAILED.name().equals(state)
                        && !JobStatus.State.CANCELLED.name().equals(state)) {
                    s = s.cancelled();
                    long lastSeq = console != null ? console.getLastSeq() : -1;
                    s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                    store.updateStatus(jobId, s);
                    logger.info("Job {} status updated to CANCELLED", jobId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to mark job {} as CANCELLED", jobId, e);
        }
    }

    private record AppliedOverrides(
            Service.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {}

    private AppliedOverrides applyOverrides(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {
        Service.ReasoningLevel reasoning =
                Service.ReasoningLevel.fromString(reasoningLevelOverride, baseConfig.reasoning());

        var config = reasoning == baseConfig.reasoning()
                ? baseConfig
                : new Service.ModelConfig(baseConfig.name(), reasoning, baseConfig.tier());

        @Nullable OpenAiChatRequestParameters.Builder parametersOverride = null;
        if (temperatureOverride != null) {
            if (cm.getService().supportsTemperature(baseConfig.name())) {
                parametersOverride = OpenAiChatRequestParameters.builder().temperature(temperatureOverride);
            } else {
                logger.debug("Skipping temperature override for model {} as it is not supported.", baseConfig.name());
            }
        }

        return new AppliedOverrides(config, parametersOverride);
    }

    private StreamingChatModel resolveModelOrThrow(
            String name, @Nullable String reasoningLevelOverride, @Nullable Double temperatureOverride) {
        var service = cm.getService();

        var applied = applyOverrides(new Service.ModelConfig(name), reasoningLevelOverride, temperatureOverride);
        var model = service.getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + name);
        }
        return model;
    }

    private StreamingChatModel resolveModelOrThrow(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {
        var service = cm.getService();

        var applied = applyOverrides(baseConfig, reasoningLevelOverride, temperatureOverride);
        var model = service.getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + baseConfig.name());
        }
        return model;
    }

    private StreamingChatModel defaultCodeModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(cm.getCodeModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevelCode(), spec.temperatureCode());
    }

    private StreamingChatModel defaultScanModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(service.getScanModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevel(), spec.temperature());
    }

    /**
     * Build a single-shot workspace-only prompt using the provided planner model and return
     * a TaskResult representing the answer (or an error TaskResult on failure). This helper
     * is read-only and does not mutate workspace state.
     *
     * @param model the model to use for the single-shot answer
     * @param question the user's question / task text
     * @return a TaskResult suitable for appending to a TaskScope
     */
    private TaskResult askUsingPlannerModel(Context ctx, StreamingChatModel model, String question) {
        var svc = cm.getService();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, Service.ModelConfig.from(model, svc));

        List<ChatMessage> messages;
        messages = SearchPrompts.instance.buildAskPrompt(ctx, question, meta);
        // Create an LLM instance for the planner model and route output to the ContextManager IO
        var llm = cm.getLlm(new Llm.Options(model, question, TaskResult.Type.ASK).withEcho());
        llm.setOutput(cm.getIo());
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Determine stop details based on the response
        if (response != null) {
            stop = TaskResult.StopDetails.fromResponse(response);
        }

        requireNonNull(stop);

        ctx = ctx.addHistoryEntry(cm.getIo().getLlmRawMessages(), TaskResult.Type.ASK, model, question);
        return new TaskResult(ctx, stop);
    }

    private static List<Map<String, Object>> convertExcerpts(List<ReviewParser.CodeExcerpt> excerpts) {
        var result = new ArrayList<Map<String, Object>>();
        for (var excerpt : excerpts) {
            var map = new LinkedHashMap<String, Object>();
            map.put("file", excerpt.file().toString());
            map.put("line", excerpt.line());
            map.put("side", excerpt.side().name());
            map.put("text", excerpt.excerpt());
            if (excerpt.codeUnit() != null) {
                map.put("codeUnit", excerpt.codeUnit().fqName());
            }
            result.add(map);
        }
        return result;
    }

    private static Throwable unwrapFailure(Throwable throwable) {
        var current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)) {
            var cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String formatThrowableMessage(Throwable throwable) {
        var message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
