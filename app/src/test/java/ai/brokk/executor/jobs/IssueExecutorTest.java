package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.issues.Comment;
import ai.brokk.issues.IssueDetails;
import ai.brokk.issues.IssueHeader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueExecutorTest {

    @Test
    void testFormatIssueDiagnosePrompt_truncatesLongComments() {
        String longBody = "x".repeat(3000);
        var comment = new Comment("author1", longBody, Instant.now());
        var header = new IssueHeader("#1", "Test Issue", "creator", Instant.now(), List.of(), List.of(), "OPEN", null);
        var details = new IssueDetails(header, "Issue body", "<p>Issue body</p>", List.of(comment), List.of());

        String prompt = IssueExecutor.formatIssueDiagnosePrompt(details, 1);

        assertTrue(prompt.contains("...(truncated)"));
        assertFalse(prompt.contains("x".repeat(3000)));
        assertTrue(prompt.contains("x".repeat(2000)));
    }

    @Test
    void testFormatIssueDiagnosePrompt_limitsCommentCount() {
        var comments = new ArrayList<Comment>();
        for (int i = 0; i < 60; i++) {
            comments.add(new Comment("author" + i, "Comment body " + i, Instant.now()));
        }
        var header = new IssueHeader("#1", "Test Issue", "creator", Instant.now(), List.of(), List.of(), "OPEN", null);
        var details = new IssueDetails(header, "Issue body", "<p>Issue body</p>", comments, List.of());

        String prompt = IssueExecutor.formatIssueDiagnosePrompt(details, 1);

        assertTrue(prompt.contains("10 earlier comments omitted"));
        assertTrue(prompt.contains("author59"));
        assertFalse(prompt.contains("author0"));
    }
}
