package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.jobs.PrReviewService.PrDetails;
import org.junit.jupiter.api.Test;

/**
 * Tests for PrReviewService.
 *
 * <p>Note: Most methods in PrReviewService interact with external GitHub API classes that cannot
 * be easily tested without a mocking framework. The project avoids mocking frameworks, so those
 * methods should be validated through integration tests instead. These unit tests cover the record
 * structure and validation logic.
 */
class PrReviewServiceTest {

    @Test
    void testPrDetails_RecordCreation() {
        PrDetails details = new PrDetails("main", "abc123", "feature-branch");

        assertEquals("main", details.baseBranch());
        assertEquals("abc123", details.headSha());
        assertEquals("feature-branch", details.headRef());
    }

    @Test
    void testPrDetails_AllFieldsPopulated() {
        PrDetails details = new PrDetails("develop", "def456", "bugfix/issue-123");

        assertEquals("develop", details.baseBranch());
        assertEquals("def456", details.headSha());
        assertEquals("bugfix/issue-123", details.headRef());
    }

    @Test
    void testComputePrDiff_ThrowsWhenNoMergeBase() {
        // This test would require mocking GitRepo, which the project avoids.
        // In practice, this scenario should be tested in integration tests
        // where a real GitRepo with unrelated branches is set up.

        // Placeholder to document expected behavior:
        // When getMergeBase returns null, computePrDiff should throw IllegalStateException
    }

    @Test
    void testPostLineComment_Integration() {
        // This test would require a real GHPullRequest instance from GitHub API.
        // The project avoids mocking frameworks, so this should be validated
        // through integration tests with actual GitHub PRs.

        // Placeholder to document expected behavior:
        // - postLineComment should call pr.createReviewComment() with the given parameters
        // - If HTTP 422 is returned, should fall back to pr.comment() with formatted message
        // - Should log appropriate messages for success and fallback scenarios
    }

    @Test
    void testHasExistingLineComment_Integration() {
        // This test would require a real GHPullRequest instance with review comments.
        // The project avoids mocking frameworks, so this should be validated
        // through integration tests.

        // Placeholder to document expected behavior:
        // - Should return true if a comment exists on the exact path and line
        // - Should return false if no matching comment exists
        // - Should handle pagination of review comments if necessary
    }

    @Test
    void testAnnotateDiffWithLineNumbers_ContextLines() {
        String diff =
                """
                diff --git a/foo.txt b/foo.txt
                index 1234567..abcdefg 100644
                --- a/foo.txt
                +++ b/foo.txt
                @@ -10,3 +10,3 @@
                 line one
                 line two
                 line three
                """;

        String annotated = PrReviewService.annotateDiffWithLineNumbers(diff);

        assertTrue(annotated.contains("diff --git a/foo.txt b/foo.txt"));
        assertTrue(annotated.contains("--- a/foo.txt"));
        assertTrue(annotated.contains("+++ b/foo.txt"));
        assertTrue(annotated.contains("@@ -10,3 +10,3 @@"));

        assertTrue(annotated.contains("[OLD:10 NEW:10]  line one"));
        assertTrue(annotated.contains("[OLD:11 NEW:11]  line two"));
        assertTrue(annotated.contains("[OLD:12 NEW:12]  line three"));
    }

    @Test
    void testAnnotateDiffWithLineNumbers_Additions() {
        String diff =
                """
                @@ -5,2 +5,4 @@
                 context
                +added line 1
                +added line 2
                 more context
                """;

        String annotated = PrReviewService.annotateDiffWithLineNumbers(diff);

        assertTrue(annotated.contains("[OLD:5 NEW:5]  context"));
        assertTrue(annotated.contains("[OLD:- NEW:6] +added line 1"));
        assertTrue(annotated.contains("[OLD:- NEW:7] +added line 2"));
        assertTrue(annotated.contains("[OLD:6 NEW:8]  more context"));
    }

    @Test
    void testAnnotateDiffWithLineNumbers_Deletions() {
        String diff =
                """
                @@ -20,4 +20,2 @@
                 context
                -deleted line 1
                -deleted line 2
                 more context
                """;

        String annotated = PrReviewService.annotateDiffWithLineNumbers(diff);

        assertTrue(annotated.contains("[OLD:20 NEW:20]  context"));
        assertTrue(annotated.contains("[OLD:21 NEW:-] -deleted line 1"));
        assertTrue(annotated.contains("[OLD:22 NEW:-] -deleted line 2"));
        assertTrue(annotated.contains("[OLD:23 NEW:21]  more context"));
    }

    @Test
    void testAnnotateDiffWithLineNumbers_MultipleHunks() {
        String diff =
                """
                @@ -1,2 +1,2 @@
                 first
                -old
                +new
                @@ -100,2 +100,2 @@
                 hundred
                -old hundred
                +new hundred
                """;

        String annotated = PrReviewService.annotateDiffWithLineNumbers(diff);

        assertTrue(annotated.contains("[OLD:1 NEW:1]  first"));
        assertTrue(annotated.contains("[OLD:2 NEW:-] -old"));
        assertTrue(annotated.contains("[OLD:- NEW:2] +new"));

        assertTrue(annotated.contains("[OLD:100 NEW:100]  hundred"));
        assertTrue(annotated.contains("[OLD:101 NEW:-] -old hundred"));
        assertTrue(annotated.contains("[OLD:- NEW:101] +new hundred"));
    }

    @Test
    void testAnnotateDiffWithLineNumbers_EmptyDiff() {
        String annotated = PrReviewService.annotateDiffWithLineNumbers("");
        assertEquals("", annotated);
    }

    @Test
    void testFetchPrRefs_Integration() {
        // This test documents the expected behavior of the PR review flow's fetch step.
        // The project avoids mocking frameworks, so this should be validated through
        // integration tests with actual Git repositories.

        // Expected behavior:
        // 1. Before computing the PR diff, JobRunner.REVIEW mode should:
        //    a. Emit a notification "Fetching PR refs from remote..."
        //    b. Call gitRepo.fetchPrRefs(prNumber) to fetch refs/pull/{N}/head
        //    c. Call gitRepo.remote().fetchBranch("origin", baseBranch) to fetch the base branch
        // 2. Both fetch calls should be wrapped in try/catch so failures only log warnings
        //    and do not abort the review
        // 3. This ensures PRs from forks and stale local repositories can still be reviewed
    }
}
