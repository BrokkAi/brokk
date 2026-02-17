package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.IssueRewriterAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.issues.Comment;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.issues.IssueDetails;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.ImageUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHRepository;

/**
 * Orchestrates issue-related workflows for the headless executor.
 *
 * <p>This class extracts issue logic from JobRunner to enable code reuse
 * between ISSUE_DIAGNOSE and ISSUE (solve) modes.
 */
public final class IssueExecutor {
    private static final Logger logger = LogManager.getLogger(IssueExecutor.class);

    /** Maximum number of comments to include in the prompt (most recent N) */
    private static final int MAX_COMMENTS = 50;

    /** Maximum characters per individual comment body before truncation */
    private static final int MAX_COMMENT_BODY_CHARS = 2_000;

    /** Truncation marker appended when content is cut */
    private static final String TRUNCATION_MARKER = "\n\n...(truncated)";

    private final ContextManager cm;
    private final JobStore store;
    private final String jobId;
    private final BooleanSupplier isCancelled;
    private final @Nullable IConsoleIO console;

    public IssueExecutor(ContextManager cm, JobStore store, String jobId) {
        this(cm, store, jobId, () -> false, null);
    }

    public IssueExecutor(
            ContextManager cm,
            JobStore store,
            String jobId,
            BooleanSupplier isCancelled,
            @Nullable IConsoleIO console) {
        this.cm = cm;
        this.store = store;
        this.jobId = jobId;
        this.isCancelled = isCancelled;
        this.console = console;
    }

    /**
     * Run a caller-specified command (intended for ISSUE-mode gates, but reusable), stream output to the console, and
     * update the session's Build Results fragment.
     *
     * <p>Returns empty string on success (or when no command is configured), otherwise the raw combined error/output
     * text.
     */
    @Blocking
    public static String runBuildAndPushContext(
            IContextManager cm, String command, @Nullable BuildAgent.BuildDetails override)
            throws InterruptedException {
        var interrupted = new AtomicReference<InterruptedException>(null);
        var updated = cm.pushContext(ctx -> {
            try {
                return BuildAgent.runExplicitCommand(ctx, command, override);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(e);
                return ctx;
            }
        });
        var ie = interrupted.get();
        if (ie != null) {
            throw ie;
        }
        return updated.getBuildError();
    }

    /**
     * Prepared context for issue operations, containing all fetched data needed
     * for diagnosis or solving.
     */
    public record IssuePreparedContext(
            GitHubAuth auth, GHRepository ghRepo, IssueDetails details, String formattedPrompt, int issueNumber) {}

    /**
     * Prepares the issue context by fetching issue details and capturing images.
     *
     * @param spec the job specification
     * @return prepared context with all issue data
     * @throws IOException if GitHub API calls fail
     * @throws IllegalArgumentException if required tags are missing
     */
    public IssuePreparedContext prepareIssueContext(JobSpec spec) throws IOException {
        String githubToken = spec.getGithubToken();
        String repoOwner = spec.getRepoOwner();
        String repoName = spec.getRepoName();
        Integer issueNumber = spec.getIssueNumber();

        if (githubToken == null || githubToken.isBlank()) {
            throw new IssueExecutionException("Issue mode requires github_token in tags");
        }
        if (repoOwner == null || repoOwner.isBlank()) {
            throw new IssueExecutionException("Issue mode requires repo_owner in tags");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IssueExecutionException("Issue mode requires repo_name in tags");
        }
        if (issueNumber == null) {
            throw new IssueExecutionException("Issue mode requires issue_number in tags");
        }

        var auth = new GitHubAuth(repoOwner, repoName, null, githubToken);
        var ghRepo = auth.getGhRepository();
        var githubIssueService = new GitHubIssueService(cm.getProject(), auth);

        // loadDetails expects issue ID like "#123"
        var issueDetails = githubIssueService.loadDetails("#" + issueNumber);

        // Capture images from issue attachments into the context
        captureIssueImages(issueDetails.attachmentUrls(), auth, issueNumber.intValue());

        String diagnosePrompt = formatIssueDiagnosePrompt(issueDetails, issueNumber.intValue());

        return new IssuePreparedContext(auth, ghRepo, issueDetails, diagnosePrompt, issueNumber.intValue());
    }

