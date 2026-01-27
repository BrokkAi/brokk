package ai.brokk.executor.jobs.modes;

import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.executor.jobs.IssueExecutionException;
import ai.brokk.executor.jobs.IssueModeSupport;
import ai.brokk.executor.jobs.IssueService;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.PrReviewService;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handler for ISSUE mode.
 */
public final class IssueModeHandler {
    private static final Logger logger = LogManager.getLogger(IssueModeHandler.class);

    private IssueModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var jobId = ctx.jobId();
        var spec = ctx.spec();
        var cm = ctx.cm();
        var store = ctx.store();
        var console = ctx.io();
        var cancelled = ctx.cancelled();
        var planner = Objects.requireNonNull(ctx.plannerModel(), "plannerModel required for ISSUE jobs");
        var codeModel = Objects.requireNonNull(ctx.codeModel(), "code model required for ISSUE jobs");

        String githubToken = spec.getGithubToken();
        String repoOwner = spec.getRepoOwner();
        String repoName = spec.getRepoName();
        Integer issueNumber = spec.getIssueNumber();

        if (githubToken == null
                || githubToken.isBlank()
                || repoOwner == null
                || repoOwner.isBlank()
                || repoName == null
                || repoName.isBlank()
                || issueNumber == null) {
            throw new IssueExecutionException(
                    "ISSUE requires github_token, repo_owner, repo_name, and issue_number in tags");
        }

        var gitHubAuth = new GitHubAuth(repoOwner, repoName, null, githubToken);
        var ghRepo = gitHubAuth.getGhRepository();
        var details = IssueService.fetchIssueDetails(ghRepo, issueNumber);
        var buildDetailsOverride = resolveIssueBuildDetails(spec, cm.getProject());
        var gitRepo = (GitRepo) cm.getProject().getRepo();
        String originalBranch = gitRepo.getCurrentBranch();
        String issueBranchName = IssueService.generateBranchNameWithRandomSuffix(issueNumber, gitRepo);

