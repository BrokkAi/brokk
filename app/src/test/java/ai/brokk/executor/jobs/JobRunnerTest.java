package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobRunnerTest {
    @Test
    void testParseModeLegacyPrReviewFallsBackToArchitect() {
        var spec = JobSpec.of(
                "test task",
                false,
                false,
                "test-model",
                null,
                null,
                false,
                Map.of("mode", "pr_review"),
                null,
                null,
                null);

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.ARCHITECT, mode);
    }

    @Test
    void testParseModeDefaultsToArchitect() {
        var spec = JobSpec.of("test task", "test-model");

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.ARCHITECT, mode);
    }

    @Test
    void testParseModeCaseInsensitive_InvalidValueFallsBackToArchitect() {
        var spec = JobSpec.of(
                "test task",
                false,
                false,
                "test-model",
                null,
                null,
                false,
                Map.of("mode", "PR_REVIEW"),
                null,
                null,
                null);

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.ARCHITECT, mode);
    }

    @Test
    void testParseModeRecognizesReviewCaseInsensitive() {
        var spec = JobSpec.of(
                "test task", false, false, "test-model", null, null, false, Map.of("mode", "ReViEw"), null, null, null);

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.REVIEW, mode);
    }

    @Test
    void maybeAnnotateDiffBlocks_rewritesDiffFence_whenClosingFenceOnOwnLine() {
        String body = "Before\n```diff\n@@ -1,0 +1,1 @@\n+foo\n```\nAfter\n";
        String result = JobRunner.maybeAnnotateDiffBlocks(body);

        assertTrue(result.contains("[OLD:- NEW:1] +foo"));
        assertTrue(result.contains("```diff\n"));
        assertTrue(result.contains("\n```"));
    }

    @Test
    void maybeAnnotateDiffBlocks_rewritesDiffFence_whenClosingFenceImmediatelyFollowsLastLine() {
        String body = "Before\n```diff\n@@ -1,0 +1,1 @@\n+foo```" + "\nAfter\n";
        String result = JobRunner.maybeAnnotateDiffBlocks(body);

        assertTrue(result.contains("[OLD:- NEW:1] +foo"));
        assertTrue(result.contains("```diff\n"));
        assertTrue(result.contains("\n```"));
    }

    @Test
    void maybeAnnotateDiffBlocks_rewritesEmptyDiffFence() {
        String body = "Before\n```diff\n```\nAfter\n";
        String result = JobRunner.maybeAnnotateDiffBlocks(body);

        assertTrue(result.contains("```diff\n\n```"));
    }

    @Test
    void testReviewModeSeverityAndCap() {
        assertEquals(PrReviewService.Severity.HIGH, JobRunner.DEFAULT_REVIEW_SEVERITY_THRESHOLD);
        assertEquals(3, JobRunner.DEFAULT_REVIEW_MAX_INLINE_COMMENTS);
    }

    @Test
    void testReviewPromptPolicyIncludesPrTitleAndBody() {
        String diff = "dummy diff";
        String title = "Fix Bug #123";
        String body = "This PR fixes a bug in the code.";
        String prompt = JobRunner.buildReviewPrompt(diff, PrReviewService.Severity.HIGH, 3, title, body);

        assertTrue(prompt.contains("Title: " + title));
        assertTrue(prompt.contains("Description:\n" + body));
    }

    @Test
    void testReviewPromptPolicyHandlesBlankPrBody() {
        String diff = "dummy diff";
        String title = "Small fix";
        String prompt = JobRunner.buildReviewPrompt(diff, PrReviewService.Severity.HIGH, 3, title, "");

        assertTrue(prompt.contains("Title: " + title));
        assertTrue(prompt.contains("(no description)"));
    }

    @Test
    void testReviewPromptPolicyExcludesPrMetadataIfBothEmpty() {
        String diff = "dummy diff";
        String prompt = JobRunner.buildReviewPrompt(diff, PrReviewService.Severity.HIGH, 3, "", "");

        assertTrue(!prompt.contains("PR Metadata:"));
    }

    @Test
    void testReviewPromptPolicyIncludesMax3AndSeverityHigh() {
        String diff = "dummy diff";
        String prompt = JobRunner.buildReviewPrompt(diff, PrReviewService.Severity.HIGH, 3, "", "");
        assertTrue(prompt.contains("MAX 3 comments"), "Prompt should cap comments to MAX 3 comments");
        assertTrue(prompt.contains("severity >= HIGH"), "Prompt should require severity >= HIGH");
        // Ensure the diff block contains DIFF_START/DIFF_END around the provided diff content
        assertTrue(
                prompt.contains("DIFF_START\n" + diff + "\nDIFF_END"),
                "Prompt should include the provided diff between DIFF_START and DIFF_END");

        // Verify strict filtering criteria
        assertTrue(prompt.contains("EXCLUSIONS"), "Prompt should explicitly list EXCLUSIONS");
        assertTrue(prompt.contains("Anti-patterns"), "Prompt should explicitly list Anti-patterns");
        assertTrue(
                prompt.contains("Do NOT report \"hardcoded defaults\" or \"configuration constants\" as HIGH"),
                "Prompt should exclude hardcoded defaults from HIGH severity");
        assertTrue(
                prompt.contains("Do NOT report \"future refactoring opportunities\" as HIGH"),
                "Prompt should exclude future refactoring from HIGH severity");
        assertTrue(
                prompt.contains(
                        "Only report functional bugs, security issues, or critical performance flaws as HIGH or CRITICAL"),
                "Prompt should restrict HIGH/CRITICAL to functional/security/performance");
        assertTrue(
                prompt.contains("\"Maintainability\" issues alone should be considered MEDIUM or LOW"),
                "Prompt should categorize maintainability as MEDIUM or LOW");
    }
}