    /**
     * Executes the ISSUE_DIAGNOSE workflow: analyzes issue and posts diagnosis comment.
     *
     * @param spec the job specification
     * @param model the LLM model to use for analysis
     * @throws IOException if GitHub API calls fail
     * @throws InterruptedException if the operation is interrupted
     */
    public void executeDiagnose(JobSpec spec, StreamingChatModel model) throws IOException, InterruptedException {
        var prepared = prepareIssueContext(spec);

        emitNotification("ISSUE_DIAGNOSE: analyzing issue #" + prepared.issueNumber());

        var context = cm.liveContext();
        var writerAgent = new IssueRewriterAgent(context, model, prepared.formattedPrompt());
        var analysisResult = writerAgent.execute();

        cm.pushContext(ctx -> analysisResult.context());

        String timestamp = Instant.now().toString();
        String diagnosisComment =
                """
                <!-- brokk:diagnosis:v1 timestamp="%s" -->

                ## Issue Analysis

                %s

                ---

                **Next steps:** Reply with `@BrokkBot solve` to proceed with the fix, or add comments to provide additional guidance.
                """
                        .formatted(timestamp, analysisResult.bodyMarkdown());

        IssueService.postIssueComment(prepared.ghRepo(), prepared.issueNumber(), diagnosisComment);

        logger.info("ISSUE_DIAGNOSE job {} posted diagnosis comment to issue #{}", jobId, prepared.issueNumber());

        emitNotification("ISSUE_DIAGNOSE: diagnosis posted to issue #" + prepared.issueNumber());
    }

