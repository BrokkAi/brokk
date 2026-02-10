package ai.brokk.init.onboarding;

import java.util.List;
import org.jetbrains.annotations.Nullable;

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
     * Unique identifier for this step (e.g., "MIGRATION", "BUILD_SETTINGS", "GIT_CONFIG").
     * Used for dependency resolution and logging.
     */
    String id();

    /**
     * IDs of steps that must complete before this step can run.
     * Empty list means no dependencies.
     * Example: BuildSettingsStep depends on MigrationStep completing first.
     */
    List<String> dependsOn();

    /**
     * Determines if this step should be included in the onboarding plan.
     * Checks ProjectState to decide if the step is needed.
     * For example, MigrationStep is applicable only if legacy style.md exists
     * with content and AGENTS.md doesn't exist.
     */
    boolean isApplicable(ProjectState state);

    /**
     * Executes the step's logic.
     * This method contains pure business logic with no UI code.
     * Returns a result that the UI layer can interpret to determine
     * what actions to take (e.g., show a dialog, update notifications).
     * Steps execute synchronously and return immediately.
     * The orchestrator handles proper sequencing via dependency resolution.
     */
    StepResult execute(ProjectState state);

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
            @Nullable OnboardingDialogData data // optional step-specific data for UI
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
        public static StepResult successWithDialog(
                String stepId, String message, @Nullable OnboardingDialogData dialogData) {
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
