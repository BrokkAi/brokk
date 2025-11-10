package ai.brokk.init.onboarding;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a single step in the project onboarding process.
 * <p>
 * Steps are pure logic with NO Swing/UI dependencies. They determine whether
 * they should run based on ProjectState, declare dependencies on other steps,
 * and execute their logic returning results.
 * <p>
 * The UI layer is responsible for interpreting step results and showing
 * appropriate dialogs when needed.
 */
public interface OnboardingStep {

    /**
     * Unique identifier for this step.
     * Used for dependency resolution and logging.
     *
     * @return step ID (e.g., "MIGRATION", "BUILD_SETTINGS", "GIT_CONFIG")
     */
    String id();

    /**
     * IDs of steps that must complete before this step can run.
     * Empty list means no dependencies.
     * <p>
     * Example: BuildSettingsStep depends on MigrationStep completing first.
     *
     * @return list of step IDs this step depends on
     */
    List<String> dependsOn();

    /**
     * Determines if this step should be included in the onboarding plan.
     * <p>
     * Checks ProjectState to decide if the step is needed.
     * For example, MigrationStep is applicable only if legacy style.md exists
     * with content and AGENTS.md doesn't exist.
     *
     * @param state current project state
     * @return true if this step should be executed
     */
    boolean isApplicable(ProjectState state);

    /**
     * Executes the step's logic.
     * <p>
     * This method contains pure business logic with no UI code.
     * Returns a result that the UI layer can interpret to determine
     * what actions to take (e.g., show a dialog, update notifications).
     * <p>
     * Steps may return CompletableFuture to support async operations.
     * The orchestrator will handle proper sequencing.
     *
     * @param state current project state
     * @return future completing with step result
     */
    CompletableFuture<StepResult> execute(ProjectState state);

    /**
     * Result of executing an onboarding step.
     * <p>
     * Contains information about what happened during execution and what
     * the UI layer should do next (if anything).
     */
    record StepResult(
            String stepId,
            boolean success,
            boolean requiresUserDialog, // true if UI should show a dialog
            String message, // optional message for logging/display
            Object data // optional step-specific data for UI
            ) {

        /**
         * Creates a successful result with no dialog needed.
         */
        public static StepResult success(String stepId, String message) {
            return new StepResult(stepId, true, false, message, null);
        }

        /**
         * Creates a successful result that requires a UI dialog.
         */
        public static StepResult successWithDialog(String stepId, String message, Object dialogData) {
            return new StepResult(stepId, true, true, message, dialogData);
        }

        /**
         * Creates a failure result.
         */
        public static StepResult failure(String stepId, String message) {
            return new StepResult(stepId, false, false, message, null);
        }
    }
}
