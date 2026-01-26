package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JobRunner.parseMode() covering PLAN mode and fallback behavior.
 */
class JobRunnerModeParsingTest {

    @Test
    void parseModeRecognizesPlanCaseInsensitive() {
        var specUpper = JobSpec.of(
                "Plan this work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ null,
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of("mode", "PLAN"),
                /* reasoningLevelCode= */ (String) null);
        assertEquals(JobRunner.Mode.PLAN, JobRunner.parseMode(specUpper));

        var specLower = JobSpec.of(
                "Plan this work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ null,
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of("mode", "plan"),
                /* reasoningLevelCode= */ (String) null);
        assertEquals(JobRunner.Mode.PLAN, JobRunner.parseMode(specLower));
    }

    @Test
    void parseModeBlankOrInvalidFallsBackToArchitect() {
        var specBlank = JobSpec.of(
                "Do work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ null,
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of("mode", ""),
                /* reasoningLevelCode= */ (String) null);
        assertEquals(JobRunner.Mode.ARCHITECT, JobRunner.parseMode(specBlank));

        var specInvalid = JobSpec.of(
                "Do work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ null,
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of("mode", "NOT_A_MODE"),
                /* reasoningLevelCode= */ (String) null);
        assertEquals(JobRunner.Mode.ARCHITECT, JobRunner.parseMode(specInvalid));
    }
}
