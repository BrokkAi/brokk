package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.agents.IssueRewriterAgent;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.issues.IssueDetails;
import ai.brokk.util.ImageUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Orchestrates issue-related workflows for the headless executor.
 *
 * <p>This class extracts issue logic from JobRunner to enable code reuse
 * between ISSUE_DIAGNOSE and ISSUE (solve) modes.
 */
public final class IssueExecutor {
    private static final Logger logger = LogManager.getLogger(IssueExecutor.class);

    private final ContextManager cm;
    private final JobStore store;
    private final String jobId;

    public IssueExecutor(ContextManager cm, JobStore store, String jobId) {
        this.cm = cm;
        this.store = store;
        this.jobId = jobId;
    }

    /**
     * Prepared context for issue operations, containing all fetched data needed
     * for diagnosis or solving.
     */
    public record IssuePreparedContext(
            GitHubAuth auth,
            GitHubIssueService issueService,
            IssueDetails details,
            String formattedPrompt,
            int issueNumber) {}

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
            throw new IllegalArgumentException("Issue mode requires github_token in tags");
        }
        if (repoOwner == null || repoOwner.isBlank()) {
            throw new IllegalArgumentException("Issue mode requires repo_owner in tags");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("Issue mode requires repo_name in tags");
        }
        if (issueNumber == null) {
            throw new IllegalArgumentException("Issue mode requires issue_number in tags");
        }

        var auth = new GitHubAuth(repoOwner, repoName, null, githubToken);
        var githubIssueService = new GitHubIssueService(cm.getProject(), auth);

        // loadDetails expects issue ID like "#123"
        var issueDetails = githubIssueService.loadDetails("#" + issueNumber);

        // Capture images from issue attachments into the context
        captureIssueImages(issueDetails.attachmentUrls(), auth, issueNumber.intValue());

        String diagnosePrompt = formatIssueDiagnosePrompt(issueDetails, issueNumber.intValue());

        return new IssuePreparedContext(auth, githubIssueService, issueDetails, diagnosePrompt, issueNumber.intValue());
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

        var ghRepo = prepared.auth().getGhRepository();
        IssueService.postIssueComment(ghRepo, prepared.issueNumber(), diagnosisComment);

        logger.info("ISSUE_DIAGNOSE job {} posted diagnosis comment to issue #{}", jobId, prepared.issueNumber());

        emitNotification("ISSUE_DIAGNOSE: diagnosis posted to issue #" + prepared.issueNumber());
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
     * Formats issue details into a diagnosis prompt for the LLM.
     */
    static String formatIssueDiagnosePrompt(IssueDetails details, int issueNumber) {
        var header = details.header();
        String title = header != null ? header.title() : null;

        String body = details.markdownBody();
        String safeBody = (body != null && !body.isBlank()) ? body : "(No description provided)";

        var sb = new StringJoiner("\n");

        String issueHeader =
                "# GitHub Issue #" + issueNumber + ((title != null && !title.isBlank()) ? ": " + title : "");
        sb.add(issueHeader);
        sb.add("");
        sb.add("## Description");
        sb.add("");
        sb.add(safeBody);
        sb.add("");

        var comments = details.comments();
        if (comments != null && !comments.isEmpty()) {
            sb.add("## Comments");
            sb.add("");
            for (var comment : comments) {
                String author = comment.author();
                var created = comment.created();
                String timestamp = created != null ? created.toString() : "unknown time";
                sb.add("### @" + (author != null ? author : "unknown") + " (" + timestamp + "):");
                String commentBody = comment.markdownBody();
                sb.add(commentBody != null ? commentBody : "");
                sb.add("");
            }
        }

        var attachments = details.attachmentUrls();
        if (attachments != null && !attachments.isEmpty()) {
            sb.add("## Attached Images");
            sb.add("");
            for (var uri : attachments) {
                sb.add("- " + uri);
            }
            sb.add("");
        }

        return sb.toString();
    }

    private void emitNotification(String message) {
        try {
            store.appendEvent(jobId, JobEvent.of("NOTIFICATION", message));
        } catch (IOException ioe) {
            logger.warn("Failed to append notification for job {}: {}", jobId, ioe.getMessage(), ioe);
        }
    }
}
