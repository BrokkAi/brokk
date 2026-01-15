package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
