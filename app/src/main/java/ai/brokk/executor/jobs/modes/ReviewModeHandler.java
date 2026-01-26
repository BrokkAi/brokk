package ai.brokk.executor.jobs.modes;

import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.LutzAgent;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.PrReviewPromptBuilder;
import ai.brokk.executor.jobs.PrReviewService;
import ai.brokk.git.GitRepo;
import ai.brokk.prompts.SearchPrompts;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Handler for REVIEW mode: performs automated PR review with GitHub integration.
 */
public final class ReviewModeHandler {
    private static final Logger logger = LogManager.getLogger(ReviewModeHandler.class);

    public static final PrReviewService.Severity DEFAULT_REVIEW_SEVERITY_THRESHOLD = PrReviewService.Severity.HIGH;
    public static final int DEFAULT_REVIEW_MAX_INLINE_COMMENTS = 3;

    private ReviewModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var jobId = ctx.jobId();
        var spec = ctx.spec();
        var cm = ctx.cm();
        var store = ctx.store();
        var plannerModel = ctx.plannerModel();
        var scanModel = ctx.scanModel();

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

            var gitRepo = (GitRepo) cm.getProject().getRepo();
            String remoteName = gitRepo.remote().getOriginRemoteNameWithFallback();
            if (remoteName == null) {
                throw new IllegalStateException(
                        "PR review requires a configured git remote (no remote found; expected 'origin' or a fallback remote)");
            }

            try {
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", "Fetching PR refs from remote '" + remoteName + "'..."));
            } catch (IOException ioe) {
                logger.warn("Failed to append fetch notification event for job {}: {}", jobId, ioe.getMessage());
            }

            gitRepo.remote().fetchPrRef(prNumber, remoteName, githubToken);
            gitRepo.remote().fetchBranch(remoteName, baseBranch, githubToken);

            String baseRef = remoteName + "/" + baseBranch;
            String prRef = remoteName + "/pr/" + prNumber;

            // 4. Compute and annotate PR diff
            String diff = PrReviewService.computePrDiff(gitRepo, baseRef, prRef);
            String annotatedDiff = PrReviewService.annotateDiffWithLineNumbers(diff);

            // Pre-scan
            try {
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", "Brokk Context Engine: analyzing repository context for PR review..."));
                var scanGoal = "Analyzing changes in this PR diff to identify related code context:\n```diff\n" + annotatedDiff + "\n```";
                var searchAgent = new LutzAgent(
                        context,
                        scanGoal,
                        Objects.requireNonNull(scanModel, "scan model unavailable for REVIEW pre-scan"),
                        SearchPrompts.Objective.ANSWER_ONLY,
                        scope);
                context = searchAgent.scanContext();
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", "Brokk Context Engine: complete — contextual insights added to Workspace."));
            } catch (Exception ex) {
                logger.warn("Pre-scan failed for REVIEW job {}: {}", jobId, ex.getMessage());
            }

            // 5. Call LLM for review
            var planner = Objects.requireNonNull(plannerModel, "planner model unavailable for REVIEW jobs");
            TaskResult reviewResult = reviewDiff(cm, context, planner, annotatedDiff);
            scope.append(reviewResult);

            // 6. Parse and post review
            String reviewText = reviewResult.output().text().join();
            var reviewResponse = PrReviewService.parsePrReviewResponse(reviewText);

            if (reviewResponse == null) {
                throw new IllegalStateException("PR review response was not valid JSON. Response preview: " + (reviewText.length() > 500 ? reviewText.substring(0, 500) : reviewText));
            }

            PrReviewService.postReviewComment(pr, reviewResponse.summaryMarkdown());

            var filteredComments = PrReviewService.filterInlineComments(
                    reviewResponse.comments(),
                    DEFAULT_REVIEW_SEVERITY_THRESHOLD,
                    DEFAULT_REVIEW_MAX_INLINE_COMMENTS);

            int posted = 0;
            for (var comment : filteredComments) {
                if (!PrReviewService.hasExistingLineComment(pr, comment.path(), comment.line())) {
                    PrReviewService.postLineComment(pr, comment.path(), comment.line(), comment.bodyMarkdown(), headSha);
                    posted++;
                }
            }
            logger.info("PR Review complete for PR #{}: posted {} line comments", prNumber, posted);
        }
    }

    private static TaskResult reviewDiff(ai.brokk.ContextManager cm, ai.brokk.context.Context ctx, StreamingChatModel model, String diff) {
        var svc = cm.getService();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, Service.ModelConfig.from(model, svc));
        String prompt = PrReviewPromptBuilder.buildReviewPrompt(diff, DEFAULT_REVIEW_SEVERITY_THRESHOLD, DEFAULT_REVIEW_MAX_INLINE_COMMENTS);

        var llm = cm.getLlm(new Llm.Options(model, "Diff Review").withEcho());
        llm.setOutput(cm.getIo());

        try {
            var response = llm.sendRequest(List.of(new UserMessage(prompt)));
            return new TaskResult(cm, "Diff Review", List.copyOf(cm.getIo().getLlmRawMessages()), ctx, TaskResult.StopDetails.fromResponse(response), meta);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