        try {
            gitRepo.createAndCheckoutBranch(issueBranchName, originalBranch);
            String issueTaskPrompt = "Resolve GitHub Issue #%d: %s\n\nIssue Body:\n%s"
                    .formatted(issueNumber, details.title(), details.body());

            if (IssueModeSupport.shouldEnrichIssuePrompt(details.body())) {
                try {
                    store.appendEvent(
                            jobId, JobEvent.of("NOTIFICATION", "Issue body is brief; performing prompt enrichment..."));
                    try (var scope = cm.beginTaskUngrouped("Prompt Enrichment")) {
                        var enrichmentResult = new LutzAgent(
                                        cm.liveContext(),
                                        issueTaskPrompt,
                                        planner,
                                        SearchPrompts.Objective.PROMPT_ENRICHMENT,
                                        scope)
                                .execute();
                        if (enrichmentResult.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                            issueTaskPrompt += "\n\nEnriched Context:\n"
                                    + enrichmentResult.output().text().join();
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Enrichment failed", e);
                }
            }

            try (var scope = cm.beginTask(issueTaskPrompt, true, "Issue #" + issueNumber + ": " + details.title())) {
                var searchResult = new LutzAgent(
                                cm.liveContext(), issueTaskPrompt, planner, SearchPrompts.Objective.TASKS_ONLY, scope)
                        .execute();
                scope.append(searchResult);

                for (TaskList.TaskItem task :
                        cm.getTaskList().tasks().stream().filter(t -> !t.done()).toList()) {
                    if (cancelled.getAsBoolean()) return;
                    cm.executeTask(task, planner, codeModel);

                    IssueModeSupport.runSingleFixVerificationGate(
                            jobId,
                            store,
                            console,
                            BuildAgent.determineVerificationCommand(cm.liveContext(), buildDetailsOverride),
                            () -> {
                                try {
                                    return BuildAgent.runVerification(cm, buildDetailsOverride);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            prompt -> {
                                try {
                                    cm.executeTask(
                                            TaskList.TaskItem.createFixTask(
                                                    "Verification failed for task: " + task.text() + "\n\n" + prompt),
                                            planner,
                                            codeModel);
                                } catch (Exception e) {
                                    logger.warn("Fix failed", e);
                                }
                            });
                }

                String targetBranch = gitHubAuth.getDefaultBranch();
                var inlineComments = fetchInlineComments(jobId, gitRepo, cm, planner, githubToken, targetBranch);

                if (inlineComments.isEmpty()) {
                    store.appendEvent(jobId, JobEvent.of("NOTIFICATION", "Review-bot: no inline comments to fix."));
                } else {
                    var taskIndex = new AtomicInteger(0);
                    final var lastTask = new AtomicReference<String>("Review-fix");

                    IssueModeSupport.runIssueReviewFixAttemptsWithCommandResultEvents(
                            jobId,
                            store,
                            console,
                            cancelled,
                            inlineComments,
                            comment -> {
                                int idx = taskIndex.incrementAndGet();
                                String desc = "Review-fix " + idx + "/" + inlineComments.size() + ": " + comment.path()
                                        + ":" + comment.line();
                                lastTask.set(desc);
                                try (var fixScope = cm.beginTaskUngrouped(desc)) {
                                    new LutzAgent(
                                                    cm.liveContext(),
                                                    desc,
                                                    planner,
                                                    SearchPrompts.Objective.LUTZ,
                                                    fixScope)
                                            .callCodeAgent(IssueModeSupport.buildInlineCommentFixPrompt(comment));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                }
                            },
                            () -> {
                                try {
                                    var workflow = new GitWorkflow(cm);
                                    workflow.performAutoCommit(
                                            Objects.requireNonNullElse(lastTask.get(), "Review-fix"));
                                    workflow.push(issueBranchName, githubToken);
                                } catch (Exception e) {
                                    logger.warn("Post-fix update failed", e);
                                }
                            });

                    if (cancelled.getAsBoolean()) return;

                    IssueModeSupport.runIssueModeTestLintRetryLoop(
                            jobId,
                            store,
                            console,
                            cancelled,
                            (attempt, msg) -> {
                                try {
                                    store.appendEvent(jobId, JobEvent.of("NOTIFICATION", msg));
                                    console.showNotification(IConsoleIO.NotificationRole.INFO, msg);
                                } catch (Throwable t) {
                                    logger.warn("Failed to emit retry loop notification", t);
                                }
                            },
                            cmd -> {
                                try {
                                    return BuildAgent.runExplicitCommand(cm, cmd, buildDetailsOverride);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            out -> {
                                try {
                                    cm.executeTask(
                                            TaskList.TaskItem.createFixTask("fix build error:\n" + out),
                                            planner,
                                            codeModel);
                                } catch (Exception e) {
                                    logger.warn("Final fix failed", e);
                                }
                            },
                            buildDetailsOverride,
                            spec.effectiveMaxIssueFixAttempts());
                }

                if (cancelled.getAsBoolean()) return;

                if (!"none".equalsIgnoreCase(spec.tags().getOrDefault("issue_delivery", ""))) {
                    var workflow = new GitWorkflow(cm);
                    workflow.performAutoCommit("Resolves #" + issueNumber + ": " + details.title());
                    var suggestion = workflow.suggestPullRequestDetails(issueBranchName, targetBranch, console);
                    var prUri = workflow.createPullRequest(
                            issueBranchName,
                            targetBranch,
                            suggestion.title(),
                            IssueService.buildPrDescription(suggestion.description(), issueNumber),
                            githubToken);
                    console.showNotification(IConsoleIO.NotificationRole.INFO, "Created PR: " + prUri);
                }
            }
        } finally {
            IssueModeSupport.cleanupIssueBranch(
                    jobId,
                    gitRepo,
                    originalBranch,
                    issueBranchName,
                    "always".equalsIgnoreCase(spec.tags().get("issue_branch_cleanup")));
        }
    }

    private static ai.brokk.agents.BuildAgent.BuildDetails resolveIssueBuildDetails(
            ai.brokk.executor.jobs.JobSpec spec, ai.brokk.project.IProject project) {
        String settings = spec.getBuildSettingsJson();
        return (settings != null && !settings.isBlank())
                ? IssueService.parseBuildSettings(settings)
                : project.awaitBuildDetails();
    }

    private static List<PrReviewService.InlineComment> fetchInlineComments(
            String jobId,
            GitRepo gitRepo,
            ai.brokk.ContextManager cm,
            StreamingChatModel model,
            String token,
            String target) {
        try {
            String remote = gitRepo.remote().getOriginRemoteNameWithFallback();
            if (remote != null) {
                gitRepo.remote().fetchBranch(remote, target, token);
            }
            String base = remote != null ? remote + "/" + target : target;
            String diff = PrReviewService.computePrDiff(gitRepo, base, "HEAD");
            if (diff.isBlank()) {
                return List.of();
            }

            String annotatedDiff = PrReviewService.annotateDiffWithLineNumbers(diff);
            String prompt = ai.brokk.executor.jobs.PrReviewPromptBuilder.buildReviewPrompt(
                    annotatedDiff, ai.brokk.executor.jobs.PrReviewService.Severity.HIGH, 10);

            try (var scope = cm.beginTaskUngrouped("Issue Review Discovery")) {
                var searchAgent =
                        new LutzAgent(cm.liveContext(), prompt, model, SearchPrompts.Objective.ANSWER_ONLY, scope);
                var result = searchAgent.execute();
                var review = PrReviewService.parsePrReviewResponse(
                        result.output().text().join());
                return review != null ? review.comments() : List.of();
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch inline comments for job {}: {}", jobId, e.getMessage());
            return List.of();
        }
    }
}