    /**
     * Executes the ISSUE (solve) workflow: analyzes issue, creates branch, executes tasks, creates PR.
     *
     * @param spec the job specification
     * @param plannerModel the LLM model for planning
     * @param codeModel the LLM model for code generation
     * @throws IOException if GitHub API calls fail
     * @throws InterruptedException if the operation is interrupted
     */
    public void executeSolve(JobSpec spec, StreamingChatModel plannerModel, StreamingChatModel codeModel)
            throws IOException, InterruptedException {
        var prepared = prepareIssueContext(spec);
        String githubToken = Objects.requireNonNull(spec.getGithubToken());

        boolean hasPriorDiagnosis = issueHasDiagnosisMarker(prepared.details());

        emitNotification("ISSUE: analyzing issue #" + prepared.issueNumber());

        var context = cm.liveContext();
        var writerAgent = new IssueRewriterAgent(context, plannerModel, prepared.formattedPrompt());
        var analysisResult = writerAgent.execute();
        context = cm.pushContext(ctx -> analysisResult.context());

        if (!hasPriorDiagnosis) {
            String timestamp = Instant.now().toString();
            String diagnosisComment =
                    """
                    <!-- brokk:diagnosis:v1 timestamp="%s" -->

                    ## Issue Analysis

                    %s

                    ---

                    **Status:** Proceeding with automated fix. A pull request will be created shortly.
                    """
                            .formatted(timestamp, analysisResult.bodyMarkdown());

            IssueService.postIssueComment(prepared.ghRepo(), prepared.issueNumber(), diagnosisComment);

            logger.info("ISSUE job {} posted diagnosis comment to issue #{}", jobId, prepared.issueNumber());
            emitNotification("ISSUE: diagnosis posted to issue #" + prepared.issueNumber());
        } else {
            String statusComment =
                    """
                    **Status:** Starting automated fix based on previous analysis. A pull request will be created shortly.
                    """;

            IssueService.postIssueComment(prepared.ghRepo(), prepared.issueNumber(), statusComment);

            logger.info(
                    "ISSUE job {} posted status acknowledgment to issue #{} (prior diagnosis exists)",
                    jobId,
                    prepared.issueNumber());
            emitNotification("ISSUE: status acknowledgment posted to issue #" + prepared.issueNumber());
        }

        var buildDetailsOverride = JobRunner.resolveIssueBuildDetails(spec, cm.getProject());
        var gitRepo = (GitRepo) cm.getProject().getRepo();

        final String originalBranch;
        try {
            originalBranch = gitRepo.getCurrentBranch();
        } catch (GitAPIException e) {
            throw new IssueExecutionException("Failed to determine current branch: " + e.getMessage(), e);
        }

        final String issueBranchName;
        try {
            issueBranchName = IssueService.generateBranchNameWithRandomSuffix(prepared.issueNumber(), gitRepo);
        } catch (GitAPIException e) {
            throw new IssueExecutionException("Failed to generate branch name: " + e.getMessage(), e);
        }

        try {
            logger.info("ISSUE job {}: Creating branch {} from {}", jobId, issueBranchName, originalBranch);
            try {
                gitRepo.createAndCheckoutBranch(issueBranchName, originalBranch);
            } catch (GitAPIException e) {
                throw new IssueExecutionException("Failed to create and checkout branch: " + e.getMessage(), e);
            }

            String issueTaskPrompt = "Resolve GitHub Issue #%d: %s\n\n%s"
                    .formatted(prepared.issueNumber(), analysisResult.title(), analysisResult.bodyMarkdown());

            // Check if we should enrich the prompt (brief issue body)
            String issueBody = prepared.details().markdownBody();
            if (IssueRewriterAgent.shouldEnrichIssuePrompt(issueBody)) {
                try {
                    emitNotification("Issue body is brief; performing prompt enrichment...");

                    var writerService = new IssueRewriterAgent(context, plannerModel, issueTaskPrompt);
                    var enriched = writerService.execute();

                    issueTaskPrompt = "Resolve GitHub Issue #%d: %s\n\n%s"
                            .formatted(prepared.issueNumber(), enriched.title(), enriched.bodyMarkdown());

                    context = cm.pushContext(ctx -> enriched.context());

                    logger.info("ISSUE job {}: prompt enrichment successful", jobId);
                } catch (Exception e) {
                    logger.warn("ISSUE job {}: prompt enrichment failed", jobId, e);
                }
            }

            // Execute Lutz-style task planning and iteration
            String taskDescription = "Issue #" + prepared.issueNumber() + ": "
                    + prepared.details().header().title();
            try (var scope = cm.beginTask(issueTaskPrompt, true, taskDescription)) {
                var scanConfig = new SearchAgent.ScanConfig(true, null, true, false);
                var searchAgent = new SearchAgent(
                        context,
                        issueTaskPrompt,
                        plannerModel,
                        SearchPrompts.Objective.TASKS_ONLY,
                        scope,
                        cm.getIo(),
                        scanConfig);
                var taskListResult = searchAgent.execute();
                scope.append(taskListResult);

                var generatedTasks = cm.getTaskList().tasks();
                var incompleteTasks =
                        generatedTasks.stream().filter(t -> !t.done()).toList();

                for (TaskList.TaskItem generatedTask : incompleteTasks) {
                    if (isCancelled.getAsBoolean()) return;

                    cm.executeTask(generatedTask, plannerModel, codeModel);

                    if (spec.skipVerification()) {
                        emitNotification("Per-task verification skipped due to skipVerification=true");
                        continue;
                    }

                    runPerTaskVerification(buildDetailsOverride, plannerModel, codeModel, generatedTask);
                }

                // Review-bot: compute diff vs default branch and generate inline comments
                String targetBranch = prepared.auth().getDefaultBranch();
                var inlineComments = JobRunner.issueModeComputeInlineComments(
                        jobId, store, gitRepo, context, plannerModel, githubToken, targetBranch, cm);
                logger.info("ISSUE job {} review-bot produced {} inline comment(s)", jobId, inlineComments.size());

                // Apply review-fix tasks
                if (inlineComments.isEmpty()) {
                    emitNotification("Review-bot: no inline comments to fix; skipping review-fix stage.");
                } else {
                    runReviewFixTasks(
                            spec,
                            buildDetailsOverride,
                            plannerModel,
                            codeModel,
                            inlineComments,
                            issueBranchName,
                            githubToken);
                }

                if (isCancelled.getAsBoolean()) {
                    logger.info("ISSUE job {} cancelled after final verification; skipping PR creation", jobId);
                    return;
                }

                // Create Pull Request (conditional)
                if (JobRunner.issueDeliveryEnabled(spec)) {
                    createPullRequest(prepared, issueBranchName, targetBranch, githubToken);
                } else {
                    emitNotification("PR creation skipped due to issue_delivery policy");
                }

                scope.compressTop();
            }
        } finally {
            boolean forceDelete = "always".equalsIgnoreCase(spec.tags().getOrDefault("issue_branch_cleanup", ""));
            JobRunner.cleanupIssueBranch(jobId, gitRepo, originalBranch, issueBranchName, forceDelete);
        }
    }

