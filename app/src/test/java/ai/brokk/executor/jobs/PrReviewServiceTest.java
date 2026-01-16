package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.jobs.PrReviewService.PrDetails;
import ai.brokk.executor.jobs.PrReviewService.Severity;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitTestCleanupUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void testComputePrDiff_ThrowsWhenNoMergeBase(@TempDir Path tempDir) throws Exception {
        Git git = null;
        GitRepo repo = null;

        try {
            // Initialize git repository
            git = Git.init().setDirectory(tempDir.toFile()).call();

            // Configure user for commits
            git.getRepository().getConfig().setString("user", null, "name", "Test User");
            git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            git.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
            git.getRepository().getConfig().save();

            // Create initial commit on master branch
            Path initialFile = tempDir.resolve("initial.txt");
            Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("initial.txt").call();
            git.commit().setMessage("Initial commit on master").setSign(false).call();

            // Create orphan branch with no common history
            git.checkout().setOrphan(true).setName("orphan-branch").call();

            // Create a file on the orphan branch
            Path orphanFile = tempDir.resolve("orphan.txt");
            Files.writeString(orphanFile, "orphan content\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("orphan.txt").call();
            git.commit().setMessage("Orphan commit").setSign(false).call();

            // Create GitRepo instance
            repo = new GitRepo(tempDir);

            // Assert precondition: no merge-base exists between master and orphan-branch
            assertNull(
                    repo.getMergeBase("master", "orphan-branch"),
                    "Should have no merge-base between unrelated branches");

            // Test that computePrDiff throws IllegalStateException
            final GitRepo repoForLambda = repo;
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> PrReviewService.computePrDiff(repoForLambda, "master", "orphan-branch"),
                    "Should throw IllegalStateException when no merge-base exists");

            // Verify exception message contains expected content
            String message = exception.getMessage();
            assertTrue(message.contains("No merge-base found"), "Message should contain 'No merge-base found'");
            assertTrue(message.contains("base branch 'master'"), "Message should contain 'base branch 'master''");
            assertTrue(
                    message.contains("head ref 'orphan-branch'"), "Message should contain 'head ref 'orphan-branch''");

        } finally {
            GitTestCleanupUtil.cleanupGitResources(repo, git);
        }
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
        //    a. Resolve a single remote name via gitRepo.remote().getOriginRemoteNameWithFallback()
        //       (prefer "origin", otherwise fall back to the target remote).
        //    b. Emit a notification "Fetching PR refs from remote '<remoteName>'..."
        //    c. Call gitRepo.remote().fetchPrRef(prNumber, remoteName), which stores into:
        //       refs/remotes/<remoteName>/pr/<N>
        //    d. Call gitRepo.remote().fetchBranch(remoteName, baseBranch), which stores into:
        //       refs/remotes/<remoteName>/<baseBranch>
        // 2. The diff computation should use refs guaranteed to exist locally after fetch:
        //    baseRef = "<remoteName>/<baseBranch>"
        //    prRef   = "<remoteName>/pr/<N>"
    }

    @Test
    void testParsePrReviewResponse_ValidJsonWithComments() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nThis PR adds a new feature.",
                  "comments": [
                    {
                      "path": "src/main/java/Example.java",
                      "line": 42,
                      "severity": "HIGH",
                      "bodyMarkdown": "Potential null pointer issue here."
                    },
                    {
                      "path": "src/test/java/ExampleTest.java",
                      "line": 10,
                      "severity": "MEDIUM",
                      "bodyMarkdown": "Missing test case for edge condition."
                    }
                  ]
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertEquals("## Brokk PR Review\n\nThis PR adds a new feature.", response.summaryMarkdown());
        assertEquals(2, response.comments().size());

        var comment1 = response.comments().get(0);
        assertEquals("src/main/java/Example.java", comment1.path());
        assertEquals(42, comment1.line());
        assertEquals("Potential null pointer issue here.", comment1.bodyMarkdown());
        assertEquals(Severity.HIGH, comment1.severity());

        var comment2 = response.comments().get(1);
        assertEquals("src/test/java/ExampleTest.java", comment2.path());
        assertEquals(10, comment2.line());
        assertEquals("Missing test case for edge condition.", comment2.bodyMarkdown());
        assertEquals(Severity.MEDIUM, comment2.severity());
    }

    @Test
    void testParsePrReviewResponse_ValidJsonWithEmptyComments() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nNo issues found in this PR.",
                  "comments": []
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertEquals("## Brokk PR Review\n\nNo issues found in this PR.", response.summaryMarkdown());
        assertTrue(response.comments().isEmpty());
    }

    @Test
    void testParsePrReviewResponse_ValidJsonMissingCommentsField() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nClean code, no comments needed."
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertEquals("## Brokk PR Review\n\nClean code, no comments needed.", response.summaryMarkdown());
        assertTrue(response.comments().isEmpty());
    }

    @Test
    void testParsePrReviewResponse_MalformedJson() {
        String malformed = "{ this is not valid json }";

        var response = PrReviewService.parsePrReviewResponse(malformed);

        assertNull(response);
    }

    @Test
    void testParsePrReviewResponse_MissingSummaryField() {
        String json = """
                {
                  "comments": []
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertNull(response);
    }

    @Test
    void testParsePrReviewResponse_WrappedJson() {
        String wrapped =
                """
                Here is the review output:

                ```json
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nLooks good overall.",
                  "comments": [
                    {
                      "path": "src/Foo.java",
                      "line": 5,
                      "severity": "LOW",
                      "bodyMarkdown": "Consider using Optional here."
                    }
                  ]
                }
                ```

                End of review.
                """;

        var response = PrReviewService.parsePrReviewResponse(wrapped);

        assertEquals("## Brokk PR Review\n\nLooks good overall.", response.summaryMarkdown());
        assertEquals(1, response.comments().size());
        assertEquals("src/Foo.java", response.comments().get(0).path());
        assertEquals(5, response.comments().get(0).line());
        assertEquals(Severity.LOW, response.comments().get(0).severity());
    }

    @Test
    void testParsePrReviewResponse_EmptyInput() {
        assertNull(PrReviewService.parsePrReviewResponse(""));
        assertNull(PrReviewService.parsePrReviewResponse("   "));
        assertNull(PrReviewService.parsePrReviewResponse(null));
    }

    @Test
    void testParsePrReviewResponse_InvalidCommentsType() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nSummary here.",
                  "comments": "not an array"
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertNull(response);
    }

    @Test
    void testParsePrReviewResponse_SeverityMissingDefaultsToLow() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nSummary here.",
                  "comments": [
                    {
                      "path": "src/Foo.java",
                      "line": 10,
                      "bodyMarkdown": "Some issue."
                    }
                  ]
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertEquals("## Brokk PR Review\n\nSummary here.", response.summaryMarkdown());
        assertEquals(1, response.comments().size());
        assertEquals(Severity.LOW, response.comments().get(0).severity());
    }

    @Test
    void testParsePrReviewResponse_UnknownSeverityDefaultsToLow() {
        String json =
                """
                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\nSummary here.",
                  "comments": [
                    {
                      "path": "src/Foo.java",
                      "line": 10,
                      "severity": "SEVERE",
                      "bodyMarkdown": "Some issue."
                    }
                  ]
                }
                """;

        var response = PrReviewService.parsePrReviewResponse(json);

        assertEquals("## Brokk PR Review\n\nSummary here.", response.summaryMarkdown());
        assertEquals(1, response.comments().size());
        assertEquals(Severity.LOW, response.comments().get(0).severity());
    }

    @Test
    void testFilterInlineComments_AppliesThresholdSortAndCap() {
        var comments = List.of(
                new PrReviewService.InlineComment("a", 1, "low", Severity.LOW),
                new PrReviewService.InlineComment("b", 2, "medium", Severity.MEDIUM),
                new PrReviewService.InlineComment("c", 3, "high", Severity.HIGH),
                new PrReviewService.InlineComment("d", 4, "critical", Severity.CRITICAL),
                new PrReviewService.InlineComment("e", 5, "high2", Severity.HIGH));

        var filtered = PrReviewService.filterInlineComments(comments, Severity.HIGH, 2);

        assertEquals(2, filtered.size());
        assertEquals(Severity.CRITICAL, filtered.get(0).severity());
        assertEquals(Severity.HIGH, filtered.get(1).severity());
    }

    @Test
    void testFilterInlineComments_DeduplicatesBeforeCap() {
        var duplicateHigh1 = new PrReviewService.InlineComment("a", 1, "dup", Severity.HIGH);
        var duplicateHigh2 = new PrReviewService.InlineComment("a", 1, "dup", Severity.HIGH);
        var distinctCritical = new PrReviewService.InlineComment("b", 2, "distinct critical", Severity.CRITICAL);

        var comments = List.of(duplicateHigh1, duplicateHigh2, distinctCritical);

        var filtered = PrReviewService.filterInlineComments(comments, Severity.HIGH, 2);

        assertEquals(2, filtered.size());
        assertEquals(Severity.CRITICAL, filtered.get(0).severity());
        assertEquals("b", filtered.get(0).path());
        assertEquals(2, filtered.get(0).line());
        assertEquals("distinct critical", filtered.get(0).bodyMarkdown());

        assertEquals(Severity.HIGH, filtered.get(1).severity());
        assertEquals("a", filtered.get(1).path());
        assertEquals(1, filtered.get(1).line());
        assertEquals("dup", filtered.get(1).bodyMarkdown());
    }

    @Test
    void testSeverityRanking_IsStableAndMatchesSemantics() {
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.HIGH));
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.MEDIUM));
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.LOW));

        assertTrue(Severity.HIGH.isAtLeast(Severity.HIGH));
        assertTrue(Severity.HIGH.isAtLeast(Severity.MEDIUM));
        assertTrue(Severity.HIGH.isAtLeast(Severity.LOW));
        assertTrue(Severity.MEDIUM.isAtLeast(Severity.LOW));

        assertTrue(Severity.LOW.isAtLeast(Severity.LOW));
        assertTrue(!Severity.LOW.isAtLeast(Severity.MEDIUM));
        assertTrue(!Severity.MEDIUM.isAtLeast(Severity.HIGH));
        assertTrue(!Severity.HIGH.isAtLeast(Severity.CRITICAL));

        assertTrue(Severity.CRITICAL.rank() < Severity.HIGH.rank());
        assertTrue(Severity.HIGH.rank() < Severity.MEDIUM.rank());
        assertTrue(Severity.MEDIUM.rank() < Severity.LOW.rank());
    }

    @Test
    void testPrReviewResponse_RecordImmutability() {
        var comment = new PrReviewService.InlineComment("src/Foo.java", 10, "Issue here", Severity.HIGH);
        var response = new PrReviewService.PrReviewResponse("## Summary", List.of(comment));

        assertEquals(1, response.comments().size());
        assertEquals("src/Foo.java", response.comments().get(0).path());
    }

    @Test
    void testPrReviewResponse_NullCommentsList() {
        var response = new PrReviewService.PrReviewResponse("## Summary", null);

        assertTrue(response.comments().isEmpty());
    }

    @Test
    void testPrReviewResponse_ConvenienceConstructor() {
        var response = new PrReviewService.PrReviewResponse("## Summary only");

        assertEquals("## Summary only", response.summaryMarkdown());
        assertTrue(response.comments().isEmpty());
    }
}
