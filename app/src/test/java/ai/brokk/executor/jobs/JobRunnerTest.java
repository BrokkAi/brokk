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
}