    private void runPerTaskVerification(
            BuildAgent.BuildDetails buildDetailsOverride,
            StreamingChatModel plannerModel,
            StreamingChatModel codeModel,
            TaskList.TaskItem generatedTask)
            throws InterruptedException {

        String output = verify(buildDetailsOverride);

        boolean passedFirst = output.isBlank();
        emitNotification("Verification: " + (passedFirst ? "PASS" : "FAIL"));
        if (passedFirst) {
            return;
        }

        String taskLabel = Objects.requireNonNullElse(generatedTask.text(), "(unnamed task)");
        String fixPrompt = "Verification failed for task: " + taskLabel + "\n\nOutput:\n" + output
                + "\n\nPlease make a single fix attempt to resolve this verification failure.";
        var fixTask = TaskList.TaskItem.createFixTask(fixPrompt);
        try {
            cm.executeTask(fixTask, plannerModel, codeModel);
        } catch (Exception e) {
            logger.warn("Fix attempt failed for job {} task {}: {}", jobId, taskLabel, e.getMessage());
        }

        String outputAfterFix = verify(buildDetailsOverride);
        boolean passedSecond = outputAfterFix.isBlank();
        emitNotification("Verification after fix: " + (passedSecond ? "PASS" : "FAIL"));

        if (!passedSecond) {
            throw new IssueExecutionException("Verification failed after single fix attempt:\n\n" + outputAfterFix);
        }
    }

    private String verify(BuildAgent.BuildDetails buildDetailsOverride) throws InterruptedException {
        return Objects.requireNonNullElse(BuildAgent.runVerification(cm, buildDetailsOverride), "");
    }

