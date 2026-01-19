package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.context.Context;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.git.GitRepo;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    private static final int DEFAULT_MAX_BUILD_ATTEMPTS = 3;

    private static final PrReviewService.Severity DEFAULT_REVIEW_SEVERITY_THRESHOLD = PrReviewService.Severity.HIGH;
    private static final int DEFAULT_REVIEW_MAX_INLINE_COMMENTS = 5;

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
        LUTZ,
        ISSUE
    }

    static Mode parseMode(JobSpec spec) {
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
        console = new HeadlessHttpConsole(store, jobId);
        final var previousIo = cm.getIo();
        cm.setIo(console);
        logger.info("Job {} attaching streaming console", jobId);

        // Transition status to RUNNING
        try {
            JobStatus status = store.loadStatus(jobId);
            if (status == null) {
                status = JobStatus.queued(jobId);
            }
            status = status.withState("RUNNING");
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
                        (mode == Mode.ARCHITECT || mode == Mode.LUTZ || mode == Mode.ISSUE)
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
                                        Objects.requireNonNull(trimmedCodeModelName),
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
                            case ARCHITECT, LUTZ, ISSUE ->
                                service.nameOf(Objects.requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askPlannerModel));
                            case SEARCH -> service.nameOf(Objects.requireNonNull(searchPlannerModel));
                            case CODE -> {
                                var plannerName = spec.plannerModel();
                                yield plannerName.isBlank() ? "(unused)" : plannerName.trim();
                            }
                            case REVIEW -> service.nameOf(Objects.requireNonNull(reviewPlannerModel));
                        };
                String codeModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> service.nameOf(Objects.requireNonNull(architectCodeModel));
                            case ASK -> "(default, ignored for ASK)";
                            case SEARCH -> "(default, ignored for SEARCH)";
                            case CODE -> service.nameOf(Objects.requireNonNull(codeModeModel));
                            case REVIEW -> "(default, ignored for REVIEW)";
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> !hasCodeModelOverride;
                            case ASK, SEARCH, REVIEW -> true;
                            case CODE -> !hasCodeModelOverride;
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
                                                Objects.requireNonNull(
                                                        architectPlannerModel,
                                                        "plannerModel required for ARCHITECT jobs"),
                                                Objects.requireNonNull(
                                                        architectCodeModel,
                                                        "code model unavailable for ARCHITECT jobs"));
                                    }
                                    case LUTZ -> {
                                        // Phase 1: Use SearchAgent to generate a task list from the initial task
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    Objects.requireNonNull(
                                                            architectPlannerModel,
                                                            "plannerModel required for LUTZ jobs"),
                                                    SearchPrompts.Objective.TASKS_ONLY,
                                                    scope);
                                            var taskListResult = searchAgent.execute();
                                            scope.append(taskListResult);
                                        }
                                        // Task list is now in the live context and persisted by the scope
                                        logger.debug("LUTZ Phase 1 complete: task list generated");

                                        // Phase 2: Check if task list was generated; if empty, mark job complete
                                        var generatedTasks = cm.getTaskList().tasks();
                                        if (generatedTasks.isEmpty()) {
                                            var msg = "SearchAgent generated no tasks for: " + spec.taskInput();
                                            logger.info("LUTZ job {}: {}", jobId, msg);
                                            if (console != null) {
                                                try {
                                                    console.showNotification(IConsoleIO.NotificationRole.INFO, msg);
                                                } catch (Throwable ignore) {
                                                    // Non-critical: event writing failed
                                                }
                                            }
                                            // No tasks generated; outer loop will handle completion/progress
                                        } else {
                                            // Phase 3: Execute each generated incomplete task sequentially
                                            logger.debug(
                                                    "LUTZ Phase 2 complete: {} task(s) to execute",
                                                    generatedTasks.size());
                                            var incompleteTasks = generatedTasks.stream()
                                                    .filter(t -> !t.done())
                                                    .toList();
                                            logger.debug(
                                                    "LUTZ will execute {} incomplete task(s)", incompleteTasks.size());

                                            for (TaskList.TaskItem generatedTask : incompleteTasks) {
                                                if (cancelled.get()) {
                                                    logger.info(
                                                            "LUTZ job {} execution cancelled during task iteration",
                                                            jobId);
                                                    return; // Cancelled: exit submitLlmAction early to prevent
                                                    // further job completion handling in the outer loop
                                                }

                                                logger.info(
                                                        "LUTZ job {} executing generated task: {}",
                                                        jobId,
                                                        generatedTask.text());
                                                try {
                                                    cm.executeTask(
                                                            generatedTask,
                                                            architectPlannerModel,
                                                            Objects.requireNonNull(architectCodeModel));
                                                } catch (Exception e) {
                                                    logger.warn(
                                                            "Generated task execution failed for job {}: {}",
                                                            jobId,
                                                            e.getMessage());
                                                    throw e;
                                                }
                                            }

                                            logger.debug("LUTZ Phase 3 complete: all generated tasks executed");
                                        }
                                    }
                                    case CODE -> {
                                        var agent = new CodeAgent(
                                                cm,
                                                Objects.requireNonNull(
                                                        codeModeModel, "code model unavailable for CODE jobs"));
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
                                                        Objects.requireNonNull(
                                                                askPlannerModel, "plannerModel required for ASK jobs"),
                                                        SearchPrompts.Objective.ANSWER_ONLY,
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
                                                        context,
                                                        Objects.requireNonNull(askPlannerModel),
                                                        spec.taskInput());
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
                                                List<ChatMessage> ui = List.of(
                                                        new UserMessage(spec.taskInput()),
                                                        new SystemMessage("ASK direct-answer failed: "
                                                                + stopDetails.explanation()));
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
                                            var scanConfig = SearchAgent.ScanConfig.withModel(scanModelToUse);
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    Objects.requireNonNull(
                                                            scanModelToUse, "scan model unavailable for SEARCH jobs"),
                                                    SearchPrompts.Objective.ANSWER_ONLY,
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
                                                        ioe.getMessage());
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
                                                        Objects.requireNonNull(
                                                                reviewScanModel,
                                                                "scan model unavailable for REVIEW pre-scan"),
                                                        SearchPrompts.Objective.ANSWER_ONLY,
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
                                            var plannerModel = Objects.requireNonNull(
                                                    reviewPlannerModel, "planner model unavailable for REVIEW jobs");
                                            TaskResult reviewResult = reviewDiff(context, plannerModel, annotatedDiff);
                                            scope.append(reviewResult);

                                            // 6. Parse review output (JSON only)
                                            String reviewText =
                                                    reviewResult.output().text().join();

                                            var reviewResponse = PrReviewService.parsePrReviewResponse(reviewText);

                                            if (reviewResponse == null) {
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
                                                    DEFAULT_REVIEW_SEVERITY_THRESHOLD,
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
                                    case ISSUE -> {
                                        StreamingChatModel issuePlannerModel = Objects.requireNonNull(
                                                architectPlannerModel, "plannerModel required for ISSUE jobs");
                                        StreamingChatModel issueCodeModel = Objects.requireNonNull(
                                                architectCodeModel, "code model required for ISSUE jobs");

                                        // 1. Extract and validate Issue metadata
                                        String githubToken = spec.getGithubToken();
                                        String repoOwner = spec.getRepoOwner();
                                        String repoName = spec.getRepoName();
                                        Integer issueNumber = spec.getIssueNumber();

                                        if (githubToken == null || githubToken.isBlank()) {
                                            throw new IssueExecutionException("ISSUE requires github_token in tags");
                                        }
                                        if (repoOwner == null || repoOwner.isBlank()) {
                                            throw new IssueExecutionException("ISSUE requires repo_owner in tags");
                                        }
                                        if (repoName == null || repoName.isBlank()) {
                                            throw new IssueExecutionException("ISSUE requires repo_name in tags");
                                        }
                                        if (issueNumber == null) {
                                            throw new IssueExecutionException("ISSUE requires issue_number in tags");
                                        }

                                        // 2. Resolve issue details and build settings
                                        var gitHubAuth = new GitHubAuth(repoOwner, repoName, null, githubToken);
                                        var ghRepo = gitHubAuth.getGhRepository();
                                        var details = IssueService.fetchIssueDetails(ghRepo, issueNumber);
                                        var buildDetailsOverride =
                                                IssueService.parseBuildSettings(spec.getBuildSettingsJson());

                                        // 3. Branch management
                                        var gitRepo = (GitRepo) cm.getProject().getRepo();
                                        String originalBranch = gitRepo.getCurrentBranch();
                                        String issueBranchName = IssueService.generateBranchName(issueNumber, gitRepo);

                                        logger.info(
                                                "ISSUE job {}: Creating branch {} from {}",
                                                jobId,
                                                issueBranchName,
                                                originalBranch);
                                        gitRepo.createAndCheckoutBranch(issueBranchName, originalBranch);

                                        String issueTaskPrompt = "Resolve GitHub Issue #%d: %s\n\nIssue Body:\n%s"
                                                .formatted(issueNumber, details.title(), details.body());

                                        // 4. Lutz-style execution: Planning then Task Iteration
                                        String taskDescription = "Issue #" + issueNumber + ": " + details.title();
                                        try (var scope = cm.beginTask(issueTaskPrompt, true, taskDescription)) {
                                            var context = cm.liveContext();
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    issueTaskPrompt,
                                                    issuePlannerModel,
                                                    SearchPrompts.Objective.TASKS_ONLY,
                                                    scope);
                                            var taskListResult = searchAgent.execute();
                                            scope.append(taskListResult);

                                            var generatedTasks =
                                                    cm.getTaskList().tasks();
                                            var incompleteTasks = generatedTasks.stream()
                                                    .filter(t -> !t.done())
                                                    .toList();

                                            for (TaskList.TaskItem generatedTask : incompleteTasks) {
                                                if (cancelled.get()) return;

                                                // Execute task with ArchitectAgent
                                                cm.executeTask(generatedTask, issuePlannerModel, issueCodeModel);

                                                // 4. Verification loop: run build and retry on failure
                                                int buildAttempts = 0;
                                                int maxBuildAttempts = Objects.requireNonNullElse(
                                                        buildDetailsOverride.maxBuildAttempts(),
                                                        DEFAULT_MAX_BUILD_ATTEMPTS);

                                                boolean verified = false;

                                                while (!verified && buildAttempts < maxBuildAttempts) {
                                                    buildAttempts++;
                                                    String buildError =
                                                            BuildAgent.runVerification(cm, buildDetailsOverride);
                                                    if (buildError.isBlank()) {
                                                        verified = true;
                                                        logger.info(
                                                                "ISSUE job {} task '{}' verified successfully",
                                                                jobId,
                                                                generatedTask.text());
                                                    } else {
                                                        logger.warn(
                                                                "ISSUE job {} task '{}' build failed (attempt {}/{}): {}",
                                                                jobId,
                                                                generatedTask.text(),
                                                                buildAttempts,
                                                                maxBuildAttempts,
                                                                buildError);

                                                        if (buildAttempts < maxBuildAttempts) {
                                                            // Ask architect to fix the build error
                                                            String fixPrompt = "The build failed after the last task '"
                                                                    + generatedTask.text()
                                                                    + "'. Please fix the following error:\n\n"
                                                                    + buildError;
                                                            cm.executeTask(
                                                                    TaskList.TaskItem.createFixTask(fixPrompt),
                                                                    issuePlannerModel,
                                                                    issueCodeModel);
                                                        } else {
                                                            throw new IssueExecutionException(
                                                                    "Failed to pass build verification after "
                                                                            + maxBuildAttempts + " attempts: "
                                                                            + buildError);
                                                        }
                                                    }
                                                }
                                            }

                                            runFixRetryLoop(
                                                    jobId,
                                                    store,
                                                    cm.getIo(),
                                                    buildDetailsOverride,
                                                    spec.effectiveMaxIssueFixAttempts(),
                                                    cmd -> {
                                                        try {
                                                            return BuildAgent.runExplicitCommand(
                                                                    cm, cmd, buildDetailsOverride);
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread()
                                                                    .interrupt();
                                                            return "Interrupted while running command: " + cmd;
                                                        }
                                                    },
                                                    prompt -> {
                                                        try {
                                                            cm.executeTask(
                                                                    TaskList.TaskItem.createFixTask(prompt),
                                                                    issuePlannerModel,
                                                                    issueCodeModel);
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread()
                                                                    .interrupt();
                                                            throw new IssueExecutionException(
                                                                    "Interrupted while attempting to fix build failure",
                                                                    e);
                                                        }
                                                    });
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

                future.complete(null);
            } catch (Throwable t) {
                logger.error("Job {} execution failed", jobId, t);

                var failure = unwrapFailure(t);
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

                future.completeExceptionally(failure);
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
                cm.setIo(previousIo);
                activeJobId = null;
                logger.info("Job {} execution ended", jobId);
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
                if (!"COMPLETED".equals(state) && !"FAILED".equals(state) && !"CANCELLED".equals(state)) {
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
        messages = SearchPrompts.instance.buildAskPrompt(ctx, question);
        // Create an LLM instance for the planner model and route output to the ContextManager IO
        var llm = cm.getLlm(new Llm.Options(model, "Answer: " + question).withEcho());
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

        // construct TaskResult
        Objects.requireNonNull(stop);
        return new TaskResult(
                cm,
                "Ask: " + question,
                List.copyOf(cm.getIo().getLlmRawMessages()),
                ctx, // Ask never changes files; use current live context
                stop,
                meta);
    }

    /**
     * Generates a diff review using the planner model and returns the result as Markdown.
     *
     * @param ctx the current Context with workspace and diff
     * @param model the model to use for the review
     * @param diff the diff content to review
     * @return a TaskResult containing the review
     */
    private TaskResult reviewDiff(Context ctx, StreamingChatModel model, String diff) {
        var svc = cm.getService();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, Service.ModelConfig.from(model, svc));

        String fencedDiff = "```diff\nDIFF_START\n" + diff + "\nDIFF_END\n```";

        String prompt =
                """
                You are performing a Pull Request diff review. The diff to review is provided
                *between the fenced code block marked DIFF_START and DIFF_END*.
                Everything inside that block is code - do not ignore any part of it.

                %s

                IMPORTANT: Line Number Format
                -----------------------------
                Each diff line is annotated with explicit OLD/NEW line numbers for your reference:

                - Added lines:   "[OLD:- NEW:N] +<content>" where N is the exact line number in the new file
                - Removed lines: "[OLD:N NEW:-] -<content>" where N is the exact line number in the old file
                - Context lines: "[OLD:N NEW:N]  <content>" where N/N are the exact line numbers in the old/new files

                When writing your review, cite line numbers using just the number, choosing the appropriate number:
                - For additions ("+"): use the NEW line number from the annotation
                - For deletions ("-"): use the OLD line number from the annotation
                - For context/unchanged lines (" "): use the NEW line number from the annotation

                Your task:
                Analyze the diff content above using the context of related methods and code files.

                OUTPUT FORMAT
                -------------
                You MUST output a single JSON object with this exact structure:

                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\n[1-3 sentences describing what changed and only the most important risks]",
                  "comments": [
                    {
                      "path": "src/main/java/Example.java",
                      "line": 42,
                      "severity": "HIGH",
                      "bodyMarkdown": "Describe the issue, why it matters, and a minimal actionable fix."
                    }
                  ]
                }

                REQUIRED FIELDS:
                - "summaryMarkdown": MUST start with exactly "## Brokk PR Review" followed by a newline and 1-3 sentences.
                - "comments": Array of inline comment objects (MUST be [] if nothing meets threshold).

                Each comment object MUST have:
                - "path": File path relative to repository root (e.g., "src/main/java/Foo.java")
                - "line": Single integer line number from the diff annotation:
                  * For "+" lines: use the NEW line number
                  * For "-" lines: use the OLD line number
                  * For " " lines: use the NEW line number
                - "severity": One of "CRITICAL"|"HIGH"|"MEDIUM"|"LOW"
                - "bodyMarkdown": Markdown description of the issue with a minimal actionable fix

                SEVERITY DEFINITIONS:
                - CRITICAL: likely exploitable security issue, data loss/corruption, auth/permission bypass, remote crash, or severe production outage risk.
                - HIGH: likely bug, race condition, broken error handling, incorrect logic, resource leak, significant performance regression, or high-impact maintainability risk.
                - MEDIUM: could become a bug; edge-case correctness; non-trivial readability/maintenance concerns.
                - LOW: style, nits, subjective preference, minor readability, minor refactors.

                COMMENT POLICY (STRICT):
                - ONLY emit comments with severity >= HIGH.
                - MAX 5 comments total. Merge similar issues into one comment instead of repeating.
                - NO style-only/nit suggestions. NO repetitive variants of the same point.
                - SKIP correct code and skip minor improvements.
                - If nothing meets severity >= HIGH, "comments" MUST be [].

                OUTPUT ONLY THE JSON OBJECT. Do not include any text before or after the JSON.
                """
                        .formatted(fencedDiff);

        List<ChatMessage> messages = List.of(new UserMessage(prompt));

        var llm = cm.getLlm(new Llm.Options(model, "Diff Review").withEcho());
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
        return new TaskResult(cm, "Diff Review", List.copyOf(cm.getIo().getLlmRawMessages()), ctx, stop, meta);
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

    static void runFixRetryLoop(
            String jobId,
            JobStore store,
            IConsoleIO io,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxAttempts,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner) {
        if (maxAttempts < 1) {
            throw new IssueExecutionException("maxIssueFixAttempts must be >= 1");
        }

        String testCmd = buildDetailsOverride.testAllCommand();
        String lintCmd = buildDetailsOverride.buildLintCommand();

        boolean testsSkipped = testCmd.isBlank();
        boolean lintSkipped = lintCmd.isBlank();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String startMsg = "Pre-PR gate attempt %d/%d: tests=%s, lint=%s"
                    .formatted(attempt, maxAttempts, testsSkipped ? "SKIP" : "RUN", lintSkipped ? "SKIP" : "RUN");
            try {
                io.showNotification(IConsoleIO.NotificationRole.INFO, startMsg);
            } catch (Throwable ignore) {
                // best-effort only
            }
            try {
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", startMsg));
            } catch (IOException ioe) {
                logger.warn(
                        "Failed to append pre-PR gate start notification event for job {}: {}",
                        jobId,
                        ioe.getMessage(),
                        ioe);
            }

            String testOut = testsSkipped ? "" : commandRunner.apply(testCmd);
            String lintOut = lintSkipped ? "" : commandRunner.apply(lintCmd);

            boolean testsPassed = testsSkipped || testOut.isBlank();
            boolean lintPassed = lintSkipped || lintOut.isBlank();

            var resultMsg = "Pre-PR gate attempt " + attempt + "/" + maxAttempts + " results: tests="
                    + (testsSkipped ? "SKIP" : (testsPassed ? "PASS" : "FAIL")) + ", lint="
                    + (lintSkipped ? "SKIP" : (lintPassed ? "PASS" : "FAIL"));
            try {
                io.showNotification(IConsoleIO.NotificationRole.INFO, resultMsg);
            } catch (Throwable ignore) {
                // best-effort only
            }
            try {
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", resultMsg));
            } catch (IOException ioe) {
                logger.warn(
                        "Failed to append pre-PR gate results notification event for job {}: {}",
                        jobId,
                        ioe.getMessage(),
                        ioe);
            }

            if (testsPassed && lintPassed) {
                return;
            }

            if (attempt == maxAttempts) {
                var failureParts = new java.util.ArrayList<String>();
                if (!testsPassed) {
                    failureParts.add("Tests failed (" + testCmd + "):\n" + testOut);
                }
                if (!lintPassed) {
                    failureParts.add("Lint failed (" + lintCmd + "):\n" + lintOut);
                }

                String failedDetails =
                        failureParts.isEmpty() ? "Unknown pre-PR gate failure" : String.join("\n\n", failureParts);

                throw new IssueExecutionException(
                        "Pre-PR gate failed after " + maxAttempts + " attempt(s):\n\n" + failedDetails);
            }

            var fixParts = new java.util.ArrayList<String>();
            if (!testsPassed) {
                fixParts.add("Tests failed when running:\n" + testCmd + "\n\nOutput:\n" + testOut);
            }
            if (!lintPassed) {
                fixParts.add("Lint failed when running:\n" + lintCmd + "\n\nOutput:\n" + lintOut);
            }

            String fixPrompt = fixParts.isEmpty() ? "Unknown pre-PR gate failure" : String.join("\n\n", fixParts);

            String fullFixPrompt = "Pre-PR gate failed (attempt " + attempt + "/" + maxAttempts + ").\n\n" + fixPrompt
                    + "\n\nPlease fix the issues so that BOTH full tests and full lint pass.";

            fixTaskRunner.accept(fullFixPrompt);
        }

        throw new IssueExecutionException("Pre-PR gate failed unexpectedly");
    }
}
