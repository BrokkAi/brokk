package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying scan-model selection semantics for PLAN mode.
 *
 * These tests assert that:
 * - When JobSpec.scanModel is provided (non-blank), it is preferred.
 * - When JobSpec.scanModel is absent or blank, the project default is used.
 *
 * The tests exercise the package-private helper chooseScanModelNameForPlan to avoid heavy
 * integration with Service/ContextManager; the JobRunner runtime path uses the same
 * selection logic to resolve an actual StreamingChatModel.
 */
class PlanModeScanModelSelectionTest {

    @Test
    void planUsesExplicitScanModelWhenProvided() {
        var spec = JobSpec.of(
                "Plan this work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ "explicit-scan-model",
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of(),
                /* reasoningLevelCode= */ (String) null);

        String chosen = JobRunner.chooseScanModelNameForPlan(spec, () -> "project-default-scan");
        assertEquals("explicit-scan-model", chosen);
    }

    @Test
    void planFallsBackToProjectDefaultWhenNoScanModel() {
        var spec = JobSpec.of(
                "Plan this work",
                /* autoCommit= */ false,
                /* autoCompress= */ false,
                /* plannerModel= */ "planner-x",
                /* scanModel= */ null,
                /* codeModel= */ null,
                /* preScan= */ false,
                /* tags= */ Map.of(),
                /* reasoningLevelCode= */ (String) null);

        String chosen = JobRunner.chooseScanModelNameForPlan(spec, () -> "project-default-scan");
        assertEquals("project-default-scan", chosen);
    }
}
