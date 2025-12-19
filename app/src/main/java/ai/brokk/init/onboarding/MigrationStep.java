package ai.brokk.init.onboarding;

import ai.brokk.analyzer.ProjectFile;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Onboarding step for migration confirmation from legacy .brokk/style.md to AGENTS.md.
 * <p>
 * This step flags that a migration dialog should be shown.
 * The UI layer (Chrome) handles showing the confirm dialog and performing
 * the actual migration via StyleGuideMigrator.
 * <p>
 * This step has no dependencies and runs first if applicable.
 */
public class MigrationStep implements OnboardingStep {
    private static final Logger logger = LogManager.getLogger(MigrationStep.class);

    public static final String STEP_ID = "MIGRATION";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public List<String> dependsOn() {
        return List.of(); // No dependencies
    }

    @Override
    public boolean isApplicable(ProjectState state) {
        return state.needsMigration();
    }

    @Override
    public StepResult execute(ProjectState state) {
        logger.info("Executing migration step (flagging UI dialog)");

        return StepResult.successWithDialog(
                STEP_ID,
                "Migration dialog required",
                new MigrationDialogData(
                        new ProjectFile(state.configRoot(), ".brokk/style.md"),
                        new ProjectFile(state.configRoot(), "AGENTS.md")));
    }

    /**
     * Data for migration confirmation dialog.
     * Contains ProjectFile instances needed to perform the migration after user confirms.
     */
    public record MigrationDialogData(ProjectFile legacyStyle, ProjectFile agentsFile)
            implements OnboardingDialogData {}
}
