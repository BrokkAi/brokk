package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobRunnerTest {
    @Test
    void testParseModeRecognizesPrReview() {
        var spec = JobSpec.of(
                "test task", false, false, "test-model", null, null, false, Map.of("mode", "pr_review"), null, null);

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.PR_REVIEW, mode);
    }

    @Test
    void testParseModeDefaultsToArchitect() {
        var spec = JobSpec.of("test task", "test-model");

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.ARCHITECT, mode);
    }

    @Test
    void testParseModeCaseInsensitive() {
        var spec = JobSpec.of(
                "test task", false, false, "test-model", null, null, false, Map.of("mode", "PR_REVIEW"), null, null);

        var mode = JobRunner.parseMode(spec);
        assertEquals(JobRunner.Mode.PR_REVIEW, mode);
    }
}