    private void runReviewFixTasks(
            JobSpec spec,
            BuildAgent.BuildDetails buildDetailsOverride,
            StreamingChatModel plannerModel,
            StreamingChatModel codeModel,
            List<PrReviewService.InlineComment> inlineComments,
            String issueBranchName,
            String githubToken) {
        var total = inlineComments.size();
        var taskIndex = new AtomicInteger(0);
        var lastTaskDescription = new AtomicReference<String>("");

        Consumer<PrReviewService.InlineComment> reviewFixTaskRunner = comment -> {
            int idx = taskIndex.incrementAndGet();

            String path = Objects.requireNonNullElse(comment.path(), "");
            int line = comment.line();

            String reviewFixTaskDescription = "Review-fix " + idx + "/" + total + ": " + path + ":" + line;
            lastTaskDescription.set(reviewFixTaskDescription);

            String prompt = JobRunner.buildInlineCommentFixPrompt(comment);

            try {
                try (var reviewFixScope = cm.beginTaskUngrouped(reviewFixTaskDescription)) {
                    var liveCtx = cm.liveContext();
                    var reviewFixAgent = new SearchAgent(
                            liveCtx,
                            reviewFixTaskDescription,
                            plannerModel,
                            SearchPrompts.Objective.LUTZ,
                            reviewFixScope);

                    try {
                        reviewFixAgent.callCodeAgent(prompt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            } catch (RuntimeException re) {
                if (re.getCause() instanceof InterruptedException) {
                    throw re;
                }
                logger.warn(
                        "ISSUE job {} review-fix task {}/{} failed for {}:{}: {}",
                        jobId,
                        idx,
                        total,
                        path,
                        line,
                        re.getMessage(),
                        re);
                throw re;
            }
        };

        Runnable branchUpdateHook = () -> {
            int idx = taskIndex.get();
            if (idx <= 0 || isCancelled.getAsBoolean()) {
                return;
            }

            String reviewFixTaskDescription = Objects.requireNonNull(lastTaskDescription.get());

            try {
                new GitWorkflow(cm).performAutoCommit(reviewFixTaskDescription);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            } catch (Exception e) {
                logger.warn(
                        "ISSUE job {} review-fix auto-commit fallback failed for task {}/{}: {}",
                        jobId,
                        idx,
                        total,
                        e.getMessage(),
                        e);
            }

            try {
                String pushMsg = new GitWorkflow(cm).push(issueBranchName, githubToken);
                emitNotification("Review-fix push succeeded: " + pushMsg);
            } catch (Exception e) {
                logger.warn(
                        "ISSUE job {} review-fix push failed for task {}/{}: {}", jobId, idx, total, e.getMessage(), e);
                emitNotification("Review-fix push failed (continuing): "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        };

        IConsoleIO io = console != null ? console : cm.getIo();
        JobRunner.runIssueReviewFixAttemptsWithCommandResultEvents(
                jobId, store, io, isCancelled, inlineComments, reviewFixTaskRunner, branchUpdateHook);

        if (isCancelled.getAsBoolean()) {
            logger.info("ISSUE job {} cancelled after review-fix; skipping final verification", jobId);
            return;
        }

        // Final verification pass
        Function<String, String> commandRunner = cmd -> {
            try {
                return runBuildAndPushContext(cm, cmd, buildDetailsOverride);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        };

        JobRunner.runIssueModeTestLintRetryLoop(
                jobId,
                store,
                io,
                isCancelled,
                (attempt, message) -> emitNotification(message),
                commandRunner,
                out -> {
                    String prompt = "fix this build error:\n" + out;
                    try {
                        cm.executeTask(TaskList.TaskItem.createFixTask(prompt), plannerModel, codeModel);
                    } catch (Exception e) {
                        logger.warn("Final fix attempt failed for job {}: {}", jobId, e.getMessage());
                    }
                },
                buildDetailsOverride,
                spec.effectiveMaxIssueFixAttempts());
    }

    private void createPullRequest(
            IssuePreparedContext prepared, String issueBranchName, String targetBranch, String githubToken) {
        try {
            var workflow = new GitWorkflow(cm);

            workflow.performAutoCommit("Resolves #" + prepared.issueNumber() + ": "
                    + prepared.details().header().title());

            var suggestion = workflow.suggestPullRequestDetails(issueBranchName, targetBranch, cm.getIo());

            String prBody = IssueService.buildPrDescription(suggestion.description(), prepared.issueNumber());

            var prUri =
                    workflow.createPullRequest(issueBranchName, targetBranch, suggestion.title(), prBody, githubToken);

            logger.info("ISSUE job {} created PR: {}", jobId, prUri);
            if (console != null) {
                console.showNotification(IConsoleIO.NotificationRole.INFO, "Created Pull Request: " + prUri);
            }
        } catch (Exception e) {
            logger.warn("ISSUE job {}: failed to create PR: {}", jobId, e.getMessage(), e);
            IConsoleIO io = console != null ? console : cm.getIo();
            try {
                io.toolError("Failed to create PR: " + e.getMessage(), "PR creation error");
            } catch (Throwable ignore) {
                // best-effort
            }
            emitNotification("Failed to create PR: " + e.getMessage());

            throw new IssueExecutionException("Failed to create PR: " + e.getMessage(), e);
        }
    }

    /**
     * Captures images from issue attachments into the context.
     */
    private void captureIssueImages(@Nullable List<URI> attachmentUrls, GitHubAuth auth, int issueNumber) {
        if (attachmentUrls == null || attachmentUrls.isEmpty()) {
            return;
        }

        try {
            var httpClient = auth.authenticatedClient();
            int capturedCount = ImageUtil.captureIssueImages(
                    attachmentUrls, httpClient, (image, description) -> cm.addPastedImageFragment(image, description));
            if (capturedCount > 0) {
                logger.info("Issue job {}: captured {} image(s) from issue #{}", jobId, capturedCount, issueNumber);
            }
        } catch (Exception e) {
            logger.warn(
                    "Issue job {}: failed to capture images from issue #{}: {}", jobId, issueNumber, e.getMessage(), e);
        }
    }

    /**
     * Formats issue details into a prompt for the LLM.
     * Used by both ISSUE_DIAGNOSE and ISSUE modes.
     */
    static String formatIssueDiagnosePrompt(IssueDetails details, int issueNumber) {
        var header = details.header();
        String title = header.title();

        String body = details.markdownBody();
        String safeBody = body.isBlank() ? "(No description provided)" : body;

        String issueHeader = "# GitHub Issue #" + issueNumber + (!title.isBlank() ? ": " + title : "");

        var out = new StringBuilder();
        out.append(issueHeader).append("\n\n");
        out.append("## Description\n\n");
        out.append(safeBody).append("\n\n");

        var comments = details.comments();
        if (!comments.isEmpty()) {
            out.append("## Comments\n\n");

            int totalComments = comments.size();
            List<Comment> includedComments;
            if (totalComments > MAX_COMMENTS) {
                out.append("*(").append(totalComments - MAX_COMMENTS).append(" earlier comments omitted)*\n\n");
                includedComments = comments.subList(totalComments - MAX_COMMENTS, totalComments);
            } else {
                includedComments = comments;
            }

            for (var comment : includedComments) {
                String author = comment.author();
                var created = comment.created();
                String timestamp = created != null ? created.toString() : "unknown time";
                out.append("@").append(author).append(" (").append(timestamp).append("):\n");

                String commentBody = comment.markdownBody();
                if (!commentBody.isBlank()) {
                    if (commentBody.length() > MAX_COMMENT_BODY_CHARS) {
                        out.append(commentBody, 0, MAX_COMMENT_BODY_CHARS);
                        out.append(TRUNCATION_MARKER);
                    } else {
                        out.append(commentBody);
                    }
                }
                out.append("\n\n");
            }
        }

        var attachments = details.attachmentUrls();
        if (!attachments.isEmpty()) {
            out.append("## Attached Images\n\n");
            for (var uri : attachments) {
                out.append("- ").append(uri).append("\n");
            }
            out.append("\n");
        }

        return out.toString();
    }

    static boolean issueHasDiagnosisMarker(IssueDetails details) {
        var comments = details.comments();
        if (comments.isEmpty()) {
            return false;
        }
        return comments.stream().map(Comment::markdownBody).anyMatch(body -> body.contains("<!-- brokk:diagnosis:v1"));
    }

    private void emitNotification(String message) {
        try {
            store.appendEvent(jobId, JobEvent.of("NOTIFICATION", message));
        } catch (IOException ioe) {
            logger.warn("Failed to append notification for job {}: {}", jobId, ioe.getMessage(), ioe);
        }
    }
}
